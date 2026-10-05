package com.cafepos.sales;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.platform.SequenceService;
import com.cafepos.platform.StatusFlags;
import com.cafepos.platform.TaxMath;
import com.cafepos.platform.TaxService;
import com.cafepos.menu.MenuService;
import com.cafepos.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class SalesService {

    // ---------------------------------------------------------------- request / response shapes

    public record Line(@NotNull Long productId, @NotNull Long variantId,
                       @NotNull @DecimalMin(value = "0.001") BigDecimal quantity,
                       BigDecimal unitPrice, BigDecimal discount) {}

    public record Pay(@NotNull String method, @NotNull @DecimalMin("0") BigDecimal amount, String reference) {}

    /** unitPrice is optional: when omitted the menu price in force on txnDate is used (and recorded as list price). */
    public record SaleRequest(@NotNull LocalDate txnDate, String channel, String source, Long customerId, String notes,
                              @NotEmpty @Valid List<Line> items, @Valid List<Pay> payments,
                              String reason, String correctionSource, Integer version) {}

    public record SaleItemDto(long id, long productId, long variantId, String productName, String variantName,
                              String categoryName, BigDecimal quantity, BigDecimal listPriceAtSale,
                              BigDecimal unitPrice, BigDecimal discount, BigDecimal lineTotal, BigDecimal taxRate,
                              String taxStatus, BigDecimal netTaxable, BigDecimal taxAmount) {}

    public record PaymentDto(long id, String method, BigDecimal amount, String status, String reference) {}

    public record SaleHeader(long id, String saleNo, LocalDate txnDate, OffsetDateTime entryAt, String channel,
                             String source, Long customerId, Long orderId, BigDecimal grossInclusive,
                             BigDecimal discountTotal, BigDecimal totalInclusive, BigDecimal grossTaxable,
                             BigDecimal discountTaxable, BigDecimal netTaxable, BigDecimal taxAmount,
                             String confirmationState, boolean estimated, boolean partial, int revision,
                             String voidState, String voidReason, String notes, int version) {}

    public record SaleDetail(SaleHeader sale, List<String> flags, long backdatedDays, List<SaleItemDto> items,
                             List<PaymentDto> payments) {}

    public record SaleSummary(long id, String saleNo, LocalDate txnDate, String channel, String source,
                              BigDecimal totalInclusive, BigDecimal netTaxable, BigDecimal taxAmount,
                              String voidState, int itemCount, List<String> flags) {}

    public record SummaryRow(long id, String saleNo, LocalDate txnDate, OffsetDateTime entryAt, String channel,
                              String source, BigDecimal totalInclusive, BigDecimal netTaxable, BigDecimal taxAmount,
                              String voidState, String confirmationState, boolean estimated, boolean partial,
                              int revision, int itemCount) {}

    public record ProductRef(long productId, long variantId, String productName, String categoryName, String variantName,
                             String lifecycleStatus) {}

    record Resolved(ProductRef ref, Long menuItemId, BigDecimal qty, BigDecimal listPrice,
                            BigDecimal unitPrice, BigDecimal rate, TaxMath.LineTax tax) {}

    private static final Set<String> CHANNELS = Set.of("COUNTER", "WHATSAPP", "PHONE", "OTHER");
    private static final Set<String> SOURCES = Set.of("MANUAL", "EXCEL", "CSV", "OCR", "WHATSAPP", "COUNTER",
            "IMPORT", "SYSTEM", "OTHER");
    public static final Set<String> METHODS = Set.of("CASH", "UPI", "CARD", "WHATSAPP", "SWIGGY", "ZOMATO",
            "OTHER_ONLINE");

    private static final String HEADER_SELECT = """
            select id, sale_no, txn_date, entry_at, channel, source, customer_id, order_id, gross_inclusive,
                   discount_total, total_inclusive, gross_taxable, discount_taxable, net_taxable, tax_amount,
                   confirmation_state, is_estimated as estimated, is_partial as partial, revision, void_state,
                   void_reason, notes, version
            from sales
            """;

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final AuditService audit;
    private final BusinessClock clock;
    private final SequenceService sequences;
    private final TaxService tax;
    private final MenuService menus;

    public SalesService(JdbcClient jdbc, CafeProps props, AuditService audit, BusinessClock clock,
                        SequenceService sequences, TaxService tax, MenuService menus) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
        this.sequences = sequences;
        this.tax = tax;
        this.menus = menus;
    }

    // ---------------------------------------------------------------- queries

    public List<SaleSummary> list(LocalDate from, LocalDate to, boolean includeVoided, int limit) {
        List<SummaryRow> rows = jdbc.sql("""
                select s.id, s.sale_no, s.txn_date, s.entry_at, s.channel, s.source, s.total_inclusive,
                       s.net_taxable, s.tax_amount, s.void_state, s.confirmation_state,
                       s.is_estimated as estimated, s.is_partial as partial, s.revision,
                       (select count(*) from sale_items i where i.sale_id = s.id) as item_count
                from sales s
                where s.business_id = :b and s.txn_date between :f and :t
                  and (:inc or s.void_state = 'ACTIVE')
                order by s.txn_date desc, s.id desc
                limit :lim
                """).param("b", props.businessId()).param("f", from).param("t", to).param("inc", includeVoided)
                .param("lim", Math.min(Math.max(limit, 1), 1000)).query(SummaryRow.class).list();
        List<SaleSummary> out = new ArrayList<>();
        for (SummaryRow r : rows) {
            out.add(new SaleSummary(r.id(), r.saleNo(), r.txnDate(), r.channel(), r.source(), r.totalInclusive(),
                    r.netTaxable(), r.taxAmount(), r.voidState(), r.itemCount(),
                    StatusFlags.of(r.source(), r.confirmationState(), r.estimated(), r.partial(), r.revision(),
                            r.txnDate(), r.entryAt(), clock.zone(), r.voidState())));
        }
        return out;
    }

    public SaleDetail get(long id) {
        SaleHeader h = jdbc.sql(HEADER_SELECT + " where id = :id and business_id = :b")
                .param("id", id).param("b", props.businessId()).query(SaleHeader.class).optional()
                .orElseThrow(() -> ApiException.notFound("Sale"));
        List<SaleItemDto> items = jdbc.sql("""
                select id, product_id, variant_id, product_name, variant_name, category_name, quantity,
                       list_price_at_sale, unit_price, discount, line_total, tax_rate, tax_status, net_taxable, tax_amount
                from sale_items where sale_id = :id order by id
                """).param("id", id).query(SaleItemDto.class).list();
        List<PaymentDto> pays = jdbc.sql("select id, method, amount, status, reference from payments where sale_id = :id order by id")
                .param("id", id).query(PaymentDto.class).list();
        return new SaleDetail(h,
                StatusFlags.of(h.source(), h.confirmationState(), h.estimated(), h.partial(), h.revision(),
                        h.txnDate(), h.entryAt(), clock.zone(), h.voidState()),
                StatusFlags.backdatedDays(h.txnDate(), h.entryAt(), clock.zone()), items, pays);
    }

    // ---------------------------------------------------------------- commands

    @Transactional
    public SaleDetail create(SaleRequest req) {
        CurrentUser.require("sales.create");
        return createInternal(req, null);
    }

    /** Used by order completion: links the sale to its order. Caller has already checked permissions. */
    @Transactional
    public SaleDetail createFromOrder(SaleRequest req, long orderId) {
        return createInternal(req, orderId);
    }

    private SaleDetail createInternal(SaleRequest req, Long orderId) {
        checkDate(req.txnDate());
        String channel = req.channel() == null ? "COUNTER" : req.channel();
        String source = req.source() == null ? "MANUAL" : req.source();
        checkEnum(channel, CHANNELS, "channel");
        checkEnum(source, SOURCES, "source");
        List<Resolved> lines = resolve(req);
        Totals t = totals(lines);
        checkPayments(req.payments(), t.total());
        long id = jdbc.sql("""
                insert into sales (business_id, sale_no, txn_date, channel, source, customer_id, order_id,
                                   gross_inclusive, discount_total, total_inclusive, gross_taxable, discount_taxable,
                                   net_taxable, tax_amount, is_partial, notes, created_by, updated_by)
                values (:b, :no, :d, :ch, :src, :cust, :ord, :g, :dt, :tot, :gt, :dtt, :nt, :ta, :partial, :notes,
                        :u, :u)
                returning id
                """)
                .param("b", props.businessId()).param("no", sequences.next("SAL", "")).param("d", req.txnDate())
                .param("ch", channel).param("src", source).param("cust", req.customerId()).param("ord", orderId)
                .param("g", t.gross()).param("dt", t.discount()).param("tot", t.total())
                .param("gt", t.grossTaxable()).param("dtt", t.discountTaxable()).param("nt", t.netTaxable())
                .param("ta", t.taxAmount()).param("partial", req.payments() == null || req.payments().isEmpty())
                .param("notes", req.notes()).param("u", CurrentUser.id()).query(Long.class).single();
        insertChildren(id, lines, req.payments());
        audit.record("SALE", id, "CREATE", req.txnDate(), null, snapshot(id), req.reason(), source);
        return get(id);
    }

    @Transactional
    public SaleDetail update(long id, SaleRequest req) {
        CurrentUser.require("sales.edit");
        if (req.reason() == null || req.reason().isBlank()) {
            throw ApiException.badRequest("A reason is required to change a recorded sale");
        }
        SaleHeader cur = get(id).sale();
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("This sale is voided and cannot be edited");
        if (req.version() != null && req.version() != cur.version()) {
            throw ApiException.conflict("This sale was changed by someone else. Reload it and try again.");
        }
        checkDate(req.txnDate());
        String channel = req.channel() == null ? cur.channel() : req.channel();
        checkEnum(channel, CHANNELS, "channel");
        List<Resolved> lines = resolve(req);
        Totals t = totals(lines);
        checkPayments(req.payments(), t.total());
        String before = snapshot(id);
        jdbc.sql("delete from payments where sale_id = :id").param("id", id).update();
        jdbc.sql("delete from sale_items where sale_id = :id").param("id", id).update();
        jdbc.sql("""
                update sales set txn_date = :d, channel = :ch, customer_id = :cust, gross_inclusive = :g,
                       discount_total = :dt, total_inclusive = :tot, gross_taxable = :gt, discount_taxable = :dtt,
                       net_taxable = :nt, tax_amount = :ta, is_partial = :partial, notes = :notes,
                       revision = revision + 1, version = version + 1, updated_by = :u, updated_at = now()
                where id = :id and business_id = :b
                """)
                .param("d", req.txnDate()).param("ch", channel).param("cust", req.customerId())
                .param("g", t.gross()).param("dt", t.discount()).param("tot", t.total())
                .param("gt", t.grossTaxable()).param("dtt", t.discountTaxable()).param("nt", t.netTaxable())
                .param("ta", t.taxAmount()).param("partial", req.payments() == null || req.payments().isEmpty())
                .param("notes", req.notes()).param("u", CurrentUser.id()).param("id", id)
                .param("b", props.businessId()).update();
        insertChildren(id, lines, req.payments());
        audit.record("SALE", id, "UPDATE", req.txnDate(), before, snapshot(id), req.reason(),
                req.correctionSource() == null || req.correctionSource().isBlank() ? "MANUAL" : req.correctionSource());
        return get(id);
    }

    @Transactional
    public SaleDetail voidSale(long id, String reason) {
        CurrentUser.require("sales.void");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a sale");
        SaleHeader cur = get(id).sale();
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("Already voided");
        String before = snapshot(id);
        jdbc.sql("""
                update sales set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now(),
                       version = version + 1, updated_at = now()
                where id = :id and business_id = :b
                """).param("r", reason).param("u", CurrentUser.id()).param("id", id).param("b", props.businessId())
                .update();
        audit.record("SALE", id, "VOID", cur.txnDate(), before, snapshot(id), reason, "MANUAL");
        return get(id);
    }

    // ---------------------------------------------------------------- internals

    record Totals(BigDecimal gross, BigDecimal discount, BigDecimal total, BigDecimal grossTaxable,
                          BigDecimal discountTaxable, BigDecimal netTaxable, BigDecimal taxAmount) {}

    private Totals totals(List<Resolved> lines) {
        BigDecimal g = BigDecimal.ZERO, d = BigDecimal.ZERO, tot = BigDecimal.ZERO, gt = BigDecimal.ZERO,
                dtt = BigDecimal.ZERO, nt = BigDecimal.ZERO, ta = BigDecimal.ZERO;
        for (Resolved r : lines) {
            TaxMath.LineTax l = r.tax();
            g = g.add(l.grossInclusive());
            d = d.add(l.discount());
            tot = tot.add(l.lineTotal());
            gt = gt.add(l.grossTaxable());
            dtt = dtt.add(l.discountTaxable());
            nt = nt.add(l.netTaxable());
            ta = ta.add(l.taxAmount());
        }
        return new Totals(g, d, tot, gt, dtt, nt, ta);
    }

    private List<Resolved> resolve(SaleRequest req) {
        LocalDate today = clock.today();
        List<Resolved> out = new ArrayList<>();
        for (Line l : req.items()) {
            ProductRef ref = jdbc.sql("""
                    select p.id as product_id, pv.id as variant_id, p.name as product_name, c.name as category_name,
                           pv.name as variant_name, p.lifecycle_status
                    from products p join categories c on c.id = p.category_id
                    join product_variants pv on pv.product_id = p.id
                    where p.id = :p and pv.id = :v and p.business_id = :b
                    """).param("p", l.productId()).param("v", l.variantId()).param("b", props.businessId())
                    .query(ProductRef.class).optional()
                    .orElseThrow(() -> ApiException.badRequest("Unknown product/variant: " + l.productId() + "/" + l.variantId()));
            // History may include products that are discontinued today, but no NEW sales of them.
            if (!req.txnDate().isBefore(today) && !req.txnDate().isAfter(today)
                    && Set.of("DISCONTINUED", "ARCHIVED").contains(ref.lifecycleStatus())) {
                throw ApiException.badRequest(ref.productName() + " is " + ref.lifecycleStatus()
                        + " and cannot be sold today");
            }
            var menuItem = menus.priceFor(l.variantId(), req.txnDate());
            BigDecimal list = menuItem.map(MenuService.MenuItemDto::price).orElse(null);
            BigDecimal unit = l.unitPrice() != null ? l.unitPrice() : list;
            if (unit == null) {
                throw ApiException.badRequest("No menu price on " + req.txnDate() + " for " + ref.productName()
                        + " (" + ref.variantName() + "). Enter the price charged.");
            }
            if (unit.signum() < 0) throw ApiException.badRequest("Price cannot be negative");
            BigDecimal disc = l.discount() == null ? BigDecimal.ZERO : l.discount();
            if (disc.signum() < 0 || disc.compareTo(l.quantity().multiply(unit)) > 0) {
                throw ApiException.badRequest("Invalid discount for " + ref.productName());
            }
            BigDecimal rate = tax.rateOn(req.txnDate(), ref.categoryName()).orElse(null);
            out.add(new Resolved(ref, menuItem.map(MenuService.MenuItemDto::id).orElse(null), l.quantity(), list,
                    unit, rate, TaxMath.line(l.quantity(), unit, disc, rate)));
        }
        return out;
    }

    private void insertChildren(long saleId, List<Resolved> lines, List<Pay> payments) {
        for (Resolved r : lines) {
            TaxMath.LineTax t = r.tax();
            jdbc.sql("""
                    insert into sale_items (sale_id, product_id, variant_id, menu_item_id, product_name, variant_name,
                                            category_name, quantity, list_price_at_sale, unit_price, discount,
                                            line_total, tax_rate, tax_status, gross_taxable, discount_taxable,
                                            net_taxable, tax_amount)
                    values (:s, :p, :v, :mi, :pn, :vn, :cn, :q, :lp, :up, :d, :lt, :tr, :ts, :gt, :dt, :nt, :ta)
                    """)
                    .param("s", saleId).param("p", r.ref().productId()).param("v", r.ref().variantId())
                    .param("mi", r.menuItemId()).param("pn", r.ref().productName()).param("vn", r.ref().variantName())
                    .param("cn", r.ref().categoryName()).param("q", r.qty()).param("lp", r.listPrice())
                    .param("up", r.unitPrice()).param("d", t.discount()).param("lt", t.lineTotal())
                    .param("tr", r.rate()).param("ts", t.taxStatus()).param("gt", t.grossTaxable())
                    .param("dt", t.discountTaxable()).param("nt", t.netTaxable()).param("ta", t.taxAmount())
                    .update();
        }
        if (payments != null) {
            for (Pay p : payments) {
                jdbc.sql("insert into payments (sale_id, method, amount, reference) values (:s, :m, :a, :r)")
                        .param("s", saleId).param("m", p.method()).param("a", p.amount()).param("r", p.reference())
                        .update();
            }
        }
    }

    private void checkPayments(List<Pay> pays, BigDecimal total) {
        if (pays == null || pays.isEmpty()) return;
        BigDecimal sum = BigDecimal.ZERO;
        for (Pay p : pays) {
            checkEnum(p.method(), METHODS, "payment method");
            sum = sum.add(p.amount());
        }
        if (sum.compareTo(total) != 0) {
            throw ApiException.badRequest("Payments add up to " + sum + " but the sale total is " + total);
        }
    }

    private void checkDate(LocalDate d) {
        LocalDate today = clock.today();
        if (d.isAfter(today)) throw ApiException.badRequest("Transaction date cannot be in the future");
        if (d.isBefore(today) && !CurrentUser.has("sales.backdate")) {
            throw ApiException.forbidden("You do not have permission to enter past-dated transactions");
        }
        if (d.isBefore(clock.dataStartDate())) {
            throw ApiException.badRequest("Date is before the business data-start date " + clock.dataStartDate());
        }
    }

    private static void checkEnum(String v, Set<String> allowed, String what) {
        if (!allowed.contains(v)) throw ApiException.badRequest("Unknown " + what + ": " + v);
    }

    private String snapshot(long id) {
        return jdbc.sql("""
                select jsonb_build_object(
                  'sale', to_jsonb(s),
                  'items', coalesce((select jsonb_agg(to_jsonb(i) order by i.id) from sale_items i where i.sale_id = s.id), '[]'::jsonb),
                  'payments', coalesce((select jsonb_agg(to_jsonb(p) order by p.id) from payments p where p.sale_id = s.id), '[]'::jsonb)
                )::text from sales s where s.id = :id
                """).param("id", id).query(String.class).single();
    }
}
