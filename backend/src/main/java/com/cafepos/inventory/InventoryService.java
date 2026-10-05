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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stock = recorded movements (ledger) anchored to the last POSTED count, minus THEORETICAL sales usage (recipes x itemised
 * sales). Sales usage is computed on the fly, never stored, so a corrected sale or recipe changes the numbers honestly.
 * Quick-entry days have no product detail: they cannot contribute usage and are reported as a gap, never guessed.
 */
@Service
public class InventoryService {

    // ------------------------------------------------------------ shared SQL (theoretical usage)
    private static final String USAGE_JOINS = """
            from sale_items si
            join sales s on s.id = si.sale_id and s.void_state = 'ACTIVE' and s.business_id = :b
            join v_daily_sales v on v.business_id = s.business_id and v.txn_date = s.txn_date and v.chosen = 'ITEMISED'
            join recipes r on r.variant_id = si.variant_id and r.business_id = :b
            join recipe_versions rv on rv.recipe_id = r.id
                 and rv.effective_from = (select max(x.effective_from) from recipe_versions x
                                          where x.recipe_id = r.id and x.effective_from <= s.txn_date)
            join recipe_lines l on l.version_id = rv.id
            """;
    private static final String USAGE_QTY = "si.quantity * l.quantity_base * 100 / (100 - l.waste_pct) / rv.output_qty";

    // ------------------------------------------------------------ DTOs
    public record StockRow(long ingredientId, String name, String kind, String baseUnit, String category,
                           BigDecimal minLevel, boolean active, BigDecimal ledgerBalance, BigDecimal lastCountQty,
                           LocalDate lastCountDate, BigDecimal movementsSinceBaseline, BigDecimal theoreticalUsage,
                           BigDecimal expectedStock, String baseline, LocalDate baselineStart) {}

    public record StockDto(long ingredientId, String name, String kind, String baseUnit, String category,
                           BigDecimal minLevel, boolean active, BigDecimal ledgerBalance, BigDecimal lastCountQty,
                           LocalDate lastCountDate, BigDecimal movementsSinceBaseline, BigDecimal theoreticalUsage,
                           BigDecimal expectedStock, String baseline, boolean lowStock, List<String> flags) {}

    public record UsageGaps(LocalDate from, LocalDate to, int summaryOnlyDays, BigDecimal unrecipedNetSales,
                            BigDecimal itemisedNetSales) {}

    public record StockReport(LocalDate asOf, List<StockDto> rows, UsageGaps gaps, List<String> notes) {}

    public record UsageRow(long ingredientId, String name, String baseUnit, BigDecimal theoreticalUsage,
                           BigDecimal wastageRecorded) {}

    public record UsageReport(LocalDate from, LocalDate to, List<UsageRow> rows, UsageGaps gaps, List<String> notes) {}

    public record CountLineInput(Long ingredientId, BigDecimal countedQty, String unit, String note) {}

    public record CountRequest(LocalDate countDate, String notes, List<CountLineInput> lines) {}

    public record CountHeader(long id, LocalDate countDate, String status, String notes, OffsetDateTime createdAt,
                              OffsetDateTime postedAt, String voidReason, int lineCount) {}

    public record CountLineRow(long id, long ingredientId, String ingredientName, String baseUnit, BigDecimal countedQty,
                               BigDecimal expectedQty, BigDecimal variance, BigDecimal unitCost, String costStatus,
                               boolean costEstimated, String note) {}

    public record CountLineDto(long id, long ingredientId, String ingredientName, String baseUnit, BigDecimal countedQty,
                               BigDecimal expectedQty, BigDecimal variance, BigDecimal unitCost, String costStatus,
                               boolean costEstimated, BigDecimal varianceValue, String note, boolean live) {}

    public record CountDetail(CountHeader header, List<CountLineDto> lines, List<String> flags) {}

    public record VarianceRow(long countId, LocalDate countDate, long ingredientId, String ingredientName, String baseUnit,
                              BigDecimal countedQty, BigDecimal expectedQty, BigDecimal variance, BigDecimal unitCost,
                              String costStatus, boolean costEstimated) {}

    public record VarianceLine(long countId, LocalDate countDate, long ingredientId, String ingredientName, String baseUnit,
                               BigDecimal countedQty, BigDecimal expectedQty, BigDecimal variance, BigDecimal unitCost,
                               BigDecimal varianceValue, String costStatus, boolean costEstimated) {}

    public record VarianceReport(LocalDate from, LocalDate to, List<VarianceLine> rows, BigDecimal totalValueKnown,
                                 int linesWithoutCost, boolean valuesHidden, List<String> flags) {}

    public record PrepRequest(Long ingredientId, LocalDate txnDate, BigDecimal plannedQty, BigDecimal actualQty,
                              String unit, String notes) {}

    public record PrepRow(long id, long ingredientId, String ingredientName, String baseUnit, LocalDate txnDate,
                          BigDecimal plannedQty, BigDecimal actualQty, BigDecimal costTotal, BigDecimal costPerUnit,
                          String costStatus, boolean costEstimated, String notes, String voidState, String voidReason,
                          OffsetDateTime entryAt) {}

    public record PrepDto(long id, long ingredientId, String ingredientName, String baseUnit, LocalDate txnDate,
                          BigDecimal plannedQty, BigDecimal actualQty, BigDecimal yieldPct, BigDecimal costTotal,
                          BigDecimal costPerUnit, String costStatus, boolean costEstimated, String notes, String voidState,
                          String voidReason, OffsetDateTime entryAt) {}

    public record Ing(long id, String name, String kind, String baseUnit, boolean active) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;
    private final LedgerService ledger;
    private final CostingService costing;

    public InventoryService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit,
                            LedgerService ledger, CostingService costing) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
        this.ledger = ledger;
        this.costing = costing;
    }

    // ------------------------------------------------------------ stock report

    public StockReport stock(LocalDate asOf, boolean includeInactive) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        List<StockRow> rows = stockRows(d, includeInactive);
        LocalDate minStart = null;
        for (StockRow r : rows) {
            if (r.baselineStart() != null && (minStart == null || r.baselineStart().isBefore(minStart))) minStart = r.baselineStart();
        }
        UsageGaps gaps = minStart == null || minStart.isAfter(d) ? null : gaps(minStart, d);
        List<StockDto> out = new ArrayList<>();
        for (StockRow r : rows) {
            List<String> flags = new ArrayList<>();
            if ("NONE".equals(r.baseline())) flags.add("NO_BASELINE");
            if ("MOVEMENTS".equals(r.baseline())) flags.add("NO_COUNT");
            if (r.expectedStock() != null && r.expectedStock().signum() < 0) flags.add("NEGATIVE_STOCK");
            boolean low = r.minLevel() != null && r.expectedStock() != null && r.expectedStock().compareTo(r.minLevel()) <= 0;
            out.add(new StockDto(r.ingredientId(), r.name(), r.kind(), r.baseUnit(), r.category(), r.minLevel(), r.active(),
                    r.ledgerBalance(), r.lastCountQty(), r.lastCountDate(), r.movementsSinceBaseline(), r.theoreticalUsage(),
                    r.expectedStock(), r.baseline(), low, flags));
        }
        List<String> notes = new ArrayList<>();
        notes.add("Expected stock = last posted count + movements since the count - theoretical sales usage (recipes x itemised sales). "
                + "'Recorded movements balance' is the sum of ledger movements only and excludes sales usage.");
        if (gaps != null && gaps.summaryOnlyDays() > 0) {
            notes.add(gaps.summaryOnlyDays() + " day(s) since the baselines have only a quick daily total: their usage is unknown and NOT deducted, so expected stock may be overstated.");
        }
        if (gaps != null && gaps.unrecipedNetSales().signum() > 0) {
            notes.add("Itemised sales of products without a recipe (net " + gaps.unrecipedNetSales() + ") are not deducted from stock.");
        }
        if (out.stream().anyMatch(x -> x.flags().contains("NO_BASELINE"))) {
            notes.add("Ingredients with NO BASELINE have no movement or count yet: their stock is unknown, not zero.");
        }
        return new StockReport(d, out, gaps, notes);
    }

    public List<StockDto> lowStock(LocalDate asOf) {
        return stock(asOf, false).rows().stream().filter(StockDto::lowStock).toList();
    }

    List<StockRow> stockRows(LocalDate asOf, boolean includeInactive) {
        String sql = """
                with last_count as (
                  select distinct on (l.ingredient_id) l.ingredient_id, c.count_date, l.counted_qty
                  from stock_count_lines l join stock_counts c on c.id = l.count_id
                  where c.business_id = :b and c.status = 'POSTED' and c.count_date <= :asof
                  order by l.ingredient_id, c.count_date desc, c.id desc
                ), first_mv as (
                  select ingredient_id, min(txn_date) as d from inventory_movements
                  where business_id = :b and txn_date <= :asof group by ingredient_id
                ), base as (
                  select i.id as ingredient_id, lc.count_date, lc.counted_qty,
                         case when lc.ingredient_id is not null then lc.count_date + 1 else fm.d end as start_date,
                         case when lc.ingredient_id is not null then 'COUNT'
                              when fm.d is not null then 'MOVEMENTS' else 'NONE' end as baseline
                  from ingredients i
                  left join last_count lc on lc.ingredient_id = i.id
                  left join first_mv fm on fm.ingredient_id = i.id
                  where i.business_id = :b and (:inc or i.active)
                ), led as (
                  select ingredient_id, sum(quantity_base) as total from inventory_movements
                  where business_id = :b and txn_date <= :asof group by ingredient_id
                ), mv as (
                  select m.ingredient_id, sum(m.quantity_base) as qty
                  from inventory_movements m join base b on b.ingredient_id = m.ingredient_id
                  where m.business_id = :b and b.start_date is not null and m.txn_date >= b.start_date and m.txn_date <= :asof
                  group by m.ingredient_id
                ), usage_cte as (
                  select l.ingredient_id, sum(""" + USAGE_QTY + """
                ) as qty
                """ + USAGE_JOINS + """
                  join base b on b.ingredient_id = l.ingredient_id
                  where b.start_date is not null and s.txn_date >= b.start_date and s.txn_date <= :asof
                  group by l.ingredient_id
                )
                select i.id as ingredient_id, i.name, i.kind, i.base_unit, i.category, i.min_level, i.active,
                       coalesce(led.total, 0) as ledger_balance, b.counted_qty as last_count_qty, b.count_date as last_count_date,
                       mv.qty as movements_since_baseline, round(u.qty, 4) as theoretical_usage,
                       case when b.baseline = 'NONE' then null
                            else round(coalesce(b.counted_qty, 0) + coalesce(mv.qty, 0) - coalesce(u.qty, 0), 4) end as expected_stock,
                       b.baseline, b.start_date as baseline_start
                from ingredients i
                join base b on b.ingredient_id = i.id
                left join led on led.ingredient_id = i.id
                left join mv on mv.ingredient_id = i.id
                left join usage_cte u on u.ingredient_id = i.id
                order by lower(i.name)
                """;
        return jdbc.sql(sql).param("b", props.businessId()).param("asof", asOf).param("inc", includeInactive)
                .query(StockRow.class).list();
    }

    /** Where theoretical usage is blind: quick-entry days and itemised products without a recipe. */
    public UsageGaps gaps(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select cast(:f as date) as from_date, cast(:t as date) as to_date,
                       (select count(*) from v_daily_sales where business_id = :b and chosen = 'SUMMARY'
                          and txn_date between :f and :t) as summary_only_days,
                       coalesce(sum(i.net_taxable) filter (where rv.id is null), 0) as unrecipied_net_sales,
                       coalesce(sum(i.net_taxable), 0) as itemised_net_sales
                from sale_items i
                join sales s on s.id = i.sale_id and s.void_state = 'ACTIVE' and s.business_id = :b
                join v_daily_sales v on v.business_id = s.business_id and v.txn_date = s.txn_date and v.chosen = 'ITEMISED'
                left join recipes r on r.variant_id = i.variant_id and r.business_id = :b
                left join recipe_versions rv on rv.recipe_id = r.id
                     and rv.effective_from = (select max(x.effective_from) from recipe_versions x
                                              where x.recipe_id = r.id and x.effective_from <= s.txn_date)
                where s.txn_date between :f and :t
                """).param("b", props.businessId()).param("f", from).param("t", to).query(GapsRow.class).single().toGaps();
    }

    public record GapsRow(LocalDate fromDate, LocalDate toDate, int summaryOnlyDays, BigDecimal unrecipiedNetSales,
                          BigDecimal itemisedNetSales) {
        UsageGaps toGaps() {
            return new UsageGaps(fromDate, toDate, summaryOnlyDays, unrecipiedNetSales, itemisedNetSales);
        }
    }

    /** Theoretical usage of ONE ingredient between two dates (inclusive), for container over-serving checks. */
    public BigDecimal theoreticalUsage(long ingredientId, LocalDate from, LocalDate to) {
        BigDecimal q = jdbc.sql("select coalesce(sum(" + USAGE_QTY + "), 0) " + USAGE_JOINS
                        + " where l.ingredient_id = :ing and s.txn_date between :f and :t")
                .param("b", props.businessId()).param("ing", ingredientId).param("f", from).param("t", to)
                .query(BigDecimal.class).single();
        return q.setScale(4, RoundingMode.HALF_UP);
    }

    public UsageReport usage(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("Choose a valid date range");
        List<UsageRow> rows = jdbc.sql("""
                select i.id as ingredient_id, i.name, i.base_unit, round(u.qty, 4) as theoretical_usage,
                       w.qty as wastage_recorded
                from ingredients i
                left join (select l.ingredient_id, sum(""" + USAGE_QTY + """
                ) as qty
                """ + USAGE_JOINS + """
                  where s.txn_date between :f and :t group by l.ingredient_id) u on u.ingredient_id = i.id
                left join (select ingredient_id, -sum(quantity_base) as qty from inventory_movements
                           where business_id = :b and ref_type = 'WASTAGE_ENTRY' and txn_date between :f and :t
                           group by ingredient_id) w on w.ingredient_id = i.id
                where i.business_id = :b and (u.qty is not null or w.qty is not null)
                order by lower(i.name)
                """).param("b", props.businessId()).param("f", from).param("t", to).query(UsageRow.class).list();
        UsageGaps g = gaps(from, to);
        List<String> notes = new ArrayList<>();
        notes.add("Theoretical usage = recipe quantity (incl. waste %) x itemised quantities sold. It is NOT measured consumption.");
        if (g.summaryOnlyDays() > 0) notes.add(g.summaryOnlyDays() + " day(s) with only a quick daily total are excluded.");
        if (g.unrecipedNetSales().signum() > 0) notes.add("Itemised sales without a recipe (net " + g.unrecipedNetSales() + ") are excluded.");
        return new UsageReport(from, to, rows, g, notes);
    }

    // ------------------------------------------------------------ stock counts

    public List<CountHeader> counts(int limit) {
        CurrentUser.require("inventory.view");
        return jdbc.sql("""
                select c.id, c.count_date, c.status, c.notes, c.created_at, c.posted_at, c.void_reason,
                       (select count(*) from stock_count_lines l where l.count_id = c.id)::int as line_count
                from stock_counts c where c.business_id = :b order by c.count_date desc, c.id desc limit :lim
                """).param("b", props.businessId()).param("lim", Math.min(Math.max(limit, 1), 200))
                .query(CountHeader.class).list();
    }

    public CountDetail count(long id) {
        CurrentUser.require("inventory.view");
        CountHeader h = header(id);
        List<CountLineRow> rows = jdbc.sql("""
                select l.id, l.ingredient_id, i.name as ingredient_name, i.base_unit, l.counted_qty, l.expected_qty, l.variance,
                       l.unit_cost, l.cost_status, l.cost_estimated, l.note
                from stock_count_lines l join ingredients i on i.id = l.ingredient_id
                where l.count_id = :c order by lower(i.name)
                """).param("c", id).query(CountLineRow.class).list();
        boolean showCost = CostingService.canSeeCosts();
        Map<Long, StockRow> live = new HashMap<>();
        boolean isDraft = "DRAFT".equals(h.status());
        if (isDraft) {
            for (StockRow s : stockRows(h.countDate(), true)) live.put(s.ingredientId(), s);
        }
        List<CountLineDto> lines = new ArrayList<>();
        for (CountLineRow r : rows) {
            BigDecimal expected = r.expectedQty();
            BigDecimal variance = r.variance();
            if (isDraft) {
                StockRow s = live.get(r.ingredientId());
                expected = s == null ? null : s.expectedStock();
                variance = expected == null ? null : r.countedQty().subtract(expected);
            }
            BigDecimal value = showCost && variance != null && r.unitCost() != null
                    ? CostingService.money(variance.multiply(r.unitCost())) : null;
            lines.add(new CountLineDto(r.id(), r.ingredientId(), r.ingredientName(), r.baseUnit(), r.countedQty(), expected,
                    variance, showCost ? r.unitCost() : null, r.costStatus(), r.costEstimated(), value, r.note(), isDraft));
        }
        List<String> flags = new ArrayList<>();
        if (lines.stream().anyMatch(l -> l.expectedQty() == null)) flags.add("FIRST_COUNT_FOR_SOME_ITEMS");
        return new CountDetail(h, lines, flags);
    }

    @Transactional
    public CountDetail createCount(CountRequest r) {
        CurrentUser.require("inventory.manage");
        LocalDate date = r.countDate() == null ? clock.today() : r.countDate();
        if (date.isAfter(clock.today())) throw ApiException.badRequest("Count date cannot be in the future");
        long id = jdbc.sql("""
                insert into stock_counts (business_id, count_date, notes, created_by) values (:b, :d, :n, :u) returning id
                """).param("b", props.businessId()).param("d", date).param("n", blank(r.notes())).param("u", CurrentUser.id())
                .query(Long.class).single();
        saveLines(id, r.lines());
        audit.record("STOCK_COUNT", id, "CREATE", date, null, countSnapshot(id), null, "MANUAL");
        return count(id);
    }

    @Transactional
    public CountDetail updateCount(long id, CountRequest r) {
        CurrentUser.require("inventory.manage");
        CountHeader h = header(id);
        if (!"DRAFT".equals(h.status())) throw ApiException.conflict("Only a DRAFT count can be changed. Void a posted count and enter a new one");
        LocalDate date = r.countDate() == null ? h.countDate() : r.countDate();
        if (date.isAfter(clock.today())) throw ApiException.badRequest("Count date cannot be in the future");
        String before = countSnapshot(id);
        jdbc.sql("update stock_counts set count_date = :d, notes = :n where id = :id")
                .param("d", date).param("n", blank(r.notes())).param("id", id).update();
        jdbc.sql("delete from stock_count_lines where count_id = :id").param("id", id).update();
        saveLines(id, r.lines());
        audit.record("STOCK_COUNT", id, "UPDATE", date, before, countSnapshot(id), "Draft edited", "MANUAL");
        return count(id);
    }

    private void saveLines(long countId, List<CountLineInput> lines) {
        if (lines == null) return;
        Set<Long> seen = new HashSet<>();
        for (CountLineInput li : lines) {
            if (li.ingredientId() == null) throw ApiException.badRequest("Choose an ingredient for every count line");
            if (!seen.add(li.ingredientId())) throw ApiException.badRequest("An ingredient can appear only once in a count");
            if (li.countedQty() == null || li.countedQty().signum() < 0) {
                throw ApiException.badRequest("Counted quantity must be zero or more (leave the line out if not counted)");
            }
            String baseUnit = jdbc.sql("select base_unit from ingredients where id = :id and business_id = :b")
                    .param("id", li.ingredientId()).param("b", props.businessId()).query(String.class).optional()
                    .orElseThrow(() -> ApiException.notFound("Ingredient"));
            BigDecimal qty;
            try {
                qty = UnitConverter.toIngredientBase(li.countedQty(), li.unit(), baseUnit);
            } catch (UnitConverter.UnitConversionException e) {
                throw ApiException.badRequest(e.getMessage());
            }
            jdbc.sql("""
                    insert into stock_count_lines (count_id, ingredient_id, counted_qty, note) values (:c, :i, :q, :n)
                    """).param("c", countId).param("i", li.ingredientId()).param("q", qty).param("n", blank(li.note())).update();
        }
    }

    @Transactional
    public CountDetail postCount(long id) {
        CurrentUser.require("inventory.manage");
        CountHeader h = header(id);
        if (!"DRAFT".equals(h.status())) throw ApiException.conflict("This count is already " + h.status().toLowerCase());
        if (h.lineCount() == 0) throw ApiException.badRequest("Add at least one counted ingredient");
        if (h.countDate().isAfter(clock.today())) throw ApiException.badRequest("Count date cannot be in the future");
        LocalDate later = jdbc.sql("select max(count_date) from stock_counts where business_id = :b and status = 'POSTED'")
                .param("b", props.businessId()).query(LocalDate.class).optional().orElse(null);
        if (later != null && later.isAfter(h.countDate())) {
            throw ApiException.conflict("A later stock count (" + later + ") is already posted. Void it or use a later date");
        }
        String before = countSnapshot(id);
        Map<Long, StockRow> stock = new HashMap<>();
        for (StockRow s : stockRows(h.countDate(), true)) stock.put(s.ingredientId(), s);
        CostingService.CostBook book = costing.loadBook();
        CostResolver res = book.resolver();
        List<CountLineRow> lines = jdbc.sql("""
                select l.id, l.ingredient_id, i.name as ingredient_name, i.base_unit, l.counted_qty, l.expected_qty, l.variance,
                       l.unit_cost, l.cost_status, l.cost_estimated, l.note
                from stock_count_lines l join ingredients i on i.id = l.ingredient_id where l.count_id = :c
                """).param("c", id).query(CountLineRow.class).list();
        for (CountLineRow l : lines) {
            StockRow s = stock.get(l.ingredientId());
            BigDecimal expected = s == null ? null : s.expectedStock();
            BigDecimal variance = expected == null ? null : l.countedQty().subtract(expected);
            CostResolver.Result c = CostingService.unit(res, l.ingredientId(), h.countDate());
            jdbc.sql("""
                    update stock_count_lines set expected_qty = :e, variance = :v, unit_cost = :uc, cost_status = :cs,
                           cost_estimated = :ce where id = :id
                    """).param("e", expected).param("v", variance).param("uc", c.cost())
                    .param("cs", c.cost() == null ? "MISSING" : c.status()).param("ce", c.estimated()).param("id", l.id()).update();
            BigDecimal adj = expected == null ? l.countedQty() : variance;
            if (adj.setScale(4, RoundingMode.HALF_UP).signum() != 0) {
                ledger.post(l.ingredientId(), "COUNT_ADJUSTMENT", adj.setScale(4, RoundingMode.HALF_UP), h.countDate(),
                        "Stock count " + h.countDate() + (expected == null ? " (first count, no earlier baseline)" : ""),
                        "STOCK_COUNT", id, "COUNT");
            }
        }
        jdbc.sql("update stock_counts set status = 'POSTED', posted_at = now(), posted_by = :u where id = :id")
                .param("u", CurrentUser.id()).param("id", id).update();
        audit.record("STOCK_COUNT", id, "POST", h.countDate(), before, countSnapshot(id), null, "COUNT");
        return count(id);
    }

    @Transactional
    public CountDetail voidCount(long id, String reason) {
        CurrentUser.require("inventory.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a stock count");
        CountHeader h = header(id);
        if ("VOID".equals(h.status())) throw ApiException.conflict("Already voided");
        String before = countSnapshot(id);
        if ("POSTED".equals(h.status())) ledger.reverseAllFor("STOCK_COUNT", id, reason, "COUNT");
        jdbc.sql("""
                update stock_counts set status = 'VOID', void_reason = :r, voided_at = now(), voided_by = :u where id = :id
                """).param("r", reason).param("u", CurrentUser.id()).param("id", id).update();
        audit.record("STOCK_COUNT", id, "VOID", h.countDate(), before, countSnapshot(id), reason, "COUNT");
        return count(id);
    }

    private CountHeader header(long id) {
        return jdbc.sql("""
                select c.id, c.count_date, c.status, c.notes, c.created_at, c.posted_at, c.void_reason,
                       (select count(*) from stock_count_lines l where l.count_id = c.id)::int as line_count
                from stock_counts c where c.id = :id and c.business_id = :b
                """).param("id", id).param("b", props.businessId()).query(CountHeader.class).optional()
                .orElseThrow(() -> ApiException.notFound("Stock count"));
    }

    private String countSnapshot(long id) {
        return jdbc.sql("""
                select jsonb_build_object('count', to_jsonb(c), 'lines',
                       (select coalesce(jsonb_agg(to_jsonb(l) order by l.id), '[]'::jsonb) from stock_count_lines l where l.count_id = c.id))::text
                from stock_counts c where c.id = :id
                """).param("id", id).query(String.class).single();
    }

    // ------------------------------------------------------------ variance report

    public VarianceReport variance(LocalDate from, LocalDate to) {
        CurrentUser.require("inventory.view");
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("Choose a valid date range");
        List<VarianceRow> rows = jdbc.sql("""
                select c.id as count_id, c.count_date, l.ingredient_id, i.name as ingredient_name, i.base_unit, l.counted_qty,
                       l.expected_qty, l.variance, l.unit_cost, l.cost_status, l.cost_estimated
                from stock_count_lines l
                join stock_counts c on c.id = l.count_id
                join ingredients i on i.id = l.ingredient_id
                where c.business_id = :b and c.status = 'POSTED' and c.count_date between :f and :t
                order by c.count_date desc, lower(i.name)
                """).param("b", props.businessId()).param("f", from).param("t", to).query(VarianceRow.class).list();
        boolean show = CostingService.canSeeCosts();
        BigDecimal total = BigDecimal.ZERO;
        int missing = 0;
        List<VarianceLine> out = new ArrayList<>();
        for (VarianceRow r : rows) {
            BigDecimal value = null;
            if (r.variance() != null) {
                if (r.unitCost() == null) missing++;
                else {
                    value = CostingService.money(r.variance().multiply(r.unitCost()));
                    total = total.add(value);
                }
            }
            out.add(new VarianceLine(r.countId(), r.countDate(), r.ingredientId(), r.ingredientName(), r.baseUnit(),
                    r.countedQty(), r.expectedQty(), r.variance(), show ? r.unitCost() : null, show ? value : null,
                    r.costStatus(), r.costEstimated()));
        }
        List<String> flags = new ArrayList<>();
        if (show && missing > 0) flags.add("PARTIAL");
        if (show && out.stream().anyMatch(VarianceLine::costEstimated)) flags.add("ESTIMATED");
        return new VarianceReport(from, to, out, show ? CostingService.money(total) : null, show ? missing : 0, !show, flags);
    }

    // ------------------------------------------------------------ prep batches

    public List<PrepDto> prepBatches(LocalDate from, LocalDate to, boolean includeVoided) {
        CurrentUser.require("inventory.view");
        List<PrepRow> rows = jdbc.sql(prepSelect() + """
                where p.business_id = :b and (:inc or p.void_state = 'ACTIVE')
                  and (cast(:f as date) is null or p.txn_date >= :f) and (cast(:t as date) is null or p.txn_date <= :t)
                order by p.txn_date desc, p.id desc limit 200
                """).param("b", props.businessId()).param("inc", includeVoided).param("f", from).param("t", to)
                .query(PrepRow.class).list();
        boolean show = CostingService.canSeeCosts();
        return rows.stream().map(r -> prepDto(r, show)).toList();
    }

    private String prepSelect() {
        return """
                select p.id, p.ingredient_id, i.name as ingredient_name, i.base_unit, p.txn_date, p.planned_qty, p.actual_qty,
                       p.cost_total, p.cost_per_unit, p.cost_status, p.cost_estimated, p.notes, p.void_state, p.void_reason,
                       p.entry_at
                from prep_batches p join ingredients i on i.id = p.ingredient_id
                """;
    }

    private PrepDto prepDto(PrepRow r, boolean showCost) {
        BigDecimal yield = r.plannedQty().signum() > 0
                ? r.actualQty().multiply(new BigDecimal("100")).divide(r.plannedQty(), 1, RoundingMode.HALF_UP) : null;
        return new PrepDto(r.id(), r.ingredientId(), r.ingredientName(), r.baseUnit(), r.txnDate(), r.plannedQty(),
                r.actualQty(), yield, showCost ? r.costTotal() : null, showCost ? r.costPerUnit() : null,
                showCost ? r.costStatus() : null, r.costEstimated(), r.notes(), r.voidState(), r.voidReason(), r.entryAt());
    }

    /** Records a batch of a PREPARED ingredient: consumes raw ingredients per its recipe, produces the ACTUAL yield. */
    @Transactional
    public PrepDto createPrep(PrepRequest r) {
        CurrentUser.require("inventory.manage");
        if (r.ingredientId() == null) throw ApiException.badRequest("Choose the prepared ingredient");
        if (r.txnDate() == null) throw ApiException.badRequest("Date is required");
        Ing ing = jdbc.sql("select id, name, kind, base_unit, active from ingredients where id = :id and business_id = :b")
                .param("id", r.ingredientId()).param("b", props.businessId()).query(Ing.class).optional()
                .orElseThrow(() -> ApiException.notFound("Ingredient"));
        if (!"PREPARED".equals(ing.kind())) throw ApiException.badRequest(ing.name() + " is not a PREPARED ingredient");
        if (r.actualQty() == null || r.actualQty().signum() <= 0) throw ApiException.badRequest("Enter the quantity actually produced");
        BigDecimal actual = toBase(r.actualQty(), r.unit(), ing.baseUnit());
        BigDecimal planned = r.plannedQty() == null ? actual : toBase(r.plannedQty(), r.unit(), ing.baseUnit());
        if (planned.signum() <= 0) throw ApiException.badRequest("Planned quantity must be more than zero");

        CostingService.CostBook book = costing.loadBook();
        CostResolver.Version v = book.recipeForIngredient(ing.id(), r.txnDate());
        Long versionId = book.versionIdForIngredient(ing.id(), r.txnDate());
        if (v == null || versionId == null) {
            throw ApiException.conflict("No recipe is in force for " + ing.name() + " on " + r.txnDate() + ". Add a recipe version first");
        }
        BigDecimal scale = planned.divide(v.outputQty(), 8, RoundingMode.HALF_UP);
        CostResolver.Result c = CostingService.unit(book.resolver(), ing.id(), r.txnDate());
        BigDecimal total = c.cost() == null ? null : CostingService.money(c.cost().multiply(planned));
        BigDecimal perUnit = total == null ? null : total.divide(actual, 6, RoundingMode.HALF_UP);
        long id = jdbc.sql("""
                insert into prep_batches (business_id, ingredient_id, txn_date, planned_qty, actual_qty, recipe_version_id,
                                          cost_total, cost_per_unit, cost_status, cost_estimated, notes, created_by)
                values (:b, :i, :d, :p, :a, :rv, :ct, :cu, :cs, :ce, :n, :u) returning id
                """).param("b", props.businessId()).param("i", ing.id()).param("d", r.txnDate()).param("p", planned)
                .param("a", actual).param("rv", versionId).param("ct", total).param("cu", perUnit)
                .param("cs", c.cost() == null ? "MISSING" : c.status()).param("ce", c.estimated())
                .param("n", blank(r.notes())).param("u", CurrentUser.id()).query(Long.class).single();
        for (CostResolver.Line l : v.lines()) {
            BigDecimal q = CostResolver.grossQty(l).multiply(scale).setScale(4, RoundingMode.HALF_UP);
            if (q.signum() > 0) {
                ledger.post(l.ingredientId(), "PREP_CONSUME", q.negate(), r.txnDate(), "Used in " + ing.name() + " batch",
                        "PREP_BATCH", id, "PREP");
            }
        }
        ledger.post(ing.id(), "PREP_PRODUCE", actual.setScale(4, RoundingMode.HALF_UP), r.txnDate(), "Batch produced",
                "PREP_BATCH", id, "PREP");
        audit.record("PREP_BATCH", id, "CREATE", r.txnDate(), null, prepSnapshot(id), null, "MANUAL");
        return prepOne(id);
    }

    @Transactional
    public PrepDto voidPrep(long id, String reason) {
        CurrentUser.require("inventory.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a prep batch");
        PrepRow cur = jdbc.sql(prepSelect() + " where p.id = :id and p.business_id = :b").param("id", id)
                .param("b", props.businessId()).query(PrepRow.class).optional().orElseThrow(() -> ApiException.notFound("Prep batch"));
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("Already voided");
        String before = prepSnapshot(id);
        ledger.reverseAllFor("PREP_BATCH", id, reason, "PREP");
        jdbc.sql("update prep_batches set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now() where id = :id")
                .param("r", reason).param("u", CurrentUser.id()).param("id", id).update();
        audit.record("PREP_BATCH", id, "VOID", cur.txnDate(), before, prepSnapshot(id), reason, "MANUAL");
        return prepOne(id);
    }

    private PrepDto prepOne(long id) {
        PrepRow r = jdbc.sql(prepSelect() + " where p.id = :id").param("id", id).query(PrepRow.class).single();
        return prepDto(r, CostingService.canSeeCosts());
    }

    private String prepSnapshot(long id) {
        return jdbc.sql("select to_jsonb(p)::text from prep_batches p where p.id = :id").param("id", id).query(String.class).single();
    }

    // ------------------------------------------------------------ helpers

    private static BigDecimal toBase(BigDecimal qty, String unit, String base) {
        try {
            return UnitConverter.toIngredientBase(qty, unit, base);
        } catch (UnitConverter.UnitConversionException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
