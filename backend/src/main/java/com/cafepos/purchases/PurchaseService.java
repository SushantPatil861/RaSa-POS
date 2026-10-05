package com.cafepos.purchases;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.platform.SequenceService;
import com.cafepos.platform.StatusFlags;
import com.cafepos.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** Suppliers and purchase bills. Same rules as everything financial: dates split, reasons on change, void not delete. */
@Service
public class PurchaseService {

    private static final Set<String> PAY_STATUS = Set.of("UNPAID", "PARTIAL", "PAID");
    private static final Set<String> TREATMENT = Set.of("INVENTORY", "EXPENSE", "FIXED_ASSET");
    private static final Set<String> SOURCES = Set.of("MANUAL", "EXCEL", "CSV", "OCR", "WHATSAPP", "COUNTER", "IMPORT", "SYSTEM", "OTHER");

    public record SupplierDto(long id, String name, String contact, String category, String paymentTerms,
                              String gstin, String pan, String notes, boolean active) {}

    public record SupplierRequest(String name, String contact, String category, String paymentTerms,
                                  String gstin, String pan, String notes) {}

    public record ItemDto(long id, String itemName, BigDecimal quantity, String unit, BigDecimal unitPrice,
                          BigDecimal total, String treatment) {}

    public record ItemRequest(String itemName, BigDecimal quantity, String unit, BigDecimal unitPrice,
                              BigDecimal total, String treatment) {}

    public record PurchaseRow(long id, String purchaseNo, LocalDate txnDate, OffsetDateTime entryAt, Long supplierId,
                              String supplierName, String invoiceNo, LocalDate invoiceDate, String paymentStatus,
                              BigDecimal totalAmount, String notes, String source, String confirmationState,
                              boolean estimated, boolean partial, int revision, String voidState, String voidReason,
                              int version) {}

    public record PurchaseDto(long id, String purchaseNo, LocalDate txnDate, OffsetDateTime entryAt, Long supplierId,
                              String supplierName, String invoiceNo, LocalDate invoiceDate, String paymentStatus,
                              BigDecimal totalAmount, String notes, String source, String confirmationState,
                              boolean estimated, boolean partial, int revision, String voidState, String voidReason,
                              int version, List<String> flags, List<ItemDto> items) {}

    public record PurchaseRequest(LocalDate txnDate, Long supplierId, String invoiceNo, LocalDate invoiceDate,
                                  String paymentStatus, BigDecimal totalAmount, String notes, String source,
                                  Boolean estimated, Boolean partial, List<ItemRequest> items,
                                  String reason, Integer version) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;
    private final SequenceService sequences;

    public PurchaseService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit,
                           SequenceService sequences) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
        this.sequences = sequences;
    }

    // ------------------------------------------------------------ suppliers

    public List<SupplierDto> suppliers() {
        return jdbc.sql("""
                select id, name, contact, category, payment_terms, gstin, pan, notes, active
                from suppliers where business_id = :b order by name
                """).param("b", props.businessId()).query(SupplierDto.class).list();
    }

    @Transactional
    public SupplierDto createSupplier(SupplierRequest r) {
        CurrentUser.require("purchases.create");
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Supplier name is required");
        long id;
        try {
            id = jdbc.sql("""
                    insert into suppliers (business_id, name, contact, category, payment_terms, gstin, pan, notes)
                    values (:b, :n, :c, :cat, :pt, :g, :p, :notes) returning id
                    """).param("b", props.businessId()).param("n", r.name().trim()).param("c", r.contact())
                    .param("cat", r.category()).param("pt", r.paymentTerms()).param("g", r.gstin())
                    .param("p", r.pan()).param("notes", r.notes()).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A supplier named '" + r.name().trim() + "' already exists");
        }
        audit.record("SUPPLIER", id, "CREATE", null, null,
                jdbc.sql("select to_jsonb(s)::text from suppliers s where s.id = :id").param("id", id)
                        .query(String.class).single(), null, "MANUAL");
        return jdbc.sql("select id, name, contact, category, payment_terms, gstin, pan, notes, active from suppliers where id = :id")
                .param("id", id).query(SupplierDto.class).single();
    }

    // ------------------------------------------------------------ purchases

    public List<PurchaseDto> list(LocalDate from, LocalDate to, Long supplierId, boolean includeVoided, int limit) {
        return jdbc.sql(baseSelect() + """
                where p.business_id = :b
                  and (cast(:f as date) is null or p.txn_date >= :f)
                  and (cast(:t as date) is null or p.txn_date <= :t)
                  and (cast(:s as bigint) is null or p.supplier_id = :s)
                  and (:inc or p.void_state = 'ACTIVE')
                order by p.txn_date desc, p.id desc limit :lim
                """).param("b", props.businessId()).param("f", from).param("t", to).param("s", supplierId)
                .param("inc", includeVoided).param("lim", Math.min(Math.max(limit, 1), 500))
                .query(PurchaseRow.class).list().stream().map(r -> toDto(r, List.of())).toList();
    }

    public PurchaseDto get(long id) {
        return toDto(row(id), items(id));
    }

    @Transactional
    public PurchaseDto create(PurchaseRequest r) {
        CurrentUser.require("purchases.create");
        validate(r);
        String source = r.source() == null || r.source().isBlank() ? "MANUAL" : r.source();
        if (!SOURCES.contains(source)) throw ApiException.badRequest("Unknown source: " + source);
        String no = sequences.next("PUR", "");
        long id = jdbc.sql("""
                insert into purchases (business_id, purchase_no, txn_date, supplier_id, invoice_no, invoice_date,
                                       payment_status, total_amount, notes, source, is_estimated, is_partial,
                                       created_by, updated_by)
                values (:b, :no, :d, :s, :inv, :invd, :ps, :tot, :n, :src, :est, :part, :u, :u)
                returning id
                """)
                .param("b", props.businessId()).param("no", no).param("d", r.txnDate()).param("s", r.supplierId())
                .param("inv", r.invoiceNo()).param("invd", r.invoiceDate())
                .param("ps", r.paymentStatus() == null ? "UNPAID" : r.paymentStatus())
                .param("tot", r.totalAmount()).param("n", r.notes()).param("src", source)
                .param("est", Boolean.TRUE.equals(r.estimated())).param("part", Boolean.TRUE.equals(r.partial()))
                .param("u", CurrentUser.id()).query(Long.class).single();
        insertItems(id, r.items());
        audit.record("PURCHASE", id, "CREATE", r.txnDate(), null, snapshot(id), null, source);
        return get(id);
    }

    @Transactional
    public PurchaseDto update(long id, PurchaseRequest r) {
        CurrentUser.require("purchases.edit");
        if (r.reason() == null || r.reason().isBlank()) {
            throw ApiException.badRequest("A reason is required to change a recorded purchase");
        }
        PurchaseRow cur = row(id);
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("This purchase is voided and cannot be edited");
        if (r.version() != null && r.version() != cur.version()) {
            throw ApiException.conflict("This purchase was changed by someone else. Reload it and try again.");
        }
        validate(r);
        String before = snapshot(id);
        jdbc.sql("delete from purchase_items where purchase_id = :id").param("id", id).update();
        jdbc.sql("""
                update purchases set txn_date = :d, supplier_id = :s, invoice_no = :inv, invoice_date = :invd,
                       payment_status = :ps, total_amount = :tot, notes = :n, is_estimated = :est, is_partial = :part,
                       revision = revision + 1, version = version + 1, updated_by = :u, updated_at = now()
                where id = :id and business_id = :b
                """)
                .param("d", r.txnDate()).param("s", r.supplierId()).param("inv", r.invoiceNo())
                .param("invd", r.invoiceDate()).param("ps", r.paymentStatus() == null ? cur.paymentStatus() : r.paymentStatus())
                .param("tot", r.totalAmount()).param("n", r.notes()).param("est", Boolean.TRUE.equals(r.estimated()))
                .param("part", Boolean.TRUE.equals(r.partial())).param("u", CurrentUser.id())
                .param("id", id).param("b", props.businessId()).update();
        insertItems(id, r.items());
        audit.record("PURCHASE", id, "UPDATE", r.txnDate(), before, snapshot(id), r.reason(), "MANUAL");
        return get(id);
    }

    @Transactional
    public PurchaseDto voidPurchase(long id, String reason) {
        CurrentUser.require("purchases.edit");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a purchase");
        PurchaseRow cur = row(id);
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("Already voided");
        String before = snapshot(id);
        jdbc.sql("""
                update purchases set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now(),
                       version = version + 1, updated_at = now()
                where id = :id and business_id = :b
                """).param("r", reason).param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("PURCHASE", id, "VOID", cur.txnDate(), before, snapshot(id), reason, "MANUAL");
        return get(id);
    }

    // ------------------------------------------------------------ internals

    private void validate(PurchaseRequest r) {
        if (r.txnDate() == null) throw ApiException.badRequest("Date is required");
        if (r.totalAmount() == null || r.totalAmount().signum() < 0) throw ApiException.badRequest("Total must be zero or more");
        if (r.txnDate().isAfter(clock.today())) throw ApiException.badRequest("Transaction date cannot be in the future");
        if (r.txnDate().isBefore(clock.dataStartDate())) {
            throw ApiException.badRequest("Date is before the business data-start date " + clock.dataStartDate());
        }
        if (r.paymentStatus() != null && !PAY_STATUS.contains(r.paymentStatus())) {
            throw ApiException.badRequest("Unknown payment status: " + r.paymentStatus());
        }
        if (r.items() != null) {
            for (ItemRequest i : r.items()) {
                if (i.itemName() == null || i.itemName().isBlank()) throw ApiException.badRequest("Every line needs an item name");
                if (i.quantity() == null || i.quantity().signum() < 0) throw ApiException.badRequest("Quantity must be zero or more");
                if (i.total() == null || i.total().signum() < 0) throw ApiException.badRequest("Line total must be zero or more");
                if (i.treatment() != null && !TREATMENT.contains(i.treatment())) {
                    throw ApiException.badRequest("Unknown treatment: " + i.treatment());
                }
            }
        }
    }

    private void insertItems(long purchaseId, List<ItemRequest> items) {
        if (items == null) return;
        for (ItemRequest i : items) {
            jdbc.sql("""
                    insert into purchase_items (purchase_id, item_name, quantity, unit, unit_price, total, treatment)
                    values (:p, :n, :q, :u, :up, :t, :tr)
                    """).param("p", purchaseId).param("n", i.itemName().trim()).param("q", i.quantity())
                    .param("u", i.unit()).param("up", i.unitPrice()).param("t", i.total())
                    .param("tr", i.treatment() == null ? "INVENTORY" : i.treatment()).update();
        }
    }

    private String baseSelect() {
        return """
                select p.id, p.purchase_no, p.txn_date, p.entry_at, p.supplier_id, s.name as supplier_name,
                       p.invoice_no, p.invoice_date, p.payment_status, p.total_amount, p.notes, p.source,
                       p.confirmation_state, p.is_estimated as estimated, p.is_partial as partial, p.revision,
                       p.void_state, p.void_reason, p.version
                from purchases p left join suppliers s on s.id = p.supplier_id
                """;
    }

    private PurchaseRow row(long id) {
        return jdbc.sql(baseSelect() + " where p.id = :id and p.business_id = :b")
                .param("id", id).param("b", props.businessId()).query(PurchaseRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Purchase"));
    }

    private List<ItemDto> items(long purchaseId) {
        return jdbc.sql("""
                select id, item_name, quantity, unit, unit_price, total, treatment
                from purchase_items where purchase_id = :p order by id
                """).param("p", purchaseId).query(ItemDto.class).list();
    }

    private PurchaseDto toDto(PurchaseRow r, List<ItemDto> items) {
        return new PurchaseDto(r.id(), r.purchaseNo(), r.txnDate(), r.entryAt(), r.supplierId(), r.supplierName(),
                r.invoiceNo(), r.invoiceDate(), r.paymentStatus(), r.totalAmount(), r.notes(), r.source(),
                r.confirmationState(), r.estimated(), r.partial(), r.revision(), r.voidState(), r.voidReason(),
                r.version(),
                StatusFlags.of(r.source(), r.confirmationState(), r.estimated(), r.partial(), r.revision(),
                        r.txnDate(), r.entryAt(), clock.zone(), r.voidState()), items);
    }

    private String snapshot(long id) {
        return jdbc.sql("""
                select jsonb_build_object('purchase', to_jsonb(p),
                  'items', coalesce((select jsonb_agg(to_jsonb(i) order by i.id) from purchase_items i where i.purchase_id = p.id), '[]'::jsonb)
                )::text from purchases p where p.id = :id
                """).param("id", id).query(String.class).single();
    }
}
