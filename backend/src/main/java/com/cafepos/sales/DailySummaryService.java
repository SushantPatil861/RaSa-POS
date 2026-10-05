package com.cafepos.sales;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.platform.StatusFlags;
import com.cafepos.platform.TaxMath;
import com.cafepos.platform.TaxService;
import com.cafepos.security.CurrentUser;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * QUICK DAILY ENTRY. total_sales is the gross billed amount (GST-inclusive, before discounts and refunds).
 * Net collected = total - discounts - refunds. The payment split (paid via) is compared with it, never forced.
 */
@Service
public class DailySummaryService {

    public record SummaryRequest(@NotNull LocalDate txnDate, @NotNull @DecimalMin("0") BigDecimal totalSales,
                                 BigDecimal cash, BigDecimal upi, BigDecimal card, BigDecimal whatsapp,
                                 BigDecimal swiggy, BigDecimal zomato, BigDecimal otherOnline, Integer ordersCount,
                                 BigDecimal discounts, BigDecimal refunds, BigDecimal wastage, BigDecimal otherIncome,
                                 String notes, String reason, String correctionSource, Integer version) {}

    public record SummaryRow(long id, LocalDate txnDate, OffsetDateTime entryAt, BigDecimal totalSales,
                             BigDecimal cash, BigDecimal upi, BigDecimal card, BigDecimal whatsapp,
                             BigDecimal swiggy, BigDecimal zomato, BigDecimal otherOnline, Integer ordersCount,
                             BigDecimal discounts, BigDecimal refunds, BigDecimal wastage, BigDecimal otherIncome,
                             String notes, BigDecimal taxRate, String taxStatus, BigDecimal netTaxable,
                             BigDecimal taxAmount, String source, String confirmationState, boolean estimated,
                             boolean partial, int revision, String voidState, String voidReason, int version) {}

    public record SummaryDto(SummaryRow summary, BigDecimal netCollected, BigDecimal tenderTotal,
                             BigDecimal tenderDifference, List<String> flags, long backdatedDays) {}

    private static final String SELECT = """
            select id, txn_date, entry_at, total_sales, cash, upi, card, whatsapp, swiggy, zomato, other_online,
                   orders_count, discounts, refunds, wastage, other_income, notes, tax_rate, tax_status,
                   net_taxable, tax_amount, source, confirmation_state, is_estimated as estimated,
                   is_partial as partial, revision, void_state, void_reason, version
            from daily_sales_summaries
            """;

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final AuditService audit;
    private final BusinessClock clock;
    private final TaxService tax;

    public DailySummaryService(JdbcClient jdbc, CafeProps props, AuditService audit, BusinessClock clock,
                               TaxService tax) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
        this.tax = tax;
    }

    public List<SummaryDto> list(LocalDate from, LocalDate to, boolean includeVoided) {
        return jdbc.sql(SELECT + """
                where business_id = :b and txn_date between :f and :t and (:inc or void_state = 'ACTIVE')
                order by txn_date desc, id desc
                """).param("b", props.businessId()).param("f", from).param("t", to).param("inc", includeVoided)
                .query(SummaryRow.class).list().stream().map(this::toDto).toList();
    }

    public SummaryDto get(long id) {
        return toDto(row(id));
    }

    @Transactional
    public SummaryDto create(SummaryRequest r) {
        CurrentUser.require("sales.create");
        checkDate(r.txnDate());
        Integer existing = jdbc.sql("""
                select count(*) from daily_sales_summaries
                where business_id = :b and txn_date = :d and void_state = 'ACTIVE'
                """).param("b", props.businessId()).param("d", r.txnDate()).query(Integer.class).single();
        if (existing > 0) {
            throw ApiException.conflict("A quick daily entry already exists for " + r.txnDate()
                    + ". Edit that entry instead.");
        }
        Calc c = calc(r);
        long id = jdbc.sql("""
                insert into daily_sales_summaries (business_id, txn_date, total_sales, cash, upi, card, whatsapp,
                    swiggy, zomato, other_online, orders_count, discounts, refunds, wastage, other_income, notes,
                    tax_rate, tax_status, gross_taxable, discount_taxable, refund_taxable, net_taxable, tax_amount,
                    is_partial, created_by, updated_by)
                values (:b, :d, :ts, :cash, :upi, :card, :wa, :sw, :zo, :oo, :oc, :disc, :ref, :wst, :oi, :notes,
                        :tr, :tst, :gt, :dtt, :rt, :nt, :ta, :partial, :u, :u)
                returning id
                """)
                .param("b", props.businessId()).param("d", r.txnDate()).param("ts", r.totalSales())
                .param("cash", z(r.cash())).param("upi", z(r.upi())).param("card", z(r.card()))
                .param("wa", z(r.whatsapp())).param("sw", z(r.swiggy())).param("zo", z(r.zomato()))
                .param("oo", z(r.otherOnline())).param("oc", r.ordersCount()).param("disc", z(r.discounts()))
                .param("ref", z(r.refunds())).param("wst", r.wastage()).param("oi", r.otherIncome())
                .param("notes", r.notes()).param("tr", c.rate).param("tst", c.status).param("gt", c.grossTaxable)
                .param("dtt", c.discountTaxable).param("rt", c.refundTaxable).param("nt", c.netTaxable)
                .param("ta", c.taxAmount).param("partial", c.tenderTotal.signum() == 0)
                .param("u", CurrentUser.id()).query(Long.class).single();
        audit.record("DAILY_SUMMARY", id, "CREATE", r.txnDate(), null, snapshot(id), r.reason(), "MANUAL");
        return get(id);
    }

    @Transactional
    public SummaryDto update(long id, SummaryRequest r) {
        CurrentUser.require("sales.edit");
        if (r.reason() == null || r.reason().isBlank()) {
            throw ApiException.badRequest("A reason is required to change a recorded daily entry");
        }
        SummaryRow cur = row(id);
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("This entry is voided and cannot be edited");
        if (r.version() != null && r.version() != cur.version()) {
            throw ApiException.conflict("This entry was changed by someone else. Reload it and try again.");
        }
        checkDate(r.txnDate());
        if (!r.txnDate().equals(cur.txnDate())) {
            Integer clash = jdbc.sql("""
                    select count(*) from daily_sales_summaries
                    where business_id = :b and txn_date = :d and void_state = 'ACTIVE' and id <> :id
                    """).param("b", props.businessId()).param("d", r.txnDate()).param("id", id)
                    .query(Integer.class).single();
            if (clash > 0) throw ApiException.conflict("A quick daily entry already exists for " + r.txnDate());
        }
        Calc c = calc(r);
        String before = snapshot(id);
        jdbc.sql("""
                update daily_sales_summaries set txn_date = :d, total_sales = :ts, cash = :cash, upi = :upi,
                    card = :card, whatsapp = :wa, swiggy = :sw, zomato = :zo, other_online = :oo, orders_count = :oc,
                    discounts = :disc, refunds = :ref, wastage = :wst, other_income = :oi, notes = :notes,
                    tax_rate = :tr, tax_status = :tst, gross_taxable = :gt, discount_taxable = :dtt,
                    refund_taxable = :rt, net_taxable = :nt, tax_amount = :ta, is_partial = :partial,
                    revision = revision + 1, version = version + 1, updated_by = :u, updated_at = now()
                where id = :id and business_id = :b
                """)
                .param("d", r.txnDate()).param("ts", r.totalSales())
                .param("cash", z(r.cash())).param("upi", z(r.upi())).param("card", z(r.card()))
                .param("wa", z(r.whatsapp())).param("sw", z(r.swiggy())).param("zo", z(r.zomato()))
                .param("oo", z(r.otherOnline())).param("oc", r.ordersCount()).param("disc", z(r.discounts()))
                .param("ref", z(r.refunds())).param("wst", r.wastage()).param("oi", r.otherIncome())
                .param("notes", r.notes()).param("tr", c.rate).param("tst", c.status).param("gt", c.grossTaxable)
                .param("dtt", c.discountTaxable).param("rt", c.refundTaxable).param("nt", c.netTaxable)
                .param("ta", c.taxAmount).param("partial", c.tenderTotal.signum() == 0)
                .param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("DAILY_SUMMARY", id, "UPDATE", r.txnDate(), before, snapshot(id), r.reason(),
                r.correctionSource() == null || r.correctionSource().isBlank() ? "MANUAL" : r.correctionSource());
        return get(id);
    }

    @Transactional
    public SummaryDto voidEntry(long id, String reason) {
        CurrentUser.require("sales.void");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void an entry");
        SummaryRow cur = row(id);
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("Already voided");
        String before = snapshot(id);
        jdbc.sql("""
                update daily_sales_summaries set void_state = 'VOIDED', void_reason = :r, voided_by = :u,
                       voided_at = now(), version = version + 1, updated_at = now()
                where id = :id and business_id = :b
                """).param("r", reason).param("u", CurrentUser.id()).param("id", id).param("b", props.businessId())
                .update();
        audit.record("DAILY_SUMMARY", id, "VOID", cur.txnDate(), before, snapshot(id), reason, "MANUAL");
        return get(id);
    }

    // ------------------------------------------------------------------ internals

    private static final class Calc {
        BigDecimal rate, grossTaxable, discountTaxable, refundTaxable, netTaxable, taxAmount, tenderTotal;
        String status;
    }

    private Calc calc(SummaryRequest r) {
        BigDecimal discounts = z(r.discounts());
        BigDecimal refunds = z(r.refunds());
        if (discounts.add(refunds).compareTo(r.totalSales()) > 0) {
            throw ApiException.badRequest("Discounts plus refunds cannot exceed total sales");
        }
        for (BigDecimal v : new BigDecimal[]{r.cash(), r.upi(), r.card(), r.whatsapp(), r.swiggy(), r.zomato(),
                r.otherOnline(), r.wastage(), r.otherIncome()}) {
            if (v != null && v.signum() < 0) throw ApiException.badRequest("Amounts cannot be negative");
        }
        Calc c = new Calc();
        c.rate = tax.rateOn(r.txnDate(), null).orElse(null);
        c.status = c.rate == null ? "NOT_CONFIGURED" : "EXTRACTED";
        c.grossTaxable = TaxMath.extractTaxable(r.totalSales(), c.rate);
        c.discountTaxable = TaxMath.extractTaxable(discounts, c.rate);
        c.refundTaxable = TaxMath.extractTaxable(refunds, c.rate);
        c.netTaxable = c.grossTaxable.subtract(c.discountTaxable).subtract(c.refundTaxable);
        BigDecimal netInclusive = r.totalSales().subtract(discounts).subtract(refunds);
        c.taxAmount = netInclusive.subtract(c.netTaxable);
        c.tenderTotal = z(r.cash()).add(z(r.upi())).add(z(r.card())).add(z(r.whatsapp()))
                .add(z(r.swiggy())).add(z(r.zomato())).add(z(r.otherOnline()));
        return c;
    }

    private SummaryDto toDto(SummaryRow s) {
        BigDecimal net = s.totalSales().subtract(s.discounts()).subtract(s.refunds());
        BigDecimal tender = s.cash().add(s.upi()).add(s.card()).add(s.whatsapp()).add(s.swiggy()).add(s.zomato())
                .add(s.otherOnline());
        return new SummaryDto(s, net, tender, tender.subtract(net),
                StatusFlags.of(s.source(), s.confirmationState(), s.estimated(), s.partial(), s.revision(),
                        s.txnDate(), s.entryAt(), clock.zone(), s.voidState()),
                StatusFlags.backdatedDays(s.txnDate(), s.entryAt(), clock.zone()));
    }

    private SummaryRow row(long id) {
        return jdbc.sql(SELECT + " where id = :id and business_id = :b").param("id", id)
                .param("b", props.businessId()).query(SummaryRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Daily entry"));
    }

    private void checkDate(LocalDate d) {
        LocalDate today = clock.today();
        if (d.isAfter(today)) throw ApiException.badRequest("Date cannot be in the future");
        if (d.isBefore(today) && !CurrentUser.has("sales.backdate")) {
            throw ApiException.forbidden("You do not have permission to enter past-dated transactions");
        }
        if (d.isBefore(clock.dataStartDate())) {
            throw ApiException.badRequest("Date is before the business data-start date " + clock.dataStartDate());
        }
    }

    private String snapshot(long id) {
        return jdbc.sql("select to_jsonb(s)::text from daily_sales_summaries s where s.id = :id")
                .param("id", id).query(String.class).single();
    }

    private static BigDecimal z(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
