package com.cafepos.gst;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GstCalculatorTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void goldenInclusive105At5PercentSplitsIntoCgstSgst() {
        assertThat(GstCalculator.extractTaxable(d("105"), d("5"))).isEqualByComparingTo("100.00");
        assertThat(GstCalculator.extractTax(d("105"), d("5"))).isEqualByComparingTo("5.00");
        BigDecimal[] split = GstCalculator.splitIntraState(d("5.00"));
        assertThat(split[0]).isEqualByComparingTo("2.50");
        assertThat(split[1]).isEqualByComparingTo("2.50");
    }

    @Test
    void oddPaisaGoesToSgstSoTheSplitAlwaysAddsUp() {
        BigDecimal[] split = GstCalculator.splitIntraState(d("5.01"));
        assertThat(split[0].add(split[1])).isEqualByComparingTo("5.01");
        assertThat(split[0]).isEqualByComparingTo("2.51");
        assertThat(split[1]).isEqualByComparingTo("2.50");
    }

    @Test
    void eligibleItcOfRupees3AgainstOutputTax5leavesRupees2Payable() {
        // output 5 (CGST 2.50 + SGST 2.50), eligible ITC 3 (CGST 1.50 + SGST 1.50)
        GstCalculator.ItcSummary itc = GstCalculator.summariseItc(List.of(
                new GstCalculator.ItcInput(d("60"), d("1.50"), d("1.50"), d("0"), true)));
        GstCalculator.Settlement s = GstCalculator.settle(d("2.50"), d("2.50"), GstCalculator.credit(itc.eligible()));
        assertThat(s.payableTotal()).isEqualByComparingTo("2.00");
        assertThat(s.payableCgst()).isEqualByComparingTo("1.00");
        assertThat(s.payableSgst()).isEqualByComparingTo("1.00");
        assertThat(s.carryForwardTotal()).isEqualByComparingTo("0.00");
    }

    @Test
    void undecidedAndIneligibleItcIsNeverCounted() {
        GstCalculator.ItcSummary itc = GstCalculator.summariseItc(List.of(
                new GstCalculator.ItcInput(d("60"), d("1.50"), d("1.50"), d("0"), null),
                new GstCalculator.ItcInput(d("10"), d("0.25"), d("0.25"), d("0"), false)));
        assertThat(itc.eligible().totalTax()).isEqualByComparingTo("0.00");
        assertThat(itc.undecided().totalTax()).isEqualByComparingTo("3.00");
        assertThat(itc.undecided().count()).isEqualTo(1);
        assertThat(itc.ineligible().totalTax()).isEqualByComparingTo("0.50");
        GstCalculator.Settlement s = GstCalculator.settle(d("2.50"), d("2.50"), GstCalculator.credit(itc.eligible()));
        assertThat(s.payableTotal()).isEqualByComparingTo("5.00");
    }

    @Test
    void excessCreditIsCarriedForwardAndPayableFloorsAtZero() {
        GstCalculator.Settlement s = GstCalculator.settle(d("2.50"), d("2.50"),
                new GstCalculator.Credit(d("0"), d("10.00"), d("1.00")));
        assertThat(s.payableCgst()).isEqualByComparingTo("0.00");
        assertThat(s.payableSgst()).isEqualByComparingTo("1.50");
        assertThat(s.carryForward().cgst()).isEqualByComparingTo("7.50");   // CGST credit cannot pay SGST
        assertThat(s.carryForward().sgst()).isEqualByComparingTo("0.00");
    }

    @Test
    void igstCreditPaysCgstThenSgst() {
        GstCalculator.Settlement s = GstCalculator.settle(d("2.50"), d("2.50"),
                new GstCalculator.Credit(d("3.00"), d("0"), d("0")));
        assertThat(s.payableCgst()).isEqualByComparingTo("0.00");
        assertThat(s.payableSgst()).isEqualByComparingTo("2.00");
        assertThat(s.payableTotal()).isEqualByComparingTo("2.00");
    }

    @Test
    void outwardIsGroupedByRateAndUnknownRateIsKeptSeparate() {
        List<GstCalculator.SaleDay> days = List.of(
                new GstCalculator.SaleDay(LocalDate.of(2026, 5, 1), d("5"), d("100.00"), d("5.00")),
                new GstCalculator.SaleDay(LocalDate.of(2026, 5, 2), d("5.000"), d("200.00"), d("10.00")),
                new GstCalculator.SaleDay(LocalDate.of(2026, 5, 3), null, d("50.00"), d("0.00")));
        List<GstCalculator.RateLine> lines = GstCalculator.outwardByRate(days);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).taxable()).isEqualByComparingTo("300.00");
        assertThat(lines.get(0).totalTax()).isEqualByComparingTo("15.00");
        assertThat(lines.get(0).cgst()).isEqualByComparingTo("7.50");
        assertThat(lines.get(0).days()).isEqualTo(2);
        assertThat(lines.get(1).rate() == null).isTrue();
        GstCalculator.OutwardTotals t = GstCalculator.totals(lines);
        assertThat(t.taxable()).isEqualByComparingTo("350.00");
        assertThat(t.totalTax()).isEqualByComparingTo("15.00");
    }

    @Test
    void rateIsPickedByEffectiveDate() {
        List<GstCalculator.RateRule> rules = List.of(
                new GstCalculator.RateRule(LocalDate.of(2026, 5, 1), d("5")),
                new GstCalculator.RateRule(LocalDate.of(2026, 9, 1), d("12")));
        assertThat(GstCalculator.rateOn(rules, LocalDate.of(2026, 4, 30)) == null).isTrue();
        assertThat(GstCalculator.rateOn(rules, LocalDate.of(2026, 8, 31))).isEqualByComparingTo("5");
        assertThat(GstCalculator.rateOn(rules, LocalDate.of(2026, 9, 1))).isEqualByComparingTo("12");
    }

    @Test
    void quarterHelpersFollowIndianFinancialYear() {
        assertThat(GstCalculator.quarterStart(LocalDate.of(2026, 5, 17))).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(GstCalculator.quarterStart(LocalDate.of(2026, 7, 1))).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(GstCalculator.quarterStart(LocalDate.of(2027, 2, 10))).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(GstCalculator.quarterEnd(LocalDate.of(2027, 1, 1))).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(GstCalculator.fyStartYear(LocalDate.of(2027, 3, 31))).isEqualTo(2026);
        assertThat(GstCalculator.fyLabel(2026)).isEqualTo("FY26-27");
        assertThat(GstCalculator.quarterNumber(LocalDate.of(2026, 10, 1))).isEqualTo(3);
        assertThat(GstCalculator.isSelfAssessmentMonth(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1))).isTrue();
        assertThat(GstCalculator.isSelfAssessmentMonth(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 1))).isFalse();
    }
}
