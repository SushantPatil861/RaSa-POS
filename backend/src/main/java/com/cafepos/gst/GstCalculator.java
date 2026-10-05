package com.cafepos.gst;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-free GST workpaper arithmetic (QRMP, regular scheme, intra-state B2C sales).
 * Everything here is an ESTIMATE for the owner's CA: CGST/SGST are split 50:50 (assumption), all sales are treated
 * as B2C intra-state supplies, and ITC is counted only where it was explicitly marked eligible.
 */
public final class GstCalculator {

    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private GstCalculator() {}

    // ------------------------------------------------------------------ small value types

    /** One trading day's outward figures as stored (ex-GST taxable value and tax) with the rate in force that day. */
    public record SaleDay(LocalDate date, BigDecimal rate, BigDecimal netTaxable, BigDecimal taxAmount) {}

    public record RateRule(LocalDate effectiveFrom, BigDecimal rate) {}

    /** Outward supplies at one rate. rate == null means "no GST rule found for those days" (unknown, never guessed). */
    public record RateLine(BigDecimal rate, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal totalTax,
                           int days) {}

    public record OutwardTotals(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal totalTax, int days) {}

    public record ItcInput(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, Boolean eligible) {}

    public record ItcTotals(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal totalTax,
                            int count) {}

    /** eligible = explicitly true; undecided = NULL ("ask the CA", NOT counted); ineligible = explicitly false. */
    public record ItcSummary(ItcTotals eligible, ItcTotals undecided, ItcTotals ineligible) {}

    /** Credit balances available per tax head. */
    public record Credit(BigDecimal igst, BigDecimal cgst, BigDecimal sgst) {
        public BigDecimal total() {
            return igst.add(cgst).add(sgst);
        }
    }

    /** Result of setting off eligible ITC against output tax. Cash payable is never negative. */
    public record Settlement(BigDecimal outputCgst, BigDecimal outputSgst, BigDecimal itcUsed,
                             BigDecimal payableCgst, BigDecimal payableSgst, BigDecimal payableTotal,
                             Credit carryForward, BigDecimal carryForwardTotal) {}

    // ------------------------------------------------------------------ rounding / tax maths

    public static BigDecimal money(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Taxable value inside a GST-inclusive amount: inclusive x 100 / (100 + rate). 105 at 5% -> 100.00 */
    public static BigDecimal extractTaxable(BigDecimal inclusive, BigDecimal ratePct) {
        return inclusive.multiply(HUNDRED).divide(HUNDRED.add(ratePct), 2, RoundingMode.HALF_UP);
    }

    /** GST inside a GST-inclusive amount. 105 at 5% -> 5.00 */
    public static BigDecimal extractTax(BigDecimal inclusive, BigDecimal ratePct) {
        return money(inclusive).subtract(extractTaxable(inclusive, ratePct));
    }

    /** Tax on an ex-GST taxable value. 100 at 5% -> 5.00 */
    public static BigDecimal taxOn(BigDecimal taxable, BigDecimal ratePct) {
        return taxable.multiply(ratePct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** Intra-state split: CGST = half (rounded half-up), SGST = the rest, so the two always add up to the tax. */
    public static BigDecimal[] splitIntraState(BigDecimal tax) {
        BigDecimal t = money(tax);
        BigDecimal cgst = t.divide(TWO, 2, RoundingMode.HALF_UP);
        return new BigDecimal[] {cgst, t.subtract(cgst)};
    }

    /** Rate (percent) in force on a date from effective-dated rules, or null when no rule applies yet. */
    public static BigDecimal rateOn(List<RateRule> rules, LocalDate date) {
        RateRule best = null;
        for (RateRule r : rules) {
            if (!r.effectiveFrom().isAfter(date) && (best == null || r.effectiveFrom().isAfter(best.effectiveFrom()))) {
                best = r;
            }
        }
        return best == null ? null : best.rate();
    }

    // ------------------------------------------------------------------ outward supplies

    /** Groups days by rate (null rate last). Tax is the RECORDED tax; CGST/SGST is split per rate line. */
    public static List<RateLine> outwardByRate(List<SaleDay> days) {
        Map<String, BigDecimal[]> acc = new LinkedHashMap<>();   // key -> [rate, taxable, tax, days]
        for (SaleDay d : days) {
            String key = d.rate() == null ? "null" : d.rate().stripTrailingZeros().toPlainString();
            BigDecimal[] a = acc.computeIfAbsent(key,
                    k -> new BigDecimal[] {d.rate(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            a[1] = a[1].add(nz(d.netTaxable()));
            a[2] = a[2].add(nz(d.taxAmount()));
            a[3] = a[3].add(BigDecimal.ONE);
        }
        List<RateLine> out = new ArrayList<>();
        for (BigDecimal[] a : acc.values()) {
            BigDecimal[] split = splitIntraState(a[2]);
            out.add(new RateLine(a[0], money(a[1]), split[0], split[1], money(a[2]), a[3].intValue()));
        }
        out.sort(Comparator.comparing(RateLine::rate, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    public static OutwardTotals totals(List<RateLine> lines) {
        BigDecimal taxable = ZERO, cgst = ZERO, sgst = ZERO, tax = ZERO;
        int days = 0;
        for (RateLine l : lines) {
            taxable = taxable.add(l.taxable());
            cgst = cgst.add(l.cgst());
            sgst = sgst.add(l.sgst());
            tax = tax.add(l.totalTax());
            days += l.days();
        }
        return new OutwardTotals(taxable, cgst, sgst, tax, days);
    }

    // ------------------------------------------------------------------ ITC

    public static ItcSummary summariseItc(List<ItcInput> rows) {
        BigDecimal[] el = new BigDecimal[5], un = new BigDecimal[5], in = new BigDecimal[5];
        for (int i = 0; i < 5; i++) {
            el[i] = BigDecimal.ZERO;
            un[i] = BigDecimal.ZERO;
            in[i] = BigDecimal.ZERO;
        }
        for (ItcInput r : rows) {
            BigDecimal[] t = r.eligible() == null ? un : (r.eligible() ? el : in);
            t[0] = t[0].add(nz(r.taxable()));
            t[1] = t[1].add(nz(r.cgst()));
            t[2] = t[2].add(nz(r.sgst()));
            t[3] = t[3].add(nz(r.igst()));
            t[4] = t[4].add(BigDecimal.ONE);
        }
        return new ItcSummary(toTotals(el), toTotals(un), toTotals(in));
    }

    private static ItcTotals toTotals(BigDecimal[] t) {
        BigDecimal cgst = money(t[1]), sgst = money(t[2]), igst = money(t[3]);
        return new ItcTotals(money(t[0]), cgst, sgst, igst, cgst.add(sgst).add(igst), t[4].intValue());
    }

    public static Credit credit(ItcTotals eligible) {
        return new Credit(eligible.igst(), eligible.cgst(), eligible.sgst());
    }

    public static Credit zeroCredit() {
        return new Credit(ZERO, ZERO, ZERO);
    }

    public static Credit addCredit(Credit a, Credit b) {
        return new Credit(a.igst().add(b.igst()), a.cgst().add(b.cgst()), a.sgst().add(b.sgst()));
    }

    /**
     * Sets off credit against intra-state output tax in the statutory order: IGST credit first (CGST, then SGST),
     * then CGST credit against CGST, then SGST credit against SGST. CGST credit can never pay SGST.
     * Anything left over is carried forward; payable never goes below zero.
     */
    public static Settlement settle(BigDecimal outputCgst, BigDecimal outputSgst, Credit pool) {
        BigDecimal c = money(outputCgst), s = money(outputSgst);
        BigDecimal i = pool.igst(), cc = pool.cgst(), sc = pool.sgst();
        BigDecimal used = ZERO;

        BigDecimal x = i.min(c);          // IGST credit -> CGST output
        i = i.subtract(x); c = c.subtract(x); used = used.add(x);
        x = i.min(s);                     // remaining IGST credit -> SGST output
        i = i.subtract(x); s = s.subtract(x); used = used.add(x);
        x = cc.min(c);                    // CGST credit -> CGST output
        cc = cc.subtract(x); c = c.subtract(x); used = used.add(x);
        x = sc.min(s);                    // SGST credit -> SGST output
        sc = sc.subtract(x); s = s.subtract(x); used = used.add(x);

        Credit carry = new Credit(money(i), money(cc), money(sc));
        return new Settlement(money(outputCgst), money(outputSgst), money(used), c, s, c.add(s), carry, carry.total());
    }

    // ------------------------------------------------------------------ periods (Indian financial year)

    /** First day of the GST quarter (Apr, Jul, Oct, Jan) containing the date. */
    public static LocalDate quarterStart(LocalDate d) {
        int m = d.getMonthValue();
        int startMonth = m >= 4 ? 4 + ((m - 4) / 3) * 3 : 1;
        return LocalDate.of(d.getYear(), startMonth, 1);
    }

    public static boolean isQuarterStart(LocalDate d) {
        return d.getDayOfMonth() == 1 && quarterStart(d).equals(d);
    }

    public static LocalDate quarterEnd(LocalDate quarterStart) {
        return YearMonth.from(quarterStart).plusMonths(2).atEndOfMonth();
    }

    /** Calendar year in which the financial year containing the date starts (Apr-Mar). */
    public static int fyStartYear(LocalDate d) {
        return d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
    }

    /** "FY26-27" */
    public static String fyLabel(int fyStartYear) {
        return String.format("FY%02d-%02d", fyStartYear % 100, (fyStartYear + 1) % 100);
    }

    /** 1..4 (Apr-Jun = Q1). */
    public static int quarterNumber(LocalDate quarterStart) {
        return switch (quarterStart.getMonthValue()) {
            case 4 -> 1;
            case 7 -> 2;
            case 10 -> 3;
            default -> 4;
        };
    }

    /** First two months of a quarter are QRMP payment (PMT-06) months; the third is settled in GSTR-3B. */
    public static boolean isSelfAssessmentMonth(LocalDate quarterStart, LocalDate monthStart) {
        return monthStart.isBefore(YearMonth.from(quarterStart).plusMonths(2).atDay(1));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
