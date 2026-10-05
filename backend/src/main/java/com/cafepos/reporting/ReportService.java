package com.cafepos.reporting;

import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only reports. Principles:
 *  - sales figures come from v_daily_sales, which never adds itemised + quick totals;
 *  - GST-inclusive takings are shown as net sales EX-GST (taxable) with the tax shown separately;
 *  - nothing is invented: Phase 1 has no recipes/stock, so COGS and gross profit are reported as NOT AVAILABLE;
 *  - every report carries a "quality" block so a figure is never shown without how solid it is.
 */
@Service
public class ReportService {

    public record DayRow(LocalDate txnDate, String basis, String reviewStatus, String chosen, BigDecimal itemisedTotal,
                         BigDecimal summaryTotal, BigDecimal difference, BigDecimal totalInclusive, Integer ordersCount,
                         BigDecimal netTaxable, BigDecimal taxAmount, BigDecimal cash, BigDecimal upi, BigDecimal card,
                         BigDecimal whatsapp, BigDecimal swiggy, BigDecimal zomato, BigDecimal otherOnline) {}

    public record CategoryAmount(Long categoryId, String category, BigDecimal amount, long count) {}

    public record Quality(int daysInRange, int daysWithSales, int daysItemisedOnly, int daysSummaryOnly,
                          int daysBoth, int daysPendingApproval, int daysStaleApproval, int daysMissing,
                          long estimatedExpenses, long partialRecords, long backdatedRecords) {}

    public record Pnl(LocalDate from, LocalDate to, BigDecimal grossSalesInclusive, BigDecimal netSalesExGst,
                      BigDecimal gstCollected, BigDecimal purchasesInventory, BigDecimal purchasesExpenseTreated,
                      BigDecimal purchasesFixedAssets, BigDecimal expensesTotal, List<CategoryAmount> expensesByCategory,
                      BigDecimal indicativeSurplus, boolean cogsAvailable, List<String> notes, Quality quality) {}

    public record MonthRow(String month, BigDecimal netSalesExGst, BigDecimal gstCollected, BigDecimal grossSales,
                           BigDecimal purchases, BigDecimal expenses, BigDecimal indicativeSurplus, int daysWithSales,
                           int daysInPeriod) {}

    public record ChannelRow(String method, BigDecimal amount) {}

    public record ProductRow(long productId, String productName, String categoryName, BigDecimal quantity,
                             BigDecimal revenueInclusive, BigDecimal netExGst, BigDecimal discount) {}

    public record MissingDay(LocalDate date) {}

    public record HealthItem(String code, String severity, long count, String message) {}

    public record DataHealth(LocalDate from, LocalDate to, List<HealthItem> items, List<LocalDate> missingDays,
                             List<LocalDate> pendingApprovalDays, List<LocalDate> staleApprovalDays) {}

    public record Dashboard(LocalDate date, BigDecimal todayGross, Integer todayOrders, BigDecimal todayExpenses,
                            BigDecimal monthGross, BigDecimal monthNetExGst, BigDecimal monthExpenses,
                            BigDecimal monthPurchases, List<ChannelRow> todayChannels, long pendingApprovals,
                            long missingDaysThisMonth, long openOrders) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;

    public ReportService(JdbcClient jdbc, CafeProps props, BusinessClock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    // ------------------------------------------------------------ sales by day

    public List<DayRow> salesByDay(LocalDate from, LocalDate to) {
        CurrentUser.require("reports.view");
        range(from, to);
        return jdbc.sql("""
                select txn_date, basis, review_status, chosen, itemised_total, summary_total, difference,
                       total_inclusive, orders_count, net_taxable, tax_amount, cash, upi, card, whatsapp, swiggy,
                       zomato, other_online
                from v_daily_sales
                where business_id = :b and txn_date between :f and :t
                order by txn_date
                """).param("b", props.businessId()).param("f", from).param("t", to).query(DayRow.class).list();
    }

    // ------------------------------------------------------------ P&L (Phase 1: no COGS)

    public Pnl pnl(LocalDate from, LocalDate to) {
        CurrentUser.require("reports.view");
        range(from, to);
        BigDecimal gross = sum("select coalesce(sum(total_inclusive),0) from v_daily_sales where business_id=:b and txn_date between :f and :t", from, to);
        BigDecimal net = sum("select coalesce(sum(net_taxable),0) from v_daily_sales where business_id=:b and txn_date between :f and :t", from, to);
        BigDecimal tax = sum("select coalesce(sum(tax_amount),0) from v_daily_sales where business_id=:b and txn_date between :f and :t", from, to);
        BigDecimal pInv = purchases(from, to, "INVENTORY");
        BigDecimal pExp = purchases(from, to, "EXPENSE");
        BigDecimal pFa = purchases(from, to, "FIXED_ASSET");
        List<CategoryAmount> byCat = jdbc.sql("""
                select e.category_id as category_id, coalesce(c.name, 'Uncategorised') as category,
                       sum(e.amount) as amount, count(*) as count
                from expenses e left join expense_categories c on c.id = e.category_id
                where e.business_id = :b and e.void_state = 'ACTIVE' and e.txn_date between :f and :t
                group by e.category_id, c.name order by sum(e.amount) desc
                """).param("b", props.businessId()).param("f", from).param("t", to)
                .query(CategoryAmount.class).list();
        BigDecimal expenses = byCat.stream().map(CategoryAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Cash-basis, purchases-as-cost indication ONLY. Not a profit figure: stock on hand is not tracked in Phase 1.
        BigDecimal surplus = net.subtract(expenses).subtract(pInv).subtract(pExp);
        List<String> notes = new ArrayList<>();
        notes.add("Net sales are shown ex-GST (prices are GST-inclusive; tax is extracted at the configured rate).");
        notes.add("Cost of goods sold and gross profit are NOT AVAILABLE: recipes and stock are not tracked yet.");
        notes.add("Indicative surplus = net sales - purchases (inventory + expense-treated) - expenses. Purchases are counted when billed, not when consumed, so this is an ESTIMATE.");
        notes.add("Input tax credit (ITC) is not deducted: eligibility is pending CA confirmation.");
        return new Pnl(from, to, gross, net, tax, pInv, pExp, pFa, expenses, byCat, surplus, false, notes,
                quality(from, to));
    }

    // ------------------------------------------------------------ month by month (three-month review)

    public List<MonthRow> months(int count) {
        CurrentUser.require("reports.view");
        int n = Math.min(Math.max(count, 1), 24);
        YearMonth cur = YearMonth.from(clock.today());
        List<MonthRow> rows = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            YearMonth ym = cur.minusMonths(i);
            LocalDate from = ym.atDay(1);
            LocalDate to = ym.atEndOfMonth();
            if (to.isAfter(clock.today())) to = clock.today();
            if (from.isBefore(clock.dataStartDate())) from = clock.dataStartDate();
            if (to.isBefore(from)) continue;
            BigDecimal gross = sum("select coalesce(sum(total_inclusive),0) from v_daily_sales where business_id=:b and txn_date between :f and :t", from, to);
            BigDecimal net = sum("select coalesce(sum(net_taxable),0) from v_daily_sales where business_id=:b and txn_date between :f and :t", from, to);
            BigDecimal tax = sum("select coalesce(sum(tax_amount),0) from v_daily_sales where business_id=:b and txn_date between :f and :t", from, to);
            BigDecimal pur = purchases(from, to, "INVENTORY").add(purchases(from, to, "EXPENSE"));
            BigDecimal exp = sum("select coalesce(sum(amount),0) from expenses where business_id=:b and void_state='ACTIVE' and txn_date between :f and :t", from, to);
            int days = jdbc.sql("select count(*) from v_daily_sales where business_id=:b and txn_date between :f and :t")
                    .param("b", props.businessId()).param("f", from).param("t", to).query(Integer.class).single();
            int period = (int) (to.toEpochDay() - from.toEpochDay() + 1);
            rows.add(new MonthRow(ym.toString(), net, tax, gross, pur, exp, net.subtract(pur).subtract(exp), days, period));
        }
        return rows;
    }

    // ------------------------------------------------------------ channels / products

    public List<ChannelRow> channels(LocalDate from, LocalDate to) {
        CurrentUser.require("reports.view");
        range(from, to);
        return jdbc.sql("""
                select m.method, coalesce(sum(m.amount),0) as amount from (
                  select 'CASH' as method, cash as amount from v_daily_sales where business_id=:b and txn_date between :f and :t
                  union all select 'UPI', upi from v_daily_sales where business_id=:b and txn_date between :f and :t
                  union all select 'CARD', card from v_daily_sales where business_id=:b and txn_date between :f and :t
                  union all select 'WHATSAPP', whatsapp from v_daily_sales where business_id=:b and txn_date between :f and :t
                  union all select 'SWIGGY', swiggy from v_daily_sales where business_id=:b and txn_date between :f and :t
                  union all select 'ZOMATO', zomato from v_daily_sales where business_id=:b and txn_date between :f and :t
                  union all select 'OTHER_ONLINE', other_online from v_daily_sales where business_id=:b and txn_date between :f and :t
                ) m group by m.method order by sum(m.amount) desc nulls last
                """).param("b", props.businessId()).param("f", from).param("t", to).query(ChannelRow.class).list();
    }

    /**
     * Product performance uses ITEMISED sales only (quick daily entries have no product detail).
     * Days chosen as quick-entry by approval are excluded so the numbers agree with the sales totals.
     */
    public List<ProductRow> products(LocalDate from, LocalDate to, int limit) {
        CurrentUser.require("reports.view");
        range(from, to);
        return jdbc.sql("""
                select i.product_id, i.product_name, i.category_name,
                       sum(i.quantity) as quantity, sum(i.line_total) as revenue_inclusive,
                       sum(i.net_taxable) as net_ex_gst, sum(i.discount) as discount
                from sale_items i
                join sales s on s.id = i.sale_id
                join v_daily_sales v on v.business_id = s.business_id and v.txn_date = s.txn_date and v.chosen = 'ITEMISED'
                where s.business_id = :b and s.void_state = 'ACTIVE' and s.txn_date between :f and :t
                group by i.product_id, i.product_name, i.category_name
                order by sum(i.line_total) desc limit :lim
                """).param("b", props.businessId()).param("f", from).param("t", to)
                .param("lim", Math.min(Math.max(limit, 1), 200)).query(ProductRow.class).list();
    }

    // ------------------------------------------------------------ data health

    public DataHealth dataHealth(LocalDate from, LocalDate to) {
        CurrentUser.require("reports.view");
        range(from, to);
        List<LocalDate> missing = missingDays(from, to);
        List<LocalDate> pending = jdbc.sql("select txn_date from v_daily_sales where business_id=:b and txn_date between :f and :t and review_status='PENDING_APPROVAL' order by txn_date")
                .param("b", props.businessId()).param("f", from).param("t", to).query(LocalDate.class).list();
        List<LocalDate> stale = jdbc.sql("select txn_date from v_daily_sales where business_id=:b and txn_date between :f and :t and review_status='STALE' order by txn_date")
                .param("b", props.businessId()).param("f", from).param("t", to).query(LocalDate.class).list();
        long uncategorised = count("select count(*) from expenses where business_id=:b and void_state='ACTIVE' and category_id is null and txn_date between :f and :t", from, to);
        long estimated = count("select count(*) from expenses where business_id=:b and void_state='ACTIVE' and is_estimated and txn_date between :f and :t", from, to);
        long drafts = count("select count(*) from expenses where business_id=:b and void_state='ACTIVE' and confirmation_state='DRAFT' and txn_date between :f and :t", from, to)
                + count("select count(*) from purchases where business_id=:b and void_state='ACTIVE' and confirmation_state='DRAFT' and txn_date between :f and :t", from, to);
        long noSupplier = count("select count(*) from purchases where business_id=:b and void_state='ACTIVE' and supplier_id is null and txn_date between :f and :t", from, to);
        long unpaid = count("select count(*) from purchases where business_id=:b and void_state='ACTIVE' and payment_status <> 'PAID' and txn_date between :f and :t", from, to);
        long summaryOnly = count("select count(*) from v_daily_sales where business_id=:b and basis='SUMMARY' and txn_date between :f and :t", from, to);
        List<HealthItem> items = new ArrayList<>();
        items.add(new HealthItem("MISSING_SALES_DAYS", missing.isEmpty() ? "OK" : "WARN", missing.size(),
                "Open days with no sales recorded (mark a day CLOSED in the calendar if the shop was shut)"));
        items.add(new HealthItem("PENDING_APPROVAL", pending.isEmpty() ? "OK" : "WARN", pending.size(),
                "Days where itemised and quick totals differ and are waiting for approval"));
        items.add(new HealthItem("STALE_APPROVAL", stale.isEmpty() ? "OK" : "WARN", stale.size(),
                "Approved days whose figures changed afterwards - needs re-approval"));
        items.add(new HealthItem("SUMMARY_ONLY_DAYS", summaryOnly == 0 ? "OK" : "INFO", summaryOnly,
                "Days with a quick total only - no product-level detail"));
        items.add(new HealthItem("UNCATEGORISED_EXPENSES", uncategorised == 0 ? "OK" : "WARN", uncategorised,
                "Expenses without a category"));
        items.add(new HealthItem("ESTIMATED_EXPENSES", estimated == 0 ? "OK" : "INFO", estimated,
                "Expenses marked as estimated"));
        items.add(new HealthItem("DRAFT_RECORDS", drafts == 0 ? "OK" : "WARN", drafts,
                "Expenses/purchases still in DRAFT"));
        items.add(new HealthItem("PURCHASES_NO_SUPPLIER", noSupplier == 0 ? "OK" : "INFO", noSupplier,
                "Purchases with no supplier recorded"));
        items.add(new HealthItem("PURCHASES_UNPAID", unpaid == 0 ? "OK" : "INFO", unpaid,
                "Purchases not fully paid"));
        return new DataHealth(from, to, items, missing, pending, stale);
    }

    // ------------------------------------------------------------ dashboard

    public Dashboard dashboard(LocalDate clientToday) {
        CurrentUser.require("reports.view");
        // "Today" is the user's own calendar date when the browser supplies it (never in the future relative to the business zone +1 day).
        LocalDate serverToday = clock.today();
        LocalDate today = clientToday != null && !clientToday.isAfter(serverToday.plusDays(1)) && !clientToday.isBefore(serverToday.minusDays(1))
                ? clientToday : serverToday;
        LocalDate monthStart = today.withDayOfMonth(1);
        if (monthStart.isBefore(clock.dataStartDate())) monthStart = clock.dataStartDate();
        var todayRow = jdbc.sql("select total_inclusive, orders_count from v_daily_sales where business_id=:b and txn_date=:d")
                .param("b", props.businessId()).param("d", today).query(DayRow2.class).optional();
        BigDecimal todayExp = sum("select coalesce(sum(amount),0) from expenses where business_id=:b and void_state='ACTIVE' and txn_date between :f and :t", today, today);
        BigDecimal mGross = sum("select coalesce(sum(total_inclusive),0) from v_daily_sales where business_id=:b and txn_date between :f and :t", monthStart, today);
        BigDecimal mNet = sum("select coalesce(sum(net_taxable),0) from v_daily_sales where business_id=:b and txn_date between :f and :t", monthStart, today);
        BigDecimal mExp = sum("select coalesce(sum(amount),0) from expenses where business_id=:b and void_state='ACTIVE' and txn_date between :f and :t", monthStart, today);
        BigDecimal mPur = purchases(monthStart, today, "INVENTORY").add(purchases(monthStart, today, "EXPENSE"));
        long pending = count("select count(*) from v_daily_sales where business_id=:b and review_status in ('PENDING_APPROVAL','STALE') and txn_date between :f and :t", clock.dataStartDate(), today);
        long openOrders = jdbc.sql("select count(*) from orders where business_id=:b and status not in ('COMPLETED','CANCELLED')")
                .param("b", props.businessId()).query(Long.class).single();
        // yesterday and earlier only: today is still in progress
        long missing = missingDays(monthStart, today.minusDays(1)).size();
        return new Dashboard(today, todayRow.map(DayRow2::totalInclusive).orElse(BigDecimal.ZERO),
                todayRow.map(DayRow2::ordersCount).orElse(0), todayExp, mGross, mNet, mExp, mPur,
                channels(today, today), pending, missing, openOrders);
    }

    public record DayRow2(BigDecimal totalInclusive, Integer ordersCount) {}

    // ------------------------------------------------------------ helpers

    private List<LocalDate> missingDays(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) return List.of();
        LocalDate start = from.isBefore(clock.dataStartDate()) ? clock.dataStartDate() : from;
        if (to.isBefore(start)) return List.of();
        return jdbc.sql("""
                select d::date from generate_series(cast(:f as date), cast(:t as date), interval '1 day') d
                where not exists (select 1 from v_daily_sales v where v.business_id = :b and v.txn_date = d::date)
                  and not exists (select 1 from business_calendar c where c.business_id = :b and c.cal_date = d::date
                                  and c.day_type in ('CLOSED','HOLIDAY'))
                order by 1
                """).param("b", props.businessId()).param("f", start).param("t", to).query(LocalDate.class).list();
    }

    private Quality quality(LocalDate from, LocalDate to) {
        int[] c = new int[5];
        jdbc.sql("""
                select basis, review_status, count(*) as n from v_daily_sales
                where business_id = :b and txn_date between :f and :t group by basis, review_status
                """).param("b", props.businessId()).param("f", from).param("t", to)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3)}).list()
                .forEach(o -> {
                    String basis = (String) o[0];
                    String rv = (String) o[1];
                    int n = (Integer) o[2];
                    c[0] += n;
                    if ("ITEMISED".equals(basis)) c[1] += n; else if ("SUMMARY".equals(basis)) c[2] += n; else c[3] += n;
                    if ("PENDING_APPROVAL".equals(rv)) c[4] += n;
                });
        int stale = (int) count("select count(*) from v_daily_sales where business_id=:b and review_status='STALE' and txn_date between :f and :t", from, to);
        LocalDate start = from.isBefore(clock.dataStartDate()) ? clock.dataStartDate() : from;
        int daysInRange = to.isBefore(start) ? 0 : (int) (to.toEpochDay() - start.toEpochDay() + 1);
        long est = count("select count(*) from expenses where business_id=:b and void_state='ACTIVE' and is_estimated and txn_date between :f and :t", from, to);
        long partial = count("select count(*) from expenses where business_id=:b and void_state='ACTIVE' and is_partial and txn_date between :f and :t", from, to)
                + count("select count(*) from purchases where business_id=:b and void_state='ACTIVE' and is_partial and txn_date between :f and :t", from, to);
        long backdated = countTz("select count(*) from expenses where business_id=:b and void_state='ACTIVE' and (entry_at at time zone :tz)::date > txn_date and txn_date between :f and :t", from, to)
                + countTz("select count(*) from sales where business_id=:b and void_state='ACTIVE' and (entry_at at time zone :tz)::date > txn_date and txn_date between :f and :t", from, to);
        return new Quality(daysInRange, c[0], c[1], c[2], c[3], c[4], stale, missingDays(from, to).size(), est, partial, backdated);
    }

    private BigDecimal purchases(LocalDate from, LocalDate to, String treatment) {
        return jdbc.sql("""
                select coalesce(sum(i.total), 0) from purchase_items i join purchases p on p.id = i.purchase_id
                where p.business_id = :b and p.void_state = 'ACTIVE' and i.treatment = :tr and p.txn_date between :f and :t
                """).param("b", props.businessId()).param("tr", treatment).param("f", from).param("t", to)
                .query(BigDecimal.class).single();
    }

    private BigDecimal sum(String sql, LocalDate from, LocalDate to) {
        return jdbc.sql(sql).param("b", props.businessId()).param("f", from).param("t", to)
                .query(BigDecimal.class).single();
    }

    private long count(String sql, LocalDate from, LocalDate to) {
        return jdbc.sql(sql).param("b", props.businessId()).param("f", from).param("t", to)
                .query(Long.class).single();
    }

    private long countTz(String sql, LocalDate from, LocalDate to) {
        return jdbc.sql(sql).param("b", props.businessId()).param("f", from).param("t", to)
                .param("tz", clock.zone().getId()).query(Long.class).single();
    }

    private void range(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw ApiException.badRequest("from and to dates are required");
        if (to.isBefore(from)) throw ApiException.badRequest("'to' cannot be before 'from'");
        if (to.toEpochDay() - from.toEpochDay() > 800) throw ApiException.badRequest("Date range too long");
    }
}
