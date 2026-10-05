package com.cafepos.tds;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TdsCalculatorTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static final TdsCalculator.SectionRate C194_OTHER =
            new TdsCalculator.SectionRate("194C", "OTHER", d("2"), d("30000"), d("100000"));
    private static final TdsCalculator.SectionRate C194_IND =
            new TdsCalculator.SectionRate("194C", "INDIVIDUAL_HUF", d("1"), d("30000"), d("100000"));
    private static final TdsCalculator.SectionRate RENT =
            new TdsCalculator.SectionRate("194I(b)", "ANY", d("10"), null, d("600000"));

    @Test
    void singlePaymentAboveThresholdIsDeducted() {
        TdsCalculator.Result r = TdsCalculator.compute(C194_OTHER, d("40000"), d("0"), "ABCDE1234F");
        assertThat(r.thresholdStatus()).isEqualTo(TdsCalculator.DEDUCT);
        assertThat(r.tdsAmount()).isEqualByComparingTo("800.00");
        assertThat(r.panMissing()).isFalse();
    }

    @Test
    void individualRateIsOnePercent() {
        TdsCalculator.Result r = TdsCalculator.compute(C194_IND, d("40000"), d("0"), "ABCDE1234F");
        assertThat(r.tdsAmount()).isEqualByComparingTo("400.00");
    }

    @Test
    void smallPaymentsBelowBothThresholdsDoNotDeduct() {
        TdsCalculator.Result r = TdsCalculator.compute(C194_OTHER, d("20000"), d("50000"), "ABCDE1234F");
        assertThat(r.thresholdStatus()).isEqualTo(TdsCalculator.BELOW_THRESHOLD);
        assertThat(r.tdsAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void annualAggregateCrossingTriggersDeductionAndWarnsAboutEarlierPayments() {
        TdsCalculator.Result r = TdsCalculator.compute(C194_OTHER, d("25000"), d("90000"), "ABCDE1234F");
        assertThat(r.thresholdStatus()).isEqualTo(TdsCalculator.DEDUCT);
        assertThat(r.tdsAmount()).isEqualByComparingTo("500.00");
        assertThat(r.noteText().contains("earlier payments")).isTrue();
    }

    @Test
    void missingPanIsFlaggedButNoHigherRateIsComputed() {
        TdsCalculator.Result r = TdsCalculator.compute(C194_OTHER, d("40000"), d("0"), " ");
        assertThat(r.panMissing()).isTrue();
        assertThat(r.tdsAmount()).isEqualByComparingTo("800.00");
        assertThat(r.noteText().contains("PAN missing: higher rate may apply, ask CA")).isTrue();
    }

    @Test
    void unknownSectionStaysUnknown() {
        TdsCalculator.Result r = TdsCalculator.compute(null, d("40000"), d("0"), "ABCDE1234F");
        assertThat(r.thresholdStatus()).isEqualTo(TdsCalculator.CHECK_WITH_CA);
        assertThat(r.tdsAmount() == null).isTrue();
        assertThat(r.rate() == null).isTrue();
    }

    @Test
    void sectionWithoutAnyThresholdAsksTheCa() {
        TdsCalculator.SectionRate none = new TdsCalculator.SectionRate("194X", "ANY", d("10"), null, null);
        TdsCalculator.Result r = TdsCalculator.compute(none, d("1000"), d("0"), "ABCDE1234F");
        assertThat(r.thresholdStatus()).isEqualTo(TdsCalculator.CHECK_WITH_CA);
        assertThat(r.tdsAmount() == null).isTrue();
    }

    @Test
    void rentUsesAnnualThresholdOnly() {
        assertThat(TdsCalculator.compute(RENT, d("50000"), d("560000"), "ABCDE1234F").thresholdStatus())
                .isEqualTo(TdsCalculator.DEDUCT);
        assertThat(TdsCalculator.compute(RENT, d("50000"), d("0"), "ABCDE1234F").thresholdStatus())
                .isEqualTo(TdsCalculator.BELOW_THRESHOLD);
    }

    @Test
    void depositDueDateIs7thOfNextMonthAnd30AprilForMarch() {
        assertThat(TdsCalculator.depositDueDate(LocalDate.of(2026, 5, 20))).isEqualTo(LocalDate.of(2026, 6, 7));
        assertThat(TdsCalculator.depositDueDate(LocalDate.of(2026, 12, 31))).isEqualTo(LocalDate.of(2027, 1, 7));
        assertThat(TdsCalculator.depositDueDate(LocalDate.of(2027, 3, 1))).isEqualTo(LocalDate.of(2027, 4, 30));
        assertThat(TdsCalculator.depositDueDate(LocalDate.of(2027, 3, 31))).isEqualTo(LocalDate.of(2027, 4, 30));
    }

    @Test
    void financialYearRunsAprilToMarch() {
        assertThat(TdsCalculator.fyStartYear(LocalDate.of(2027, 3, 31))).isEqualTo(2026);
        assertThat(TdsCalculator.fyStartYear(LocalDate.of(2026, 4, 1))).isEqualTo(2026);
        assertThat(TdsCalculator.fyLabel(2026)).isEqualTo("FY26-27");
    }

    @Test
    void panFormatCheck() {
        assertThat(TdsCalculator.looksLikePan("ABCDE1234F")).isTrue();
        assertThat(TdsCalculator.looksLikePan("abcde1234f")).isTrue();
        assertThat(TdsCalculator.looksLikePan("ABCDE12345")).isFalse();
        assertThat(TdsCalculator.looksLikePan(null)).isFalse();
    }
}
