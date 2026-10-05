package com.cafepos.inventory;

import com.cafepos.audit.AuditService;
import com.cafepos.costing.CostResolver;
import com.cafepos.costing.CostingService;
import com.cafepos.costing.UnitConverter;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Wastage of an ingredient (base units) or of a finished product variant (portions). The cost is snapshotted at entry
 * (as of the wastage date) with its status; if unknown it stays unknown. Ingredient stock is reduced through WASTAGE
 * movements; a void reverses them (never deletes).
 */
@Service
public class WastageService {

    private static final Set<String> REASONS = Set.of("EXPIRED", "SPOILED", "SPILLAGE", "OVER_PORTION", "STAFF_MEAL", "SAMPLE", "OTHER");

    public record WastageRow(long id, LocalDate txnDate, OffsetDateTime entryAt, Long ingredientId, String ingredientName,
                             String baseUnit, Long productId, Long variantId, String productName, String variantName,
                             BigDecimal quantity, String reason, BigDecimal unitCost, BigDecimal costTotal, String costStatus,
                             boolean costEstimated, String notes, String voidState, String voidReason) {}

    public record WastageDto(long id, LocalDate txnDate, OffsetDateTime entryAt, Long ingredientId, String ingredientName,
                             String baseUnit, Long productId, Long variantId, String productName, String variantName,
                             BigDecimal quantity, String reason, BigDecimal unitCost, BigDecimal costTotal, String costStatus,
                             boolean costEstimated, String notes, String voidState, String voidReason, List<String> flags) {}

    public record WastageRequest(LocalDate txnDate, Long ingredientId, Long variantId, BigDecimal quantity, String unit,
                                 String reason, String notes) {}

    public record ReasonSummary(String reason, long entries, BigDecimal costKnown, long entriesWithoutCost) {}

    public record PeriodSummary(String period, long entries, BigDecimal costKnown, long entriesWithoutCost) {}

    public record Summary(LocalDate from, LocalDate to, BigDecimal totalCostKnown, long entries, long entriesWithoutCost,
                          boolean costsHidden, List<ReasonSummary> byReason, List<PeriodSummary> byMonth, List<String> flags) {}

    public record VariantInfo(long variantId, long productId) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;
    private final LedgerService ledger;
    private final CostingService costing;

    public WastageService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit, LedgerService ledger,
                          CostingService costing) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
        this.ledger = ledger;
        this.costing = costing;
    }

    public List<WastageDto> list(LocalDate from, LocalDate to, String reason, boolean includeVoided, int limit) {
        boolean show = CostingService.canSeeCosts();
        return jdbc.sql(baseSelect() + """
                where w.business_id = :b and (:inc or w.void_state = 'ACTIVE')
                  and (cast(:f as date) is null or w.txn_date >= :f)
                  and (cast(:t as date) is null or w.txn_date <= :t)
                  and (cast(:r as text) is null or w.reason = :r)
                order by w.txn_date desc, w.id desc limit :lim
                """).param("b", props.businessId()).param("inc", includeVoided).param("f", from).param("t", to)
                .param("r", reason == null || reason.isBlank() ? null : reason)
                .param("lim", Math.min(Math.max(limit, 1), 500)).query(WastageRow.class).list()
                .stream().map(r -> toDto(r, show)).toList();
    }

    private String baseSelect() {
        return """
                select w.id, w.txn_date, w.entry_at, w.ingredient_id, i.name as ingredient_name, i.base_unit, w.product_id,
                       w.variant_id, p.name as product_name, v.name as variant_name, w.quantity, w.reason, w.unit_cost,
                       w.cost_total, w.cost_status, w.cost_estimated, w.notes, w.void_state, w.void_reason
                from wastage_entries w
                left join ingredients i on i.id = w.ingredient_id
                left join products p on p.id = w.product_id
                left join product_variants v on v.id = w.variant_id
                """;
    }

    private WastageDto toDto(WastageRow r, boolean showCost) {
        List<String> flags = new ArrayList<>();
        if (!"COMPLETE".equals(r.costStatus())) flags.add(r.costStatus());
        if (r.costEstimated()) flags.add("ESTIMATED");
        if ("VOIDED".equals(r.voidState())) flags.add("VOIDED");
        return new WastageDto(r.id(), r.txnDate(), r.entryAt(), r.ingredientId(), r.ingredientName(), r.baseUnit(),
                r.productId(), r.variantId(), r.productName(), r.variantName(), r.quantity(), r.reason(),
                showCost ? r.unitCost() : null, showCost ? r.costTotal() : null, r.costStatus(), r.costEstimated(),
                r.notes(), r.voidState(), r.voidReason(), flags);
    }

    @Transactional
    public WastageDto create(WastageRequest r) {
        CurrentUser.require("wastage.create");
        if (r.txnDate() == null) throw ApiException.badRequest("Date is required");
        if (r.txnDate().isAfter(clock.today())) throw ApiException.badRequest("Date cannot be in the future");
        if (r.txnDate().isBefore(clock.dataStartDate())) {
            throw ApiException.badRequest("Date is before the business data-start date " + clock.dataStartDate());
        }
        if ((r.ingredientId() == null) == (r.variantId() == null)) {
            throw ApiException.badRequest("Choose either an ingredient or a product, not both");
        }
        if (r.reason() == null || !REASONS.contains(r.reason())) {
            throw ApiException.badRequest("Reason must be one of " + String.join(", ", new java.util.TreeSet<>(REASONS)));
        }
        if (r.quantity() == null || r.quantity().signum() <= 0) throw ApiException.badRequest("Quantity must be more than zero");

        CostingService.CostBook book = costing.loadBook();
        CostResolver res = book.resolver();
        BigDecimal qty = r.quantity();
        BigDecimal unitCost = null;
        BigDecimal total = null;
        String costStatus = "MISSING";
        boolean est = false;
        Long productId = null;
        List<CostResolver.Line> recipeLines = null;
        BigDecimal outputQty = BigDecimal.ONE;

        if (r.ingredientId() != null) {
            String baseUnit = jdbc.sql("select base_unit from ingredients where id = :id and business_id = :b")
                    .param("id", r.ingredientId()).param("b", props.businessId()).query(String.class).optional()
                    .orElseThrow(() -> ApiException.notFound("Ingredient"));
            try {
                qty = UnitConverter.toIngredientBase(r.quantity(), r.unit(), baseUnit);
            } catch (UnitConverter.UnitConversionException e) {
                throw ApiException.badRequest(e.getMessage());
            }
            CostResolver.Result c = CostingService.unit(res, r.ingredientId(), r.txnDate());
            if (c.cost() != null) {
                unitCost = c.cost();
                total = CostingService.money(c.cost().multiply(qty));
                costStatus = c.status();
                est = c.estimated();
            }
        } else {
            VariantInfo vi = jdbc.sql("""
                    select v.id as variant_id, v.product_id from product_variants v join products p on p.id = v.product_id
                    where v.id = :id and p.business_id = :b
                    """).param("id", r.variantId()).param("b", props.businessId()).query(VariantInfo.class).optional()
                    .orElseThrow(() -> ApiException.notFound("Product variant"));
            productId = vi.productId();
            CostResolver.Result c = CostingService.portion(res, r.variantId(), r.txnDate());
            if (c.cost() != null) {
                unitCost = c.cost();
                total = CostingService.money(c.cost().multiply(qty));
                costStatus = c.status();
                est = c.estimated();
            }
            CostResolver.Version v = book.recipeForVariant(r.variantId(), r.txnDate());
            if (v != null) {
                recipeLines = v.lines();
                outputQty = v.outputQty();
            }
        }
        long id = jdbc.sql("""
                insert into wastage_entries (business_id, txn_date, ingredient_id, product_id, variant_id, quantity, reason,
                                             unit_cost, cost_total, cost_status, cost_estimated, notes, created_by)
                values (:b, :d, :ing, :prod, :var, :q, :r, :uc, :ct, :cs, :ce, :n, :u) returning id
                """).param("b", props.businessId()).param("d", r.txnDate()).param("ing", r.ingredientId())
                .param("prod", productId).param("var", r.variantId()).param("q", qty).param("r", r.reason())
                .param("uc", unitCost).param("ct", total).param("cs", costStatus).param("ce", est)
                .param("n", r.notes() == null || r.notes().isBlank() ? null : r.notes().trim())
                .param("u", CurrentUser.id()).query(Long.class).single();

        if (r.ingredientId() != null) {
            ledger.post(r.ingredientId(), "WASTAGE", qty.negate(), r.txnDate(), "Wastage: " + r.reason(), "WASTAGE_ENTRY", id, "WASTAGE");
        } else if (recipeLines != null) {
            // a wasted finished product used up its recipe ingredients (direct lines)
            for (CostResolver.Line l : recipeLines) {
                BigDecimal q = CostResolver.grossQty(l).multiply(qty).divide(outputQty, 4, RoundingMode.HALF_UP);
                if (q.signum() > 0) {
                    ledger.post(l.ingredientId(), "WASTAGE", q.negate(), r.txnDate(), "Wasted product: " + r.reason(),
                            "WASTAGE_ENTRY", id, "WASTAGE");
                }
            }
        }
        audit.record("WASTAGE", id, "CREATE", r.txnDate(), null, snapshot(id), r.notes(), "MANUAL");
        return one(id);
    }

    @Transactional
    public WastageDto voidEntry(long id, String reason) {
        CurrentUser.require("wastage.void");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a wastage entry");
        WastageRow cur = jdbc.sql(baseSelect() + " where w.id = :id and w.business_id = :b").param("id", id)
                .param("b", props.businessId()).query(WastageRow.class).optional().orElseThrow(() -> ApiException.notFound("Wastage entry"));
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("Already voided");
        String before = snapshot(id);
        ledger.reverseAllFor("WASTAGE_ENTRY", id, reason, "WASTAGE");
        jdbc.sql("update wastage_entries set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now() where id = :id")
                .param("r", reason).param("u", CurrentUser.id()).param("id", id).update();
        audit.record("WASTAGE", id, "VOID", cur.txnDate(), before, snapshot(id), reason, "MANUAL");
        return one(id);
    }

    public Summary summary(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("Choose a valid date range");
        boolean show = CostingService.canSeeCosts();
        List<ReasonSummary> byReason = jdbc.sql("""
                select reason, count(*) as entries, coalesce(sum(cost_total), 0) as cost_known,
                       count(*) filter (where cost_status <> 'COMPLETE') as entries_without_cost
                from wastage_entries where business_id = :b and void_state = 'ACTIVE' and txn_date between :f and :t
                group by reason order by coalesce(sum(cost_total), 0) desc, count(*) desc
                """).param("b", props.businessId()).param("f", from).param("t", to).query(ReasonSummary.class).list();
        List<PeriodSummary> byMonth = jdbc.sql("""
                select to_char(txn_date, 'YYYY-MM') as period, count(*) as entries, coalesce(sum(cost_total), 0) as cost_known,
                       count(*) filter (where cost_status <> 'COMPLETE') as entries_without_cost
                from wastage_entries where business_id = :b and void_state = 'ACTIVE' and txn_date between :f and :t
                group by 1 order by 1
                """).param("b", props.businessId()).param("f", from).param("t", to).query(PeriodSummary.class).list();
        BigDecimal total = BigDecimal.ZERO;
        long n = 0;
        long noCost = 0;
        List<ReasonSummary> outReason = new ArrayList<>();
        for (ReasonSummary r : byReason) {
            total = total.add(r.costKnown());
            n += r.entries();
            noCost += r.entriesWithoutCost();
            outReason.add(show ? r : new ReasonSummary(r.reason(), r.entries(), null, r.entriesWithoutCost()));
        }
        List<PeriodSummary> outMonth = new ArrayList<>();
        for (PeriodSummary p : byMonth) {
            outMonth.add(show ? p : new PeriodSummary(p.period(), p.entries(), null, p.entriesWithoutCost()));
        }
        List<String> flags = new ArrayList<>();
        if (show && noCost > 0) flags.add("PARTIAL");
        return new Summary(from, to, show ? CostingService.money(total) : null, n, noCost, !show, outReason, outMonth, flags);
    }

    private WastageDto one(long id) {
        WastageRow r = jdbc.sql(baseSelect() + " where w.id = :id").param("id", id).query(WastageRow.class).single();
        return toDto(r, CostingService.canSeeCosts());
    }

    private String snapshot(long id) {
        return jdbc.sql("select to_jsonb(w)::text from wastage_entries w where w.id = :id").param("id", id)
                .query(String.class).single();
    }
}
