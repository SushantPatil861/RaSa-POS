package com.cafepos.platform;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDayAndStatusTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void lateNightSaleBelongsToPreviousTradingDay() {
        // 01:30 IST on 5 Oct with a 04:00 rollover is still the 4 Oct trading day
        Instant t = OffsetDateTime.of(2026, 10, 5, 1, 30, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)).toInstant();
        assertThat(BusinessDay.of(t, IST, 4)).isEqualTo(LocalDate.of(2026, 10, 4));
    }

    @Test
    void afterRolloverIsNewDay() {
        Instant t = OffsetDateTime.of(2026, 10, 5, 4, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)).toInstant();
        assertThat(BusinessDay.of(t, IST, 4)).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void backdatedAndCorrectedFlagsAreDerived() {
        OffsetDateTime entry = OffsetDateTime.of(2026, 10, 6, 12, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
        var flags = StatusFlags.of("MANUAL", "CONFIRMED", false, false, 2, LocalDate.of(2026, 10, 4), entry, IST, "ACTIVE");
        assertThat(flags).contains("CONFIRMED", "MANUAL", "BACKDATED", "CORRECTED");
        assertThat(flags).doesNotContain("ESTIMATED", "PARTIAL", "VOIDED");
    }

    @Test
    void sameDayEntryIsNotBackdated() {
        OffsetDateTime entry = OffsetDateTime.of(2026, 10, 4, 12, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
        var flags = StatusFlags.of("MANUAL", "CONFIRMED", true, true, 0, LocalDate.of(2026, 10, 4), entry, IST, "VOIDED");
        assertThat(flags).contains("ESTIMATED", "PARTIAL", "VOIDED").doesNotContain("BACKDATED", "CORRECTED");
    }

    @Test
    void importedSourcesAreLabelledImported() {
        OffsetDateTime entry = OffsetDateTime.of(2026, 10, 4, 12, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
        assertThat(StatusFlags.of("EXCEL", "CONFIRMED", false, false, 0, LocalDate.of(2026, 10, 4), entry, IST, "ACTIVE"))
                .contains("IMPORTED");
    }
}
