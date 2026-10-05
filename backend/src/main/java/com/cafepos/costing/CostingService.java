package com.cafepos.costing;

import com.cafepos.platform.ApiException;
import com.cafepos.platform.CafeProps;
import com.cafepos.reporting.ReportService;
import com.cafepos.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cost resolution (as of a date) and theoretical COGS computed on the fly from ITEMISED sales.
 *  - quick-entry (summary) days contribute NO COGS and are listed as such;
 *  - a product without a recipe is NO_RECIPE, one with a missing ingredient cost is PARTIAL - never zero-filled;
 *  - coverage = share of itemised net sales whose recipe AND ingredient costs are complete.
 */
@Service
public class CostingService {

    // ------------------------------------------------------------ raw rows (public: mapped by JdbcClient)
    public record IngRow(long id, String name, String kind, String baseUnit) {}

    public record VersionRow(Long variantId, Long ingredientId, long versionId, LocalDate effectiveFrom, BigDecimal outputQty) {}

    public record LineRow(long versionId, long ingredientId, BigDecimal quantityBase, BigDecimal wastePct) {}

    public record CostRow(long id, long ingredientId, LocalDate effectiveDate, BigDecimal costPerBase, String source) {}

    public record SaleAgg(long productId, long variantId, String productName, String variantName, LocalDate txnDate,
                          BigDecimal quantity, BigDecimal revenueInclusive, BigDecimal netExGst) {}

    public record SummaryDay(LocalDate txnDate, BigDecimal totalInclusive, BigDecimal netTaxable) {}

    // ------------------------------------------------------------ DTOs
    public record IngredientCost(long ingredientId, String name, String kind, String baseUnit, BigDecimal costPerBase,
                                 String status, boolean estimated, LocalDate effectiveDate, String source,
                                 List<String> missing) {}

    public record ProductMargin(long productId, long variantId, String productName, String variantName,
                                BigDecimal quantity, BigDecimal netSales, BigDecimal cogs, BigDecimal grossProfit,
                                BigDecimal marginPct, String status, boolean estimated, List<String> missingIngredients) {}

    public record Coverage(BigDecimal itemisedNetSales, BigDecimal completeNetSales, BigDecimal partialNetSales,
                           BigDecimal noRecipeNetSales, BigDecimal coveragePct, int itemisedDays, int summaryOnlyDays,
                           BigDecimal summaryOnlyNetSales, BigDecimal summaryOnlyGross, List<LocalDate> summaryOnlyDates) {}

    public record ProductMarginReport(LocalDate from, LocalDate to, Coverage coverage, List<String> flags,
                                      List<ProductMargin> rows) {}

    public record GrossProfit(LocalDate from, LocalDate to, Coverage coverage, BigDecimal itemisedNetSales,
                              BigDecimal cogsKnown, BigDecimal grossProfit, BigDecimal marginPct,
                              BigDecimal grossProfitCompleteLines, BigDecimal completeLinesNetSales,
                              BigDecimal completeLinesCogs, List<String> flags, List<String> notes,
                              List<String> missingIngredients) {}

    public record RealPnl(LocalDate from, LocalDate to, ReportService.Pnl pnl, GrossProfit costing,
                          BigDecimal wastageCost, long wastageEntriesWithoutCost, BigDecimal netOperatingProfit,
                          List<String> flags, List<String> notes) {}

    // ------------------------------------------------------------ cost book
    private record VerRec(LocalDate from, CostResolver.Version version, long versionId) {}

    /** All recipes and costs held in memory (small café data set) so as-of resolution is plain Java. */
    public static final class CostBook implements CostResolver.Source {
        private final Map<Long, IngRow> ingredients = new HashMap<>();
        private final Map<Long, List<VerRec>> byVariant = new HashMap<>();
        private final Map<Long, List<VerRec>> byIngredient = new HashMap<>();
        private final Map<Long, List<CostRow>> costs = new HashMap<>();

        private static VerRec latest(List<VerRec> list, LocalDate date) {
            VerRec best = null;
            if (list == null) return null;
            for (VerRec v : list) {
                if (!v.from().isAfter(date)) best = v;
            }
            return best;
        }

        @Override
        public CostResolver.Version recipeForVariant(long variantId, LocalDate date) {
            VerRec v = latest(byVariant.get(variantId), date);
            return v == null ? null : v.version();
        }

        @Override
        public CostResolver.Version recipeForIngredient(long ingredientId, LocalDate date) {
            VerRec v = latest(byIngredient.get(ingredientId), date);
            return v == null ? null : v.version();
        }

        /** Version id in force (for snapshots), or null. */
        public Long versionIdForIngredient(long ingredientId, LocalDate date) {
            VerRec v = latest(byIngredient.get(ingredientId), date);
            return v == null ? null : v.versionId();
        }

        public CostRow directRow(long ingredientId, LocalDate date) {
            List<CostRow> list = costs.get(ingredientId);
            if (list == null) return null;
            CostRow best = null;
            for (CostRow c : list) {
                if (!c.effectiveDate().isAfter(date)) best = c;
            }
            return best;
        }

        @Override
        public CostResolver.UnitCost directCost(long ingredientId, LocalDate date) {
            CostRow c = directRow(ingredientId, date);
            return c == null ? null : new CostResolver.UnitCost(c.costPerBase(), "ESTIMATE".equals(c.source()));
        }

        @Override
        public String ingredientName(long ingredientId) {
            IngRow i = ingredients.get(ingredientId);
            return i == null ? "ingredient #" + ingredientId : i.name();
        }

        @Override
        public boolean isPrepared(long ingredientId) {
            IngRow i = ingredients.get(ingredientId);
            return i != null && "PREPARED".equals(i.kind());
        }

        public Map<Long, IngRow> ingredients() {
            return ingredients;
        }

        /** Ingredient ids used by the CURRENT (latest) recipe of a prepared ingredient: for cycle checks. */
        public Set<Long> currentChildren(long ingredientId) {
            List<VerRec> list = byIngredient.get(ingredientId);
            Set<Long> out = new LinkedHashSet<>();
            if (list == null || list.isEmpty()) return out;
            for (CostResolver.Line l : list.get(list.size() - 1).version().lines()) out.add(l.ingredientId());
            return out;
        }

        public CostResolver resolver() {
            return new CostResolver(this);
        }
    }

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final ReportService reports;
    private final com.cafepos.platform.BusinessClock clock;

    public CostingService(JdbcClient jdbc, CafeProps props, ReportService reports, com.cafepos.platform.BusinessClock clock) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.props = props;
        this.reports = reports;
    }

    // ------------------------------------------------------------ permissions

    /** Cost and margin figures need BOTH finance.view_profit and costing.view. */
    public static boolean canSeeCosts() {
        return CurrentUser.has("costing.view") && CurrentUser.has("finance.view_profit");
    }

    public static void requireCosts() {
        CurrentUser.require("finance.view_profit");
        CurrentUser.require("costing.view");
    }

    // ------------------------------------------------------------ book loading

    public CostBook loadBook() {
        CostBook book = new CostBook();
        long b = props.businessId();
        for (IngRow i : jdbc.sql("select id, name, kind, base_unit from ingredients where business_id = :b")
                .param("b", b).query(IngRow.class).list()) {
            book.ingredients.put(i.id(), i);
        }
        Map<Long, List<CostResolver.Line>> lines = new HashMap<>();
        for (LineRow l : jdbc.sql("""
                select l.version_id, l.ingredient_id, l.quantity_base, l.waste_pct
                from recipe_lines l join recipe_versions v on v.id = l.version_id join recipes r on r.id = v.recipe_id
                where r.business_id = :b order by l.id
                """).param("b", b).query(LineRow.class).list()) {
            lines.computeIfAbsent(l.versionId(), k -> new ArrayList<>())
                    .add(new CostResolver.Line(l.ingredientId(), l.quantityBase(), l.wastePct()));
        }
        for (VersionRow v : jdbc.sql("""
                select r.variant_id, r.ingredient_id, v.id as version_id, v.effective_from, v.output_qty
                from recipe_versions v join recipes r on r.id = v.recipe_id
                where r.business_id = :b order by v.effective_from, v.id
                """).param("b", b).query(VersionRow.class).list()) {
            VerRec rec = new VerRec(v.effectiveFrom(),
                    new CostResolver.Version(v.outputQty(), lines.getOrDefault(v.versionId(), List.of())), v.versionId());
            if (v.variantId() != null) book.byVariant.computeIfAbsent(v.variantId(), k -> new ArrayList<>()).add(rec);
            if (v.ingredientId() != null) book.byIngredient.computeIfAbsent(v.ingredientId(), k -> new ArrayList<>()).add(rec);
        }
        for (CostRow c : jdbc.sql("""
                select h.id, h.ingredient_id, h.effective_date, h.cost_per_base, h.source
                from ingredient_cost_history h
                where h.business_id = :b and not exists (select 1 from ingredient_cost_voids x where x.cost_id = h.id)
                order by h.ingredient_id, h.effective_date, h.id
                """).param("b", b).query(CostRow.class).list()) {
            book.costs.computeIfAbsent(c.ingredientId(), k -> new ArrayList<>()).add(c);
        }
        return book;
    }

    /** Safe wrapper: a recipe loop becomes an UNKNOWN result with an explanation instead of an error page. */
    public static CostResolver.Result portion(CostResolver res, long variantId, LocalDate date) {
        try {
            return res.portionCost(variantId, date);
        } catch (CostResolver.CycleException e) {
            return new CostResolver.Result(null, CostResolver.UNKNOWN, false, false, List.of(e.getMessage()));
        }
    }

    public static CostResolver.Result unit(CostResolver res, long ingredientId, LocalDate date) {
        try {
            return res.unitCost(ingredientId, date);
        } catch (CostResolver.CycleException e) {
            return new CostResolver.Result(null, CostResolver.UNKNOWN, false, false, List.of(e.getMessage()));
        }
    }

    public static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------ ingredient costs as of a date

    public List<IngredientCost> ingredientCosts(LocalDate asOf) {
        requireCosts();
        LocalDate d = asOf == null ? clock.today() : asOf;
        CostBook book = loadBook();
        CostResolver res = book.resolver();
        List<IngredientCost> out = new ArrayList<>();
        List<IngRow> rows = new ArrayList<>(book.ingredients().values());
        rows.sort((a, b2) -> a.name().compareToIgnoreCase(b2.name()));
        for (IngRow i : rows) {
            CostResolver.Result r = unit(res, i.id(), d);
            CostRow direct = book.directRow(i.id(), d);
            String status = r.cost() == null ? "MISSING" : r.status();
            out.add(new IngredientCost(i.id(), i.name(), i.kind(), i.baseUnit(), r.cost(), status, r.estimated(),
                    direct == null ? null : direct.effectiveDate(), direct == null ? null : direct.source(), r.missing()));
        }
        return out;
    }

    // ------------------------------------------------------------ COGS computation

    private record Computation(Coverage coverage, List<ProductMargin> rows, BigDecimal completeNet,
                               BigDecimal completeCogs, BigDecimal knownCogs, boolean estimated, Set<String> missing) {}

    private static final class ProdAcc {
        long productId;
        long variantId;
        String productName;
        String variantName;
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        boolean anyCosted = false;
        int lines = 0;
        int completeLines = 0;
        int noRecipeLines = 0;
        boolean estimated = false;
        final LinkedHashSet<String> missing = new LinkedHashSet<>();
    }

    private Computation compute(LocalDate from, LocalDate to) {
        long b = props.businessId();
        List<SaleAgg> sales = jdbc.sql("""
                select i.product_id, i.variant_id, max(i.product_name) as product_name, max(i.variant_name) as variant_name,
                       s.txn_date, sum(i.quantity) as quantity, sum(i.line_total) as revenue_inclusive,
                       sum(i.net_taxable) as net_ex_gst
                from sale_items i
                join sales s on s.id = i.sale_id
                join v_daily_sales v on v.business_id = s.business_id and v.txn_date = s.txn_date and v.chosen = 'ITEMISED'
                where s.business_id = :b and s.void_state = 'ACTIVE' and s.txn_date between :f and :t
                group by i.product_id, i.variant_id, s.txn_date
                order by s.txn_date
                """).param("b", b).param("f", from).param("t", to).query(SaleAgg.class).list();
        List<SummaryDay> summaryDays = jdbc.sql("""
                select txn_date, total_inclusive, net_taxable from v_daily_sales
                where business_id = :b and chosen = 'SUMMARY' and txn_date between :f and :t order by txn_date
                """).param("b", b).param("f", from).param("t", to).query(SummaryDay.class).list();
        int itemisedDays = jdbc.sql("""
                select count(*) from v_daily_sales where business_id = :b and chosen = 'ITEMISED' and txn_date between :f and :t
                """).param("b", b).param("f", from).param("t", to).query(Integer.class).single();

        CostBook book = loadBook();
        CostResolver res = book.resolver();
        Map<String, CostResolver.Result> memo = new HashMap<>();
        Map<Long, ProdAcc> prods = new LinkedHashMap<>();
        BigDecimal itemisedNet = BigDecimal.ZERO;
        BigDecimal completeNet = BigDecimal.ZERO;
        BigDecimal partialNet = BigDecimal.ZERO;
        BigDecimal noRecipeNet = BigDecimal.ZERO;
        BigDecimal completeCogs = BigDecimal.ZERO;
        BigDecimal knownCogs = BigDecimal.ZERO;
        boolean estimated = false;
        Set<String> allMissing = new LinkedHashSet<>();

        for (SaleAgg s : sales) {
            String key = s.variantId() + "@" + s.txnDate();
            CostResolver.Result r = memo.get(key);
            if (r == null) {
                r = portion(res, s.variantId(), s.txnDate());
                memo.put(key, r);
            }
            ProdAcc p = prods.get(s.variantId());
            if (p == null) {
                p = new ProdAcc();
                p.productId = s.productId();
                p.variantId = s.variantId();
                p.productName = s.productName();
                p.variantName = s.variantName();
                prods.put(s.variantId(), p);
            }
            BigDecimal net = s.netExGst() == null ? BigDecimal.ZERO : s.netExGst();
            p.qty = p.qty.add(s.quantity());
            p.net = p.net.add(net);
            p.lines++;
            itemisedNet = itemisedNet.add(net);
            if (r.noRecipe()) {
                p.noRecipeLines++;
                noRecipeNet = noRecipeNet.add(net);
                continue;
            }
            if (r.cost() != null) {
                BigDecimal lineCost = r.cost().multiply(s.quantity());
                p.cogs = p.cogs.add(lineCost);
                p.anyCosted = true;
                knownCogs = knownCogs.add(lineCost);
                if (r.estimated()) {
                    p.estimated = true;
                    estimated = true;
                }
                if (CostResolver.COMPLETE.equals(r.status())) {
                    p.completeLines++;
                    completeNet = completeNet.add(net);
                    completeCogs = completeCogs.add(lineCost);
                    continue;
                }
            }
            partialNet = partialNet.add(net);
            p.missing.addAll(r.missing());
            allMissing.addAll(r.missing());
        }

        List<ProductMargin> rows = new ArrayList<>();
        for (ProdAcc p : prods.values()) {
            String status;
            if (p.noRecipeLines == p.lines) status = "NO_RECIPE";
            else if (p.completeLines == p.lines) status = "COMPLETE";
            else status = "PARTIAL";
            BigDecimal cogs = p.anyCosted ? money(p.cogs) : null;
            BigDecimal gp = null;
            BigDecimal margin = null;
            if ("COMPLETE".equals(status)) {
                gp = money(p.net.subtract(p.cogs));
                if (p.net.signum() > 0) margin = gp.multiply(new BigDecimal("100")).divide(money(p.net), 1, RoundingMode.HALF_UP);
            }
            rows.add(new ProductMargin(p.productId, p.variantId, p.productName, p.variantName, p.qty, money(p.net),
                    cogs, gp, margin, status, p.estimated, new ArrayList<>(p.missing)));
        }
        rows.sort((x, y) -> y.netSales().compareTo(x.netSales()));

        BigDecimal summaryNet = BigDecimal.ZERO;
        BigDecimal summaryGross = BigDecimal.ZERO;
        List<LocalDate> summaryDates = new ArrayList<>();
        for (SummaryDay d : summaryDays) {
            summaryDates.add(d.txnDate());
            if (d.netTaxable() != null) summaryNet = summaryNet.add(d.netTaxable());
            if (d.totalInclusive() != null) summaryGross = summaryGross.add(d.totalInclusive());
        }
        BigDecimal pct = itemisedNet.signum() > 0
                ? completeNet.multiply(new BigDecimal("100")).divide(itemisedNet, 1, RoundingMode.HALF_UP) : null;
        Coverage cov = new Coverage(money(itemisedNet), money(completeNet), money(partialNet), money(noRecipeNet), pct,
                itemisedDays, summaryDays.size(), money(summaryNet), money(summaryGross), summaryDates);
        return new Computation(cov, rows, completeNet, completeCogs, knownCogs, estimated, allMissing);
    }

    private List<String> flagsFor(Computation c) {
        List<String> flags = new ArrayList<>();
        Coverage cov = c.coverage();
        if (cov.itemisedNetSales().signum() == 0) flags.add("MISSING");
        else if (cov.coveragePct() != null && cov.coveragePct().compareTo(new BigDecimal("100")) < 0) flags.add("PARTIAL");
        if (c.estimated()) flags.add("ESTIMATED");
        if (cov.summaryOnlyDays() > 0) flags.add("SUMMARY_DAYS_EXCLUDED");
        return flags;
    }

    public ProductMarginReport productMargins(LocalDate from, LocalDate to) {
        requireCosts();
        range(from, to);
        Computation c = compute(from, to);
        return new ProductMarginReport(from, to, c.coverage(), flagsFor(c), c.rows());
    }

    public GrossProfit grossProfit(LocalDate from, LocalDate to) {
        requireCosts();
        range(from, to);
        return grossProfitOf(from, to, compute(from, to));
    }

    private GrossProfit grossProfitOf(LocalDate from, LocalDate to, Computation c) {
        Coverage cov = c.coverage();
        BigDecimal net = cov.itemisedNetSales();
        BigDecimal cogsKnown = money(c.knownCogs());
        BigDecimal gp = net.subtract(cogsKnown);
        BigDecimal margin = net.signum() > 0 ? gp.multiply(new BigDecimal("100")).divide(net, 1, RoundingMode.HALF_UP) : null;
        BigDecimal gpComplete = money(c.completeNet().subtract(c.completeCogs()));
        List<String> flags = flagsFor(c);
        List<String> notes = new ArrayList<>();
        notes.add("Gross profit = net sales ex-GST of ITEMISED days minus theoretical COGS (recipe x ingredient cost as of the sale date).");
        if (cov.summaryOnlyDays() > 0) {
            notes.add(cov.summaryOnlyDays() + " day(s) have only a quick daily total (" + cov.summaryOnlyNetSales()
                    + " net sales). They contribute NO cost of goods sold and are excluded from gross profit.");
        }
        if (cov.coveragePct() != null && cov.coveragePct().compareTo(new BigDecimal("100")) < 0) {
            notes.add("Gross profit is estimated because cost data is incomplete: only " + cov.coveragePct()
                    + "% of itemised sales have a complete recipe and ingredient costs.");
        }
        if (c.estimated()) notes.add("Some ingredient costs are estimates.");
        return new GrossProfit(from, to, cov, net, cogsKnown, money(gp), margin, gpComplete, cov.completeNetSales(),
                money(c.completeCogs()), flags, notes, new ArrayList<>(c.missing()));
    }

    /** Existing P&L figures plus COGS / gross profit with explicit coverage. Never a single unqualified profit number. */
    public RealPnl realPnl(LocalDate from, LocalDate to) {
        requireCosts();
        range(from, to);
        ReportService.Pnl pnl = reports.pnl(from, to);
        GrossProfit gp = grossProfitOf(from, to, compute(from, to));
        BigDecimal wastage = jdbc.sql("""
                select coalesce(sum(cost_total), 0) from wastage_entries
                where business_id = :b and void_state = 'ACTIVE' and txn_date between :f and :t
                """).param("b", props.businessId()).param("f", from).param("t", to).query(BigDecimal.class).single();
        long wastageNoCost = jdbc.sql("""
                select count(*) from wastage_entries
                where business_id = :b and void_state = 'ACTIVE' and cost_status <> 'COMPLETE' and txn_date between :f and :t
                """).param("b", props.businessId()).param("f", from).param("t", to).query(Long.class).single();
        List<String> flags = new ArrayList<>(gp.flags());
        List<String> notes = new ArrayList<>(gp.notes());
        BigDecimal netProfit = null;
        Coverage cov = gp.coverage();
        boolean full = cov.summaryOnlyDays() == 0 && cov.itemisedNetSales().signum() > 0
                && cov.coveragePct() != null && cov.coveragePct().compareTo(new BigDecimal("100")) == 0;
        if (full) {
            netProfit = gp.grossProfit().subtract(pnl.expensesTotal()).subtract(pnl.purchasesExpenseTreated());
            notes.add("Net operating profit = gross profit - expenses - expense-treated purchases. Wastage is already inside theoretical usage only if recorded; it is shown separately and not deducted.");
        } else {
            flags.add("NET_PROFIT_NOT_AVAILABLE");
            notes.add("Net operating profit is NOT shown: it needs 100% itemised sales with complete recipe costs for the whole period.");
        }
        if (wastageNoCost > 0) notes.add(wastageNoCost + " wastage entr(ies) have no cost, so the wastage cost is understated.");
        return new RealPnl(from, to, pnl, gp, money(wastage), wastageNoCost, netProfit == null ? null : money(netProfit),
                flags, notes);
    }

    private static void range(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw ApiException.badRequest("from and to dates are required");
        if (to.isBefore(from)) throw ApiException.badRequest("'to' cannot be before 'from'");
        if (to.toEpochDay() - from.toEpochDay() > 800) throw ApiException.badRequest("Date range too long");
    }
}
