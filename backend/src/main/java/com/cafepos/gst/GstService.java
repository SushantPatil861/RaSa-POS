package com.cafepos.gst;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * GST workpapers for QRMP (quarterly return, monthly payment). Everything is a SNAPSHOT for the owner's CA:
 * nothing is filed from here, no day is silently dropped (gaps become data-quality warnings) and ITC is counted
 * only where staff / the CA marked it eligible.
 */
@Service
public class GstService {

    private static final Set<String> RETURN_TYPES = Set.of("GSTR1", "GSTR3B", "PMT06");
    private static final Set<String> PERIOD_TYPES = Set.of("QUARTER", "MONTH");
    private static final Pattern GSTIN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final int MAX_WARNING_DATES = 100;

    // ------------------------------------------------------------------ DTOs

    public record Warning(String code, String severity, String message, List<String> dates) {}

    public record ItcLine(long purchaseId, String purchaseNo, String invoiceNo, LocalDate invoiceDate, LocalDate txnDate,
                          LocalDate itcDate, String supplierName, String supplierGstin, BigDecimal taxableValue,
                          BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal totalTax, Boolean itcEligible) {}

    public record MonthFigures(LocalDate start, LocalDate end, String label, boolean qrmpPaymentMonth,
                               List<GstCalculator.RateLine> outwardByRate, GstCalculator.OutwardTotals outward,
                               GstCalculator.ItcSummary itc, GstCalculator.Settlement settlement,
                               BigDecimal pmt06Estimate, String note) {}

    public record Gstr1B2cs(String placeOfSupply, List<GstCalculator.RateLine> lines,
                            GstCalculator.OutwardTotals totals, String note) {}

    public record Gstr3b31a(BigDecimal taxableValue, BigDecimal igst, BigDecimal cgst, BigDecimal sgst, BigDecimal cess,
                            String note) {}

    public record Gstr3b(Gstr3b31a table31a, String table31bToE, GstCalculator.ItcSummary table4,
                         GstCalculator.Credit openingCredit, GstCalculator.Settlement table61,
                         BigDecimal pmt06PaidLogged, int pmt06FilingsLogged, BigDecimal balanceAfterPmt06,
                         String note) {}

    public record Figures(String periodType, LocalDate periodStart, LocalDate periodEnd, String label, String fyLabel,
                          List<MonthFigures> months, Gstr1B2cs gstr1, Gstr3b gstr3b, List<ItcLine> itcLines,
                          List<String> notes) {}

    public record Computed(Figures figures, List<Warning> warnings) {}

    public record WorkpaperRow(long id, String periodType, LocalDate periodStart, LocalDate periodEnd, String status,
                               int version, OffsetDateTime preparedAt, String preparedByName, OffsetDateTime reviewedAt,
                               String reviewedByName, String notes, int warningCount) {}

    public record WorkpaperDto(long id, String periodType, LocalDate periodStart, LocalDate periodEnd, String status,
                               int version, OffsetDateTime preparedAt, String preparedByName, OffsetDateTime reviewedAt,
                               String reviewedByName, String notes, boolean latest, JsonNode figures, JsonNode warnings) {}

    public record WorkpaperRequest(String periodType, LocalDate periodStart, BigDecimal openingCreditIgst,
                                   BigDecimal openingCreditCgst, BigDecimal openingCreditSgst, String notes) {}

    public record ReviewRequest(String notes) {}

    public record MonthInfo(LocalDate start, LocalDate end, String label, boolean qrmpPaymentMonth) {}

    public record QuarterInfo(LocalDate start, LocalDate end, String label, int quarter, List<MonthInfo> months,
                              Long latestWorkpaperId, Integer latestVersion, String latestStatus) {}

    public record FilingDto(long id, String returnType, LocalDate periodStart, LocalDate periodEnd, LocalDate filedOn,
                            String arn, BigDecimal taxPaid, Long workpaperId, String notes, String voidState,
                            String voidReason, int version, OffsetDateTime createdAt, String createdByName) {}

    public record FilingRequest(String returnType, LocalDate periodStart, LocalDate periodEnd, LocalDate filedOn,
                                String arn, BigDecimal taxPaid, Long workpaperId, String notes) {}

    public record ItcPurchaseRow(long purchaseId, String purchaseNo, LocalDate txnDate, String invoiceNo,
                                 LocalDate invoiceDate, String supplierName, String supplierMasterGstin,
                                 BigDecimal totalAmount, Long detailId, BigDecimal taxableValue, BigDecimal cgst,
                                 BigDecimal sgst, BigDecimal igst, BigDecimal totalTax, String supplierGstin,
                                 Boolean itcEligible, String notes, Integer version) {}

    public record ItcRequest(BigDecimal taxableValue, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
                             String supplierGstin, Boolean itcEligible, String notes, String reason, Integer version) {}

    // internal row types (public: JdbcClient maps them reflectively)
    public record SaleRow(LocalDate txnDate, String basis, String reviewStatus, String chosen, BigDecimal netTaxable,
                          BigDecimal taxAmount, BigDecimal totalInclusive) {}

    public record RuleRow(LocalDate effectiveFrom, BigDecimal rate, Boolean itcEligible) {}

    public record CountTotal(long cnt, BigDecimal total) {}

    public record LatestRow(long id, LocalDate periodStart, String status, int version) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;
    private final ObjectMapper json;

    public GstService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ periods

    public List<QuarterInfo> quarters(Integer fyStartYear) {
        CurrentUser.require("gst.view");
        int fy = fyStartYear != null ? fyStartYear : GstCalculator.fyStartYear(clock.today());
        LocalDate fyStart = LocalDate.of(fy, 4, 1);
        List<LatestRow> latest = jdbc.sql("""
                select distinct on (period_start) id, period_start, status, version
                from gst_workpapers
                where business_id = :b and period_type = 'QUARTER' and period_start >= :f and period_start <= :t
                order by period_start, version desc
                """).param("b", props.businessId()).param("f", fyStart).param("t", fyStart.plusMonths(9))
                .query(LatestRow.class).list();
        List<QuarterInfo> out = new ArrayList<>();
        for (int q = 0; q < 4; q++) {
            LocalDate qs = fyStart.plusMonths(3L * q);
            LocalDate qe = GstCalculator.quarterEnd(qs);
            List<MonthInfo> months = new ArrayList<>();
            for (int m = 0; m < 3; m++) {
                LocalDate ms = qs.plusMonths(m);
                months.add(new MonthInfo(ms, YearMonth.from(ms).atEndOfMonth(), monthLabel(ms),
                        GstCalculator.isSelfAssessmentMonth(qs, ms)));
            }
            LatestRow lr = latest.stream().filter(l -> l.periodStart().equals(qs)).findFirst().orElse(null);
            out.add(new QuarterInfo(qs, qe, "Q" + (q + 1) + " " + GstCalculator.fyLabel(fy) + " ("
                    + monthShort(qs) + "-" + monthShort(qe) + " " + qe.getYear() + ")", q + 1, months,
                    lr == null ? null : lr.id(), lr == null ? null : lr.version(), lr == null ? null : lr.status()));
        }
        return out;
    }

    // ------------------------------------------------------------------ compute

    public Computed preview(String periodType, LocalDate periodStart, BigDecimal oi, BigDecimal oc, BigDecimal os) {
        CurrentUser.require("gst.view");
        return compute(periodType, periodStart, new GstCalculator.Credit(nz(oi), nz(oc), nz(os)));
    }

    Computed compute(String periodType, LocalDate start, GstCalculator.Credit opening) {
        if (periodType == null || !PERIOD_TYPES.contains(periodType)) {
            throw ApiException.badRequest("Period type must be QUARTER or MONTH");
        }
        if (start == null) throw ApiException.badRequest("Period start is required");
        boolean quarter = "QUARTER".equals(periodType);
        if (quarter && !GstCalculator.isQuarterStart(start)) {
            throw ApiException.badRequest("A quarter must start on 1 Apr, 1 Jul, 1 Oct or 1 Jan");
        }
        if (!quarter && start.getDayOfMonth() != 1) throw ApiException.badRequest("A month must start on the 1st");
        if (opening.igst().signum() < 0 || opening.cgst().signum() < 0 || opening.sgst().signum() < 0) {
            throw ApiException.badRequest("Opening credit cannot be negative");
        }
        LocalDate end = quarter ? GstCalculator.quarterEnd(start) : YearMonth.from(start).atEndOfMonth();
        LocalDate today = clock.today();
        LocalDate dataStart = clock.dataStartDate();
        LocalDate quarterStart = GstCalculator.quarterStart(start);

        List<RuleRow> ruleRows = jdbc.sql("""
                select effective_from, rate, itc_eligible from tax_rules
                where business_id = :b and tax_type = 'GST' and scope = 'ALL' order by effective_from
                """).param("b", props.businessId()).query(RuleRow.class).list();
        List<GstCalculator.RateRule> rules = new ArrayList<>();
        for (RuleRow r : ruleRows) rules.add(new GstCalculator.RateRule(r.effectiveFrom(), r.rate()));
        long categoryRules = jdbc.sql("""
                select count(*) from tax_rules where business_id = :b and tax_type = 'GST' and scope <> 'ALL'
                  and effective_from <= :t
                """).param("b", props.businessId()).param("t", end).query(Long.class).single();

        List<SaleRow> sales = jdbc.sql("""
                select txn_date, basis, review_status, chosen, net_taxable, tax_amount, total_inclusive
                from v_daily_sales
                where business_id = :b and txn_date >= :f and txn_date <= :t order by txn_date
                """).param("b", props.businessId()).param("f", start).param("t", end).query(SaleRow.class).list();

        List<LocalDate> closed = jdbc.sql("""
                select cal_date from business_calendar
                where business_id = :b and cal_date >= :f and cal_date <= :t and day_type in ('CLOSED','HOLIDAY')
                """).param("b", props.businessId()).param("f", start).param("t", end).query(LocalDate.class).list();

        List<ItcLine> itcLines = jdbc.sql("""
                select p.id as purchase_id, p.purchase_no, p.invoice_no, p.invoice_date, p.txn_date,
                       coalesce(p.invoice_date, p.txn_date) as itc_date, s.name as supplier_name, d.supplier_gstin,
                       d.taxable_value, d.cgst, d.sgst, d.igst, d.total_tax, d.itc_eligible
                from purchase_tax_details d
                join purchases p on p.id = d.purchase_id
                left join suppliers s on s.id = p.supplier_id
                where d.business_id = :b and p.void_state = 'ACTIVE'
                  and coalesce(p.invoice_date, p.txn_date) >= :f and coalesce(p.invoice_date, p.txn_date) <= :t
                order by coalesce(p.invoice_date, p.txn_date), p.id
                """).param("b", props.businessId()).param("f", start).param("t", end).query(ItcLine.class).list();

        CountTotal noDetails = jdbc.sql("""
                select count(*) as cnt, coalesce(sum(p.total_amount), 0) as total from purchases p
                where p.business_id = :b and p.void_state = 'ACTIVE'
                  and coalesce(p.invoice_date, p.txn_date) >= :f and coalesce(p.invoice_date, p.txn_date) <= :t
                  and not exists (select 1 from purchase_tax_details d where d.purchase_id = p.id)
                """).param("b", props.businessId()).param("f", start).param("t", end).query(CountTotal.class).single();

        BigDecimal pmt06Paid = jdbc.sql("""
                select coalesce(sum(tax_paid), 0) from gst_filings
                where business_id = :b and return_type = 'PMT06' and void_state = 'ACTIVE'
                  and period_start >= :f and period_end <= :t
                """).param("b", props.businessId()).param("f", start).param("t", end).query(BigDecimal.class).single();
        int pmt06Count = jdbc.sql("""
                select count(*) from gst_filings
                where business_id = :b and return_type = 'PMT06' and void_state = 'ACTIVE'
                  and period_start >= :f and period_end <= :t
                """).param("b", props.businessId()).param("f", start).param("t", end).query(Integer.class).single();

        // ---- per-day rates and data-quality warnings
        List<Warning> warnings = new ArrayList<>();
        List<GstCalculator.SaleDay> allDays = new ArrayList<>();
        Map<LocalDate, SaleRow> byDate = new LinkedHashMap<>();
        List<String> noRate = new ArrayList<>(), pending = new ArrayList<>(), stale = new ArrayList<>(),
                quick = new ArrayList<>(), noTax = new ArrayList<>(), mismatch = new ArrayList<>();
        for (SaleRow s : sales) {
            byDate.put(s.txnDate(), s);
            BigDecimal rate = GstCalculator.rateOn(rules, s.txnDate());
            BigDecimal net = nz(s.netTaxable());
            BigDecimal tax = nz(s.taxAmount());
            allDays.add(new GstCalculator.SaleDay(s.txnDate(), rate, net, tax));
            String d = s.txnDate().toString();
            if (rate == null && net.signum() > 0) noRate.add(d);
            if ("PENDING_APPROVAL".equals(s.reviewStatus())) pending.add(d);
            if ("STALE".equals(s.reviewStatus())) stale.add(d);
            if ("SUMMARY".equals(s.chosen())) quick.add(d);
            if (nz(s.totalInclusive()).signum() > 0 && tax.signum() == 0) noTax.add(d);
            if (rate != null && GstCalculator.taxOn(net, rate).subtract(tax).abs().compareTo(BigDecimal.ONE) > 0) mismatch.add(d);
        }
        if (!noRate.isEmpty()) {
            warnings.add(warn("NO_GST_RATE", "ERROR", noRate.size() + " day(s) with sales fall before any GST rule in Tax rules, "
                    + "so their rate is unknown. Their recorded tax is shown under 'rate not known'.", noRate));
        }
        if (!pending.isEmpty()) {
            warnings.add(warn("PENDING_APPROVAL_DAYS", "WARN", pending.size() + " day(s) have both itemised and quick-entry totals "
                    + "that nobody has approved yet. The itemised figures are used until a day is approved.", pending));
        }
        if (!stale.isEmpty()) {
            warnings.add(warn("STALE_APPROVAL_DAYS", "WARN", stale.size() + " day(s) were approved but their sales changed afterwards. "
                    + "Re-approve them before relying on these figures.", stale));
        }
        if (!quick.isEmpty()) {
            warnings.add(warn("QUICK_ENTRY_DAYS", "INFO", quick.size() + " day(s) use a quick daily total (no bill-by-bill detail); "
                    + "GST on those days was extracted from the total.", quick));
        }
        if (!noTax.isEmpty()) {
            warnings.add(warn("NO_TAX_RECORDED", "WARN", noTax.size() + " day(s) have sales but no GST recorded (tax not configured when entered).", noTax));
        }
        if (!mismatch.isEmpty()) {
            warnings.add(warn("TAX_DIFFERS_FROM_RATE", "WARN", mismatch.size() + " day(s) have recorded tax that differs from taxable value x "
                    + "the GST rule rate by more than Rs 1. Recorded tax is used; check the rate rule or the entries.", mismatch));
        }
        Set<LocalDate> closedSet = new HashSet<>(closed);
        List<String> missing = new ArrayList<>();
        LocalDate scanFrom = start.isBefore(dataStart) ? dataStart : start;
        LocalDate scanTo = end.isBefore(today) ? end : today.minusDays(1);
        for (LocalDate d = scanFrom; !d.isAfter(scanTo); d = d.plusDays(1)) {
            if (!byDate.containsKey(d) && !closedSet.contains(d)) missing.add(d.toString());
        }
        if (!missing.isEmpty()) {
            warnings.add(warn("DAYS_WITHOUT_SALES", "WARN", missing.size() + " day(s) have no sales recorded and are not marked closed or "
                    + "a holiday. They are NOT assumed to be zero - they are simply missing from the totals.", missing));
        }
        if (start.isBefore(dataStart)) {
            warnings.add(warn("BEFORE_DATA_START", "INFO", "The period starts before the system's data-start date (" + dataStart
                    + "); earlier sales are not in this system.", List.of()));
        }
        if (end.isAfter(today) || end.equals(today)) {
            warnings.add(warn("PERIOD_NOT_COMPLETE", "INFO", "The period has not ended yet; figures will change.", List.of()));
        }
        if (categoryRules > 0) {
            warnings.add(warn("CATEGORY_RATES_EXIST", "WARN", "Category-specific GST rules exist but daily sales totals cannot be split by "
                    + "category. This workpaper uses the 'ALL' rate only - ask your CA.", List.of()));
        }
        if (noDetails.cnt() > 0) {
            warnings.add(warn("PURCHASES_WITHOUT_ITC_DETAILS", "WARN", noDetails.cnt() + " purchase(s) worth Rs " + noDetails.total().setScale(2)
                    + " in this period have no GST details entered, so they carry no ITC here.", List.of()));
        }
        List<String> undecided = new ArrayList<>(), noGstin = new ArrayList<>();
        for (ItcLine l : itcLines) {
            if (l.itcEligible() == null) undecided.add(l.purchaseNo());
            if (Boolean.TRUE.equals(l.itcEligible()) && (l.supplierGstin() == null || l.supplierGstin().isBlank())) noGstin.add(l.purchaseNo());
        }
        if (!undecided.isEmpty()) {
            warnings.add(warn("ITC_UNDECIDED", "WARN", undecided.size() + " purchase(s) have tax details but ITC eligibility is undecided "
                    + "(ask the CA). They are listed separately and NOT counted as ITC.", undecided));
        }
        if (!noGstin.isEmpty()) {
            warnings.add(warn("ITC_WITHOUT_SUPPLIER_GSTIN", "WARN", noGstin.size() + " eligible ITC purchase(s) have no supplier GSTIN recorded.", noGstin));
        }
        if (itcLines.stream().anyMatch(l -> l.igst().signum() > 0)) {
            warnings.add(warn("IGST_ITC_PRESENT", "INFO", "Some purchases carry IGST. IGST credit is set off first, as per the usual order; confirm with your CA.", List.of()));
        }

        // ---- months
        List<MonthFigures> months = new ArrayList<>();
        GstCalculator.Credit pool = opening;
        List<LocalDate> monthStarts = new ArrayList<>();
        if (quarter) {
            for (int i = 0; i < 3; i++) monthStarts.add(start.plusMonths(i));
        } else {
            monthStarts.add(start);
        }
        for (LocalDate ms : monthStarts) {
            LocalDate me = YearMonth.from(ms).atEndOfMonth();
            List<GstCalculator.SaleDay> md = new ArrayList<>();
            for (GstCalculator.SaleDay sd : allDays) if (!sd.date().isBefore(ms) && !sd.date().isAfter(me)) md.add(sd);
            List<GstCalculator.RateLine> lines = GstCalculator.outwardByRate(md);
            GstCalculator.OutwardTotals ot = GstCalculator.totals(lines);
            List<GstCalculator.ItcInput> inputs = new ArrayList<>();
            for (ItcLine l : itcLines) {
                if (!l.itcDate().isBefore(ms) && !l.itcDate().isAfter(me)) {
                    inputs.add(new GstCalculator.ItcInput(l.taxableValue(), l.cgst(), l.sgst(), l.igst(), l.itcEligible()));
                }
            }
            GstCalculator.ItcSummary itc = GstCalculator.summariseItc(inputs);
            GstCalculator.Settlement st = GstCalculator.settle(ot.cgst(), ot.sgst(),
                    GstCalculator.addCredit(pool, GstCalculator.credit(itc.eligible())));
            pool = st.carryForward();
            boolean payMonth = GstCalculator.isSelfAssessmentMonth(quarterStart, ms);
            String note = payMonth
                    ? "QRMP payment month: estimate for PMT-06 (self-assessment). Your CA may prefer the fixed-sum (35%) method instead."
                    : "Third month of the quarter: tax is settled in GSTR-3B, not through a PMT-06 estimate.";
            months.add(new MonthFigures(ms, me, monthLabel(ms), payMonth, lines, ot, itc, st,
                    payMonth ? st.payableTotal() : null, note));
        }

        // ---- whole-period views
        List<GstCalculator.RateLine> periodLines = GstCalculator.outwardByRate(allDays);
        GstCalculator.OutwardTotals periodTotals = GstCalculator.totals(periodLines);
        List<GstCalculator.ItcInput> periodInputs = new ArrayList<>();
        for (ItcLine l : itcLines) {
            periodInputs.add(new GstCalculator.ItcInput(l.taxableValue(), l.cgst(), l.sgst(), l.igst(), l.itcEligible()));
        }
        GstCalculator.ItcSummary periodItc = GstCalculator.summariseItc(periodInputs);
        GstCalculator.Settlement periodSettle = GstCalculator.settle(periodTotals.cgst(), periodTotals.sgst(),
                GstCalculator.addCredit(opening, GstCalculator.credit(periodItc.eligible())));
        BigDecimal balance = periodSettle.payableTotal().subtract(pmt06Paid);

        Gstr1B2cs g1 = new Gstr1B2cs("Maharashtra (27)", periodLines, periodTotals,
                "GSTR-1 style summary of B2C (small) supplies by rate. All sales are treated as B2C intra-state; CGST/SGST split 50:50 is an assumption.");
        Gstr3b g3 = new Gstr3b(
                new Gstr3b31a(periodTotals.taxable(), ZERO, periodTotals.cgst(), periodTotals.sgst(), ZERO,
                        "3.1(a) Outward taxable supplies (other than zero rated, nil rated and exempted)"),
                "Zero-rated, nil-rated / exempt, non-GST and reverse-charge supplies are NOT tracked by this system.",
                periodItc, opening, periodSettle, pmt06Paid, pmt06Count, balance,
                "Table 4: only ITC marked eligible is counted. Table 6.1: estimated tax payable after ITC. "
                        + "'balanceAfterPmt06' subtracts PMT-06 payments you logged in the filings log.");

        List<String> notes = new ArrayList<>();
        notes.add("This is a WORKPAPER for your CA. Nothing is filed or submitted from this system. Confirm every figure with your CA before filing.");
        notes.add("All sales are treated as B2C supplies within Maharashtra. CGST and SGST are split 50:50 (assumption - confirm with your CA).");
        notes.add("Sales made through delivery platforms (Swiggy / Zomato) are included above. Ask your CA whether any GST on those is paid by the platform instead of by you.");
        notes.add("Input tax credit is counted ONLY where a purchase was explicitly marked eligible. Purchases with no decision are listed separately.");
        RuleRow latestRule = ruleRows.isEmpty() ? null : ruleRows.stream().filter(r -> !r.effectiveFrom().isAfter(end)).reduce((a, b) -> b).orElse(null);
        if (latestRule == null || latestRule.itcEligible() == null) {
            notes.add("Whether ITC can be claimed at your GST rate is still undecided in Tax rules - ask your CA before using the ITC figures.");
        } else if (!latestRule.itcEligible()) {
            notes.add("Tax rules say ITC is NOT eligible at this rate; any ITC counted here was marked eligible by a person - confirm with your CA.");
        }
        notes.add("ITC is placed in the month of the supplier invoice date (purchase date if the invoice date is blank). Opening credit from earlier periods is "
                + (opening.total().signum() > 0 ? "as entered: " + opening.total().setScale(2) : "not included (zero)") + ".");

        String label = quarter ? quarterLabel(start) : monthLabel(start);
        Figures figures = new Figures(periodType, start, end, label, GstCalculator.fyLabel(GstCalculator.fyStartYear(start)),
                months, g1, g3, itcLines, notes);
        return new Computed(figures, warnings);
    }

    // ------------------------------------------------------------------ workpapers

    @Transactional
    public WorkpaperDto prepare(WorkpaperRequest r) {
        CurrentUser.require("gst.prepare");
        GstCalculator.Credit opening = new GstCalculator.Credit(money(r.openingCreditIgst()), money(r.openingCreditCgst()),
                money(r.openingCreditSgst()));
        Computed c = compute(r.periodType(), r.periodStart(), opening);
        long id;
        try {
            id = jdbc.sql("""
                    insert into gst_workpapers (business_id, period_type, period_start, period_end, figures, warnings,
                                                version, prepared_by, notes)
                    values (:b, :pt, :ps, :pe, cast(:fig as jsonb), cast(:warn as jsonb),
                            (select coalesce(max(version), 0) + 1 from gst_workpapers
                              where business_id = :b and period_type = :pt and period_start = :ps),
                            :u, :n)
                    returning id
                    """)
                    .param("b", props.businessId()).param("pt", r.periodType()).param("ps", c.figures().periodStart())
                    .param("pe", c.figures().periodEnd()).param("fig", toJson(c.figures())).param("warn", toJson(c.warnings()))
                    .param("u", CurrentUser.id()).param("n", r.notes()).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Someone else just prepared this period. Reload and try again.");
        }
        audit.record("GST_WORKPAPER", id, "CREATE", c.figures().periodStart(), null, snapshot(id), r.notes(), "MANUAL");
        return get(id);
    }

    public List<WorkpaperRow> list(String periodType, LocalDate periodStart) {
        CurrentUser.require("gst.view");
        return jdbc.sql("""
                select w.id, w.period_type, w.period_start, w.period_end, w.status, w.version, w.prepared_at,
                       pu.name as prepared_by_name, w.reviewed_at, ru.name as reviewed_by_name, w.notes,
                       jsonb_array_length(w.warnings) as warning_count
                from gst_workpapers w
                left join users pu on pu.id = w.prepared_by
                left join users ru on ru.id = w.reviewed_by
                where w.business_id = :b
                  and (cast(:pt as text) is null or w.period_type = :pt)
                  and (cast(:ps as date) is null or w.period_start = :ps)
                order by w.period_start desc, w.version desc limit 200
                """).param("b", props.businessId()).param("pt", periodType).param("ps", periodStart)
                .query(WorkpaperRow.class).list();
    }

    public WorkpaperDto get(long id) {
        CurrentUser.require("gst.view");
        WorkpaperFull w = jdbc.sql("""
                select w.id, w.period_type, w.period_start, w.period_end, w.status, w.version, w.prepared_at,
                       pu.name as prepared_by_name, w.reviewed_at, ru.name as reviewed_by_name, w.notes,
                       (w.version = (select max(x.version) from gst_workpapers x
                                      where x.business_id = w.business_id and x.period_type = w.period_type
                                        and x.period_start = w.period_start)) as latest,
                       w.figures::text as figures, w.warnings::text as warnings
                from gst_workpapers w
                left join users pu on pu.id = w.prepared_by
                left join users ru on ru.id = w.reviewed_by
                where w.id = :id and w.business_id = :b
                """).param("id", id).param("b", props.businessId()).query(WorkpaperFull.class).optional()
                .orElseThrow(() -> ApiException.notFound("GST workpaper"));
        return new WorkpaperDto(w.id(), w.periodType(), w.periodStart(), w.periodEnd(), w.status(), w.version(),
                w.preparedAt(), w.preparedByName(), w.reviewedAt(), w.reviewedByName(), w.notes(), w.latest(),
                tree(w.figures()), tree(w.warnings()));
    }

    public record WorkpaperFull(long id, String periodType, LocalDate periodStart, LocalDate periodEnd, String status,
                                int version, OffsetDateTime preparedAt, String preparedByName, OffsetDateTime reviewedAt,
                                String reviewedByName, String notes, boolean latest, String figures, String warnings) {}

    @Transactional
    public WorkpaperDto markReviewed(long id, String notes) {
        CurrentUser.require("gst.prepare");
        WorkpaperDto w = get(id);
        if (!"DRAFT".equals(w.status())) throw ApiException.conflict("Only a DRAFT workpaper can be marked reviewed");
        if (!w.latest()) throw ApiException.conflict("A newer version of this workpaper exists. Review the latest version instead.");
        String before = snapshot(id);
        jdbc.sql("""
                update gst_workpapers set status = 'REVIEWED', reviewed_by = :u, reviewed_at = now(),
                       notes = coalesce(:n, notes)
                where id = :id and business_id = :b and status = 'DRAFT'
                """).param("u", CurrentUser.id()).param("n", blankToNull(notes)).param("id", id)
                .param("b", props.businessId()).update();
        audit.record("GST_WORKPAPER", id, "REVIEW", w.periodStart(), before, snapshot(id), notes, "MANUAL");
        return get(id);
    }

    @Transactional
    public WorkpaperDto markFiled(long id, String notes) {
        CurrentUser.require("gst.prepare");
        WorkpaperDto w = get(id);
        if (!"REVIEWED".equals(w.status())) throw ApiException.conflict("Mark the workpaper reviewed first");
        String before = snapshot(id);
        jdbc.sql("""
                update gst_workpapers set status = 'FILED_MARKED', notes = coalesce(:n, notes)
                where id = :id and business_id = :b and status = 'REVIEWED'
                """).param("n", blankToNull(notes)).param("id", id).param("b", props.businessId()).update();
        audit.record("GST_WORKPAPER", id, "MARK_FILED", w.periodStart(), before, snapshot(id),
                notes, "MANUAL");
        return get(id);
    }

    // ------------------------------------------------------------------ CSV export for the CA

    public String exportCsv(long id) {
        WorkpaperDto w = get(id);
        JsonNode f = w.figures();
        StringBuilder sb = new StringBuilder();
        row(sb, "GST WORKPAPER (QRMP) - FOR YOUR CA. NOT A FILED RETURN. CONFIRM WITH YOUR CA.");
        row(sb, "Period", f.path("label").asText(), f.path("periodStart").asText(), f.path("periodEnd").asText());
        row(sb, "Workpaper id / version / status", String.valueOf(w.id()), String.valueOf(w.version()), w.status());
        row(sb, "Prepared by", w.preparedByName(), String.valueOf(w.preparedAt()));
        row(sb, "Reviewed by", w.reviewedByName(), String.valueOf(w.reviewedAt()));
        sb.append("\n");
        row(sb, "GSTR-1 STYLE: B2C (small) SUPPLIES BY RATE", f.path("gstr1").path("placeOfSupply").asText());
        row(sb, "Rate %", "Taxable value", "CGST", "SGST", "Total tax", "Days");
        for (JsonNode l : f.path("gstr1").path("lines")) {
            row(sb, l.path("rate").isNull() ? "rate not known" : l.path("rate").asText(), l.path("taxable").asText(),
                    l.path("cgst").asText(), l.path("sgst").asText(), l.path("totalTax").asText(), l.path("days").asText());
        }
        JsonNode t1 = f.path("gstr1").path("totals");
        row(sb, "Total", t1.path("taxable").asText(), t1.path("cgst").asText(), t1.path("sgst").asText(), t1.path("totalTax").asText(), t1.path("days").asText());
        sb.append("\n");
        JsonNode g = f.path("gstr3b");
        row(sb, "GSTR-3B STYLE");
        row(sb, "3.1(a) Outward taxable supplies", "Taxable value", "IGST", "CGST", "SGST", "Cess");
        row(sb, "", g.path("table31a").path("taxableValue").asText(), g.path("table31a").path("igst").asText(),
                g.path("table31a").path("cgst").asText(), g.path("table31a").path("sgst").asText(), g.path("table31a").path("cess").asText());
        row(sb, "3.1(b)-(e)", g.path("table31bToE").asText());
        row(sb, "4 ITC", "Taxable value", "IGST", "CGST", "SGST", "Total tax", "Purchases");
        for (String k : new String[] {"eligible", "undecided", "ineligible"}) {
            JsonNode n = g.path("table4").path(k);
            row(sb, "ITC " + k + (k.equals("eligible") ? " (counted)" : " (NOT counted)"), n.path("taxable").asText(),
                    n.path("igst").asText(), n.path("cgst").asText(), n.path("sgst").asText(), n.path("totalTax").asText(), n.path("count").asText());
        }
        JsonNode s = g.path("table61");
        row(sb, "6.1 Payment of tax", "CGST", "SGST", "Total");
        row(sb, "Output tax", s.path("outputCgst").asText(), s.path("outputSgst").asText());
        row(sb, "ITC set off (total)", s.path("itcUsed").asText());
        row(sb, "Estimated cash payable", s.path("payableCgst").asText(), s.path("payableSgst").asText(), s.path("payableTotal").asText());
        row(sb, "Credit carried forward", "IGST " + s.path("carryForward").path("igst").asText(),
                "CGST " + s.path("carryForward").path("cgst").asText(), "SGST " + s.path("carryForward").path("sgst").asText());
        row(sb, "PMT-06 payments logged", g.path("pmt06PaidLogged").asText(), g.path("pmt06FilingsLogged").asText() + " filing(s)");
        row(sb, "Balance after PMT-06 payments", g.path("balanceAfterPmt06").asText());
        sb.append("\n");
        row(sb, "MONTH-WISE (QRMP)");
        row(sb, "Month", "Taxable value", "Output tax", "Eligible ITC", "Undecided ITC (not counted)", "Estimated payable", "PMT-06 estimate", "Note");
        for (JsonNode m : f.path("months")) {
            row(sb, m.path("label").asText(), m.path("outward").path("taxable").asText(), m.path("outward").path("totalTax").asText(),
                    m.path("itc").path("eligible").path("totalTax").asText(), m.path("itc").path("undecided").path("totalTax").asText(),
                    m.path("settlement").path("payableTotal").asText(), m.path("pmt06Estimate").isNull() ? "n/a" : m.path("pmt06Estimate").asText(),
                    m.path("note").asText());
        }
        sb.append("\n");
        row(sb, "ITC DETAIL (purchases)");
        row(sb, "Purchase no", "Invoice no", "Invoice date", "Supplier", "Supplier GSTIN", "Taxable value", "CGST", "SGST", "IGST", "Total tax", "ITC eligible");
        for (JsonNode l : f.path("itcLines")) {
            String el = l.path("itcEligible").isNull() ? "UNDECIDED - ask CA" : (l.path("itcEligible").asBoolean() ? "YES" : "NO");
            row(sb, l.path("purchaseNo").asText(), l.path("invoiceNo").asText(), l.path("itcDate").asText(), l.path("supplierName").asText(),
                    l.path("supplierGstin").asText(), l.path("taxableValue").asText(), l.path("cgst").asText(), l.path("sgst").asText(),
                    l.path("igst").asText(), l.path("totalTax").asText(), el);
        }
        sb.append("\n");
        row(sb, "DATA-QUALITY WARNINGS");
        for (JsonNode wn : w.warnings()) {
            StringBuilder dates = new StringBuilder();
            for (JsonNode d : wn.path("dates")) dates.append(dates.length() > 0 ? " " : "").append(d.asText());
            row(sb, wn.path("severity").asText(), wn.path("code").asText(), wn.path("message").asText(), dates.toString());
        }
        sb.append("\n");
        row(sb, "NOTES");
        for (JsonNode n : f.path("notes")) row(sb, n.asText());
        return sb.toString();
    }

    private static void row(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            String c = cells[i] == null ? "" : cells[i];
            if (c.contains(",") || c.contains("\"") || c.contains("\n")) c = "\"" + c.replace("\"", "\"\"") + "\"";
            sb.append(c);
        }
        sb.append("\r\n");
    }

    // ------------------------------------------------------------------ filings log (manual record only)

    private static final String FILING_SELECT = """
            select f.id, f.return_type, f.period_start, f.period_end, f.filed_on, f.arn, f.tax_paid, f.workpaper_id,
                   f.notes, f.void_state, f.void_reason, f.version, f.created_at, u.name as created_by_name
            from gst_filings f left join users u on u.id = f.created_by
            """;

    public List<FilingDto> filings(LocalDate from, LocalDate to, boolean includeVoided) {
        CurrentUser.require("gst.view");
        return jdbc.sql(FILING_SELECT + """
                where f.business_id = :b
                  and (cast(:f as date) is null or f.period_end >= :f)
                  and (cast(:t as date) is null or f.period_start <= :t)
                  and (:inc or f.void_state = 'ACTIVE')
                order by f.period_start desc, f.filed_on desc, f.id desc limit 300
                """).param("b", props.businessId()).param("f", from).param("t", to).param("inc", includeVoided)
                .query(FilingDto.class).list();
    }

    @Transactional
    public FilingDto logFiling(FilingRequest r) {
        CurrentUser.require("gst.prepare");
        if (r.returnType() == null || !RETURN_TYPES.contains(r.returnType())) throw ApiException.badRequest("Return type must be GSTR1, GSTR3B or PMT06");
        if (r.periodStart() == null || r.periodEnd() == null || r.periodEnd().isBefore(r.periodStart())) {
            throw ApiException.badRequest("Enter the period this filing covers");
        }
        if (r.filedOn() == null) throw ApiException.badRequest("Enter the date it was filed or paid");
        if (r.filedOn().isAfter(clock.today())) throw ApiException.badRequest("Filing date cannot be in the future");
        if (r.taxPaid() != null && r.taxPaid().signum() < 0) throw ApiException.badRequest("Tax paid cannot be negative");
        if (r.workpaperId() != null) {
            long n = jdbc.sql("select count(*) from gst_workpapers where id = :id and business_id = :b")
                    .param("id", r.workpaperId()).param("b", props.businessId()).query(Long.class).single();
            if (n == 0) throw ApiException.badRequest("That workpaper does not exist");
        }
        long id = jdbc.sql("""
                insert into gst_filings (business_id, return_type, period_start, period_end, filed_on, arn, tax_paid,
                                         workpaper_id, notes, created_by)
                values (:b, :rt, :ps, :pe, :fo, :arn, :tp, :wp, :n, :u) returning id
                """)
                .param("b", props.businessId()).param("rt", r.returnType()).param("ps", r.periodStart()).param("pe", r.periodEnd())
                .param("fo", r.filedOn()).param("arn", blankToNull(r.arn())).param("tp", r.taxPaid())
                .param("wp", r.workpaperId()).param("n", blankToNull(r.notes())).param("u", CurrentUser.id())
                .query(Long.class).single();
        audit.record("GST_FILING", id, "CREATE", r.filedOn(), null, filingSnapshot(id), null, "MANUAL");
        return filing(id);
    }

    @Transactional
    public FilingDto voidFiling(long id, String reason) {
        CurrentUser.require("gst.prepare");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a filing record");
        FilingDto cur = filing(id);
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("Already voided");
        String before = filingSnapshot(id);
        jdbc.sql("""
                update gst_filings set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now(),
                       version = version + 1 where id = :id and business_id = :b
                """).param("r", reason).param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("GST_FILING", id, "VOID", cur.filedOn(), before, filingSnapshot(id), reason, "MANUAL");
        return filing(id);
    }

    private FilingDto filing(long id) {
        return jdbc.sql(FILING_SELECT + " where f.id = :id and f.business_id = :b")
                .param("id", id).param("b", props.businessId()).query(FilingDto.class).optional()
                .orElseThrow(() -> ApiException.notFound("Filing record"));
    }

    // ------------------------------------------------------------------ ITC entry against existing purchases

    private static final String ITC_SELECT = """
            select p.id as purchase_id, p.purchase_no, p.txn_date, p.invoice_no, p.invoice_date, s.name as supplier_name,
                   s.gstin as supplier_master_gstin, p.total_amount, d.id as detail_id, d.taxable_value, d.cgst, d.sgst,
                   d.igst, d.total_tax, d.supplier_gstin, d.itc_eligible, d.notes, d.version
            from purchases p
            left join suppliers s on s.id = p.supplier_id
            left join purchase_tax_details d on d.purchase_id = p.id
            """;

    /** filter: ALL, MISSING (no GST details yet), UNDECIDED (details but ITC undecided), ELIGIBLE. */
    public List<ItcPurchaseRow> itcPurchases(LocalDate from, LocalDate to, String filter, int limit) {
        CurrentUser.require("gst.view");
        String flt = filter == null || filter.isBlank() ? "ALL" : filter;
        if (!Set.of("ALL", "MISSING", "UNDECIDED", "ELIGIBLE").contains(flt)) throw ApiException.badRequest("Unknown filter " + flt);
        return jdbc.sql(ITC_SELECT + """
                where p.business_id = :b and p.void_state = 'ACTIVE'
                  and (cast(:f as date) is null or coalesce(p.invoice_date, p.txn_date) >= :f)
                  and (cast(:t as date) is null or coalesce(p.invoice_date, p.txn_date) <= :t)
                  and (:flt = 'ALL'
                       or (:flt = 'MISSING' and d.id is null)
                       or (:flt = 'UNDECIDED' and d.id is not null and d.itc_eligible is null)
                       or (:flt = 'ELIGIBLE' and d.itc_eligible = true))
                order by coalesce(p.invoice_date, p.txn_date) desc, p.id desc limit :lim
                """).param("b", props.businessId()).param("f", from).param("t", to).param("flt", flt)
                .param("lim", Math.min(Math.max(limit, 1), 500)).query(ItcPurchaseRow.class).list();
    }

    @Transactional
    public ItcPurchaseRow saveItc(long purchaseId, ItcRequest r) {
        CurrentUser.require("gst.prepare");
        long active = jdbc.sql("select count(*) from purchases where id = :id and business_id = :b and void_state = 'ACTIVE'")
                .param("id", purchaseId).param("b", props.businessId()).query(Long.class).single();
        if (active == 0) throw ApiException.notFound("Purchase");
        BigDecimal taxable = money(r.taxableValue());
        BigDecimal cgst = money(r.cgst()), sgst = money(r.sgst()), igst = money(r.igst());
        if (r.taxableValue() == null || taxable.signum() < 0) throw ApiException.badRequest("Taxable value is required and cannot be negative");
        if (cgst.signum() < 0 || sgst.signum() < 0 || igst.signum() < 0) throw ApiException.badRequest("Tax amounts cannot be negative");
        BigDecimal total = cgst.add(sgst).add(igst);
        if (total.compareTo(taxable) > 0) throw ApiException.badRequest("Total tax cannot be more than the taxable value");
        String gstin = r.supplierGstin() == null ? null : r.supplierGstin().trim().toUpperCase();
        if (gstin != null && !gstin.isEmpty() && !GSTIN.matcher(gstin).matches()) {
            throw ApiException.badRequest("Supplier GSTIN must be 15 characters in the usual GSTIN format");
        }
        if (gstin != null && gstin.isEmpty()) gstin = null;

        Integer curVersion = jdbc.sql("select version from purchase_tax_details where purchase_id = :id")
                .param("id", purchaseId).query(Integer.class).optional().orElse(null);
        if (curVersion == null) {
            long id;
            try {
                id = jdbc.sql("""
                        insert into purchase_tax_details (business_id, purchase_id, taxable_value, cgst, sgst, igst, total_tax,
                                                          supplier_gstin, itc_eligible, notes, created_by, updated_by)
                        values (:b, :p, :tv, :c, :s, :i, :tt, :g, :e, :n, :u, :u) returning id
                        """)
                        .param("b", props.businessId()).param("p", purchaseId).param("tv", taxable).param("c", cgst)
                        .param("s", sgst).param("i", igst).param("tt", total).param("g", gstin).param("e", r.itcEligible())
                        .param("n", blankToNull(r.notes())).param("u", CurrentUser.id()).query(Long.class).single();
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("Someone else just entered GST details for this purchase. Reload and try again.");
            }
            audit.record("PURCHASE_TAX_DETAIL", id, "CREATE", null, null, itcSnapshot(id), r.reason(), "MANUAL");
        } else {
            if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason is required to change GST details already entered");
            if (r.version() != null && r.version().intValue() != curVersion.intValue()) {
                throw ApiException.conflict("These GST details were changed by someone else. Reload and try again.");
            }
            long id = jdbc.sql("select id from purchase_tax_details where purchase_id = :p").param("p", purchaseId).query(Long.class).single();
            String before = itcSnapshot(id);
            jdbc.sql("""
                    update purchase_tax_details set taxable_value = :tv, cgst = :c, sgst = :s, igst = :i, total_tax = :tt,
                           supplier_gstin = :g, itc_eligible = :e, notes = :n, version = version + 1,
                           updated_by = :u, updated_at = now()
                    where id = :id
                    """)
                    .param("tv", taxable).param("c", cgst).param("s", sgst).param("i", igst).param("tt", total)
                    .param("g", gstin).param("e", r.itcEligible()).param("n", blankToNull(r.notes()))
                    .param("u", CurrentUser.id()).param("id", id).update();
            audit.record("PURCHASE_TAX_DETAIL", id, "UPDATE", null, before, itcSnapshot(id), r.reason(), "MANUAL");
        }
        return jdbc.sql(ITC_SELECT + " where p.id = :id and p.business_id = :b").param("id", purchaseId)
                .param("b", props.businessId()).query(ItcPurchaseRow.class).single();
    }

    // ------------------------------------------------------------------ helpers

    private String snapshot(long id) {
        return jdbc.sql("select (to_jsonb(w) - 'figures' - 'warnings')::text from gst_workpapers w where w.id = :id")
                .param("id", id).query(String.class).single();
    }

    private String filingSnapshot(long id) {
        return jdbc.sql("select to_jsonb(f)::text from gst_filings f where f.id = :id").param("id", id)
                .query(String.class).single();
    }

    private String itcSnapshot(long id) {
        return jdbc.sql("select to_jsonb(d)::text from purchase_tax_details d where d.id = :id").param("id", id)
                .query(String.class).single();
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise GST figures", e);
        }
    }

    private JsonNode tree(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored GST workpaper JSON is invalid", e);
        }
    }

    private static Warning warn(String code, String severity, String message, List<String> dates) {
        List<String> shown = dates.size() > MAX_WARNING_DATES ? dates.subList(0, MAX_WARNING_DATES) : dates;
        return new Warning(code, severity, message, new ArrayList<>(shown));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return GstCalculator.money(v);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static final String[] MON = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private static String monthShort(LocalDate d) {
        return MON[d.getMonthValue() - 1];
    }

    static String monthLabel(LocalDate d) {
        return MON[d.getMonthValue() - 1] + " " + d.getYear();
    }

    static String quarterLabel(LocalDate qs) {
        LocalDate qe = GstCalculator.quarterEnd(qs);
        return "Q" + GstCalculator.quarterNumber(qs) + " " + GstCalculator.fyLabel(GstCalculator.fyStartYear(qs))
                + " (" + monthShort(qs) + "-" + monthShort(qe) + " " + qe.getYear() + ")";
    }
}
