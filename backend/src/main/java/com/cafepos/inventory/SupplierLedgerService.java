package com.cafepos.inventory;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Supplier ledger: purchases (non-void) vs payments, with aging. Purchases already marked PAID at purchase time are not
 * owed; UNPAID and PARTIAL purchases are treated as fully on credit (the purchase record has no part-paid amount) and
 * flagged. Payments are applied oldest bill first.
 */
@Service
public class SupplierLedgerService {

    private static final Set<String> METHODS = Set.of("CASH", "UPI", "CARD", "BANK", "CHEQUE", "OTHER");

    public record PaymentRow(long id, long supplierId, String supplierName, LocalDate txnDate, OffsetDateTime entryAt,
                             BigDecimal amount, String method, String reference, String notes, String voidState,
                             String voidReason) {}

    public record PaymentRequest(Long supplierId, LocalDate txnDate, BigDecimal amount, String method, String reference,
                                 String notes) {}

    public record PurchaseLine(long id, String purchaseNo, LocalDate txnDate, String invoiceNo, BigDecimal totalAmount,
                               String paymentStatus, String confirmationState) {}

    public record PaymentLine(long id, LocalDate txnDate, BigDecimal amount, String method, String reference, String notes) {}

    public record SupplierBalanceRow(long supplierId, String supplierName, BigDecimal purchasesTotal,
                                     BigDecimal creditPurchases, BigDecimal paymentsTotal) {}

    public record SupplierBalance(long supplierId, String supplierName, BigDecimal purchasesTotal, BigDecimal creditPurchases,
                                  BigDecimal paymentsTotal, BigDecimal balance) {}

    public record Ledger(long supplierId, String supplierName, LocalDate asOf, BigDecimal purchasesTotal,
                         BigDecimal paidAtPurchase, BigDecimal creditPurchases, BigDecimal paymentsTotal, BigDecimal balance,
                         BigDecimal advance, BigDecimal balanceIncludingPaidAtPurchase, LedgerMath.Aging aging,
                         List<PurchaseLine> purchases, List<PaymentLine> payments, List<String> flags, List<String> notes) {}

    public record SupplierName(long id, String name) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;

    public SupplierLedgerService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
    }

    public List<SupplierBalance> balances(LocalDate asOf) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        List<SupplierBalanceRow> rows = jdbc.sql("""
                select s.id as supplier_id, s.name as supplier_name, coalesce(p.total, 0) as purchases_total,
                       coalesce(p.credit, 0) as credit_purchases, coalesce(pay.total, 0) as payments_total
                from suppliers s
                left join (select supplier_id, sum(total_amount) as total,
                                  sum(total_amount) filter (where payment_status <> 'PAID') as credit
                           from purchases where business_id = :b and void_state = 'ACTIVE' and txn_date <= :d and supplier_id is not null
                           group by supplier_id) p on p.supplier_id = s.id
                left join (select supplier_id, sum(amount) as total from supplier_payments
                           where business_id = :b and void_state = 'ACTIVE' and txn_date <= :d group by supplier_id) pay
                       on pay.supplier_id = s.id
                where s.business_id = :b and (p.supplier_id is not null or pay.supplier_id is not null)
                order by lower(s.name)
                """).param("b", props.businessId()).param("d", d).query(SupplierBalanceRow.class).list();
        List<SupplierBalance> out = new ArrayList<>();
        for (SupplierBalanceRow r : rows) {
            out.add(new SupplierBalance(r.supplierId(), r.supplierName(), r.purchasesTotal(), r.creditPurchases(),
                    r.paymentsTotal(), r.creditPurchases().subtract(r.paymentsTotal())));
        }
        return out;
    }

    public Ledger ledger(long supplierId, LocalDate asOf) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        SupplierName s = jdbc.sql("select id, name from suppliers where id = :id and business_id = :b")
                .param("id", supplierId).param("b", props.businessId()).query(SupplierName.class).optional()
                .orElseThrow(() -> ApiException.notFound("Supplier"));
        List<PurchaseLine> purchases = jdbc.sql("""
                select id, purchase_no, txn_date, invoice_no, total_amount, payment_status, confirmation_state
                from purchases where business_id = :b and supplier_id = :s and void_state = 'ACTIVE' and txn_date <= :d
                order by txn_date, id
                """).param("b", props.businessId()).param("s", supplierId).param("d", d).query(PurchaseLine.class).list();
        List<PaymentLine> payments = jdbc.sql("""
                select id, txn_date, amount, method, reference, notes
                from supplier_payments where business_id = :b and supplier_id = :s and void_state = 'ACTIVE' and txn_date <= :d
                order by txn_date, id
                """).param("b", props.businessId()).param("s", supplierId).param("d", d).query(PaymentLine.class).list();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal paidAtPurchase = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        boolean partial = false;
        boolean draft = false;
        List<LedgerMath.Bill> bills = new ArrayList<>();
        for (PurchaseLine p : purchases) {
            total = total.add(p.totalAmount());
            if ("PAID".equals(p.paymentStatus())) {
                paidAtPurchase = paidAtPurchase.add(p.totalAmount());
            } else {
                credit = credit.add(p.totalAmount());
                bills.add(new LedgerMath.Bill(p.id(), p.txnDate(), p.totalAmount()));
            }
            if ("PARTIAL".equals(p.paymentStatus())) partial = true;
            if ("DRAFT".equals(p.confirmationState())) draft = true;
        }
        BigDecimal paid = BigDecimal.ZERO;
        for (PaymentLine p : payments) paid = paid.add(p.amount());
        LedgerMath.Aging aging = LedgerMath.age(bills, paid, d);
        List<String> flags = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (partial) {
            flags.add("PARTIAL");
            notes.add("Some purchases are marked PARTIAL: the purchase record does not hold the part-paid amount, so they are treated as fully on credit. Record the payments here to settle them.");
        }
        if (draft) {
            flags.add("INCLUDES_DRAFT");
            notes.add("Some purchases are still DRAFT.");
        }
        notes.add("Purchases marked PAID at purchase time are not counted as owed. Payments are applied to the oldest bill first.");
        return new Ledger(s.id(), s.name(), d, total, paidAtPurchase, credit, paid, aging.outstanding(), aging.advance(),
                total.subtract(paid), aging, purchases, payments, flags, notes);
    }

    public List<PaymentRow> payments(Long supplierId, LocalDate from, LocalDate to, boolean includeVoided) {
        return jdbc.sql("""
                select p.id, p.supplier_id, s.name as supplier_name, p.txn_date, p.entry_at, p.amount, p.method, p.reference,
                       p.notes, p.void_state, p.void_reason
                from supplier_payments p join suppliers s on s.id = p.supplier_id
                where p.business_id = :b and (:inc or p.void_state = 'ACTIVE')
                  and (cast(:s as bigint) is null or p.supplier_id = :s)
                  and (cast(:f as date) is null or p.txn_date >= :f) and (cast(:t as date) is null or p.txn_date <= :t)
                order by p.txn_date desc, p.id desc limit 300
                """).param("b", props.businessId()).param("inc", includeVoided).param("s", supplierId).param("f", from)
                .param("t", to).query(PaymentRow.class).list();
    }

    @Transactional
    public PaymentRow createPayment(PaymentRequest r) {
        CurrentUser.require("supplier_ledger.manage");
        if (r.supplierId() == null) throw ApiException.badRequest("Choose the supplier");
        if (r.txnDate() == null) throw ApiException.badRequest("Payment date is required");
        if (r.txnDate().isAfter(clock.today())) throw ApiException.badRequest("Payment date cannot be in the future");
        if (r.amount() == null || r.amount().signum() <= 0) throw ApiException.badRequest("Amount must be more than zero");
        if (r.method() == null || !METHODS.contains(r.method())) throw ApiException.badRequest("Payment method must be one of " + String.join(", ", new java.util.TreeSet<>(METHODS)));
        boolean ok = jdbc.sql("select exists (select 1 from suppliers where id = :id and business_id = :b)")
                .param("id", r.supplierId()).param("b", props.businessId()).query(Boolean.class).single();
        if (!ok) throw ApiException.notFound("Supplier");
        long id = jdbc.sql("""
                insert into supplier_payments (business_id, supplier_id, txn_date, amount, method, reference, notes, created_by)
                values (:b, :s, :d, :a, :m, :ref, :n, :u) returning id
                """).param("b", props.businessId()).param("s", r.supplierId()).param("d", r.txnDate()).param("a", r.amount())
                .param("m", r.method()).param("ref", blank(r.reference())).param("n", blank(r.notes()))
                .param("u", CurrentUser.id()).query(Long.class).single();
        audit.record("SUPPLIER_PAYMENT", id, "CREATE", r.txnDate(), null, snapshot(id), null, "MANUAL");
        return one(id);
    }

    @Transactional
    public PaymentRow voidPayment(long id, String reason) {
        CurrentUser.require("supplier_ledger.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a payment");
        PaymentRow cur = jdbc.sql("""
                select p.id, p.supplier_id, s.name as supplier_name, p.txn_date, p.entry_at, p.amount, p.method, p.reference,
                       p.notes, p.void_state, p.void_reason
                from supplier_payments p join suppliers s on s.id = p.supplier_id where p.id = :id and p.business_id = :b
                """).param("id", id).param("b", props.businessId()).query(PaymentRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("Already voided");
        String before = snapshot(id);
        jdbc.sql("update supplier_payments set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now() where id = :id")
                .param("r", reason).param("u", CurrentUser.id()).param("id", id).update();
        audit.record("SUPPLIER_PAYMENT", id, "VOID", cur.txnDate(), before, snapshot(id), reason, "MANUAL");
        return one(id);
    }

    private PaymentRow one(long id) {
        return jdbc.sql("""
                select p.id, p.supplier_id, s.name as supplier_name, p.txn_date, p.entry_at, p.amount, p.method, p.reference,
                       p.notes, p.void_state, p.void_reason
                from supplier_payments p join suppliers s on s.id = p.supplier_id where p.id = :id
                """).param("id", id).query(PaymentRow.class).single();
    }

    private String snapshot(long id) {
        return jdbc.sql("select to_jsonb(p)::text from supplier_payments p where p.id = :id").param("id", id)
                .query(String.class).single();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
