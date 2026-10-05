package com.cafepos.hygiene;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HygieneMathTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void rangeIsInclusiveAtBothEnds() {
        assertThat(HygieneMath.inRange(d("-18"), d("-22"), d("-16"))).isTrue();
        assertThat(HygieneMath.inRange(d("-16.0"), d("-22"), d("-16"))).isTrue();
        assertThat(HygieneMath.inRange(d("-15.9"), d("-22"), d("-16"))).isFalse();
        assertThat(HygieneMath.inRange(d("5.1"), d("0"), d("5"))).isFalse();
    }

    @Test
    void dailyMissedDaysSkipClosedDays() {
        HygieneMath.Coverage c = HygieneMath.coverage("DAILY", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5),
                Set.of(LocalDate.of(2026, 10, 2)), List.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4)));
        assertThat(c.expected()).isEqualTo(4);
        assertThat(c.completed()).isEqualTo(2);
        assertThat(c.missed()).containsExactly(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 5));
        assertThat(HygieneMath.percent(c.completed(), c.expected())).isEqualByComparingTo("50.0");
    }

    @Test
    void weeklyJudgesOnlyFullMondayToSundayWeeks() {
        // 5 Oct 2026 is a Monday. Range 1-Oct..25-Oct holds full weeks 5-11, 12-18, 19-25.
        HygieneMath.Coverage c = HygieneMath.coverage("WEEKLY", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 25),
                Set.of(), List.of(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 20)));
        assertThat(c.expected()).isEqualTo(3);
        assertThat(c.completed()).isEqualTo(2);
        assertThat(c.missed()).containsExactly(LocalDate.of(2026, 10, 12));
    }

    @Test
    void monthlyJudgesOnlyFullMonths() {
        HygieneMath.Coverage c = HygieneMath.coverage("MONTHLY", LocalDate.of(2026, 9, 15), LocalDate.of(2026, 11, 30),
                Set.of(), List.of(LocalDate.of(2026, 11, 3)));
        assertThat(c.expected()).isEqualTo(2);   // October and November
        assertThat(c.missed()).containsExactly(LocalDate.of(2026, 10, 1));
    }

    @Test
    void nothingExpectedMeansUnknownPercentNotHundred() {
        assertThat(HygieneMath.percent(0, 0) == null).isTrue();
    }
}
