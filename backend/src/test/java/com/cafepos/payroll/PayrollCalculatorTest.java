package com.cafepos.payroll;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PayrollCalculatorTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static PayrollCalculator.Input in(String type, String rate, int days, String paid, String hours, String ot,
                                              String adds, String deds, String outstanding, String requested) {
        return new PayrollCalculator.Input(type, d(rate), days, d(paid), hours == null ? null : d(hours), d(ot), null,
                d(adds), d(deds), d(outstanding), d(requested));
    }

    @Test
    void monthlyIsBaseOverCalendarDaysTimesPaidDays() {
        // April has 30 days: 30000 / 30 x 26.5 = 26500.00
        PayrollCalculator.Result r = PayrollCalculator.calculate(in("MONTHLY", "30000", 30, "26.5", null, "0", "0", "0", "0", "0"));
        assertThat(r.gross()).isEqualByComparingTo("26500.00");
        assertThat(r.net()).isEqualByComparingTo("26500.00");
    }

    @Test
    void monthlyRoundsHalfUpToTwoDecimals() {
        // 10000 / 31 x 25 = 8064.516... -> 8064.52
        PayrollCalculator.Result r = PayrollCalculator.calculate(in("MONTHLY", "10000", 31, "25", null, "0", "0", "0", "0", "0"));
        assertThat(r.gross()).isEqualByComparingTo("8064.52");
    }

    @Test
    void dailyAndHourly() {
        assertThat(PayrollCalculator.calculate(in("DAILY", "500", 30, "24.5", null, "0", "0", "0", "0", "0")).gross())
                .isEqualByComparingTo("12250.00");
        assertThat(PayrollCalculator.calculate(in("HOURLY", "60", 30, "0", "182.5", "0", "0", "0", "0", "0")).gross())
                .isEqualByComparingTo("10950.00");
    }

    @Test
    void overtimeIsOnePointFiveTimesHourlyEquivalent() {
        // Feb 2026 = 28 days: 15000 / 28 / 8 x 1.5 x 2 = 200.89
        PayrollCalculator.Result r = PayrollCalculator.calculate(in("MONTHLY", "15000", 28, "28", null, "2", "0", "0", "0", "0"));
        assertThat(r.gross()).isEqualByComparingTo("15000.00");
        assertThat(r.overtimePay()).isEqualByComparingTo("200.89");
        assertThat(r.net()).isEqualByComparingTo("15200.89");
        // DAILY 800/day: 800 / 8 x 1.5 x 3 = 450.00 ; HOURLY 100: 100 x 1.5 x 2 = 300.00
        assertThat(PayrollCalculator.calculate(in("DAILY", "800", 30, "1", null, "3", "0", "0", "0", "0")).overtimePay())
                .isEqualByComparingTo("450.00");
        assertThat(PayrollCalculator.calculate(in("HOURLY", "100", 30, "0", "8", "2", "0", "0", "0", "0")).overtimePay())
                .isEqualByComparingTo("300.00");
    }

    @Test
    void perEmployeeOvertimeMultiplierOverridesTheDefault() {
        PayrollCalculator.Input i = new PayrollCalculator.Input("HOURLY", d("100"), 30, d("0"), d("8"), d("2"), d("2"),
                d("0"), d("0"), d("0"), d("0"));
        assertThat(PayrollCalculator.calculate(i).overtimePay()).isEqualByComparingTo("400.00");
    }

    @Test
    void advanceRecoveryIsCappedByOutstandingAndByNetPay() {
        // net before recovery 1000: requested 3000 but outstanding 2000 and net 1000 -> 1000
        PayrollCalculator.Result r = PayrollCalculator.calculate(in("MONTHLY", "1000", 30, "30", null, "0", "0", "0", "2000", "3000"));
        assertThat(r.advanceRecovery()).isEqualByComparingTo("1000.00");
        assertThat(r.net()).isEqualByComparingTo("0.00");
        // outstanding smaller than requested
        PayrollCalculator.Result r2 = PayrollCalculator.calculate(in("MONTHLY", "10000", 30, "30", null, "0", "0", "0", "500", "2000"));
        assertThat(r2.advanceRecovery()).isEqualByComparingTo("500.00");
        assertThat(r2.net()).isEqualByComparingTo("9500.00");
    }

    @Test
    void otherAdditionsAndDeductionsAreFreeLines() {
        PayrollCalculator.Result r = PayrollCalculator.calculate(in("MONTHLY", "10000", 30, "30", null, "0", "500", "200", "0", "0"));
        assertThat(r.net()).isEqualByComparingTo("10300.00");
        PayrollCalculator.Result r2 = PayrollCalculator.calculate(in("MONTHLY", "100", 30, "30", null, "0", "0", "500", "0", "0"));
        assertThat(r2.net()).isEqualByComparingTo("0.00");
        assertThat(r2.deductionsExceededEarnings()).isTrue();
    }

    @Test
    void attendanceCountsPaidDaysAndHalfDays() {
        List<PayrollCalculator.AttendanceDay> days = new ArrayList<>();
        days.add(new PayrollCalculator.AttendanceDay(LocalDate.of(2026, 9, 1), "PRESENT", d("9"), d("1")));
        days.add(new PayrollCalculator.AttendanceDay(LocalDate.of(2026, 9, 2), "HALF_DAY", d("4"), d("0")));
        days.add(new PayrollCalculator.AttendanceDay(LocalDate.of(2026, 9, 3), "ABSENT", null, null));
        days.add(new PayrollCalculator.AttendanceDay(LocalDate.of(2026, 9, 4), "WEEKLY_OFF", null, null));
        days.add(new PayrollCalculator.AttendanceDay(LocalDate.of(2026, 9, 5), "PAID_LEAVE", null, null));
        days.add(new PayrollCalculator.AttendanceDay(LocalDate.of(2026, 9, 6), "HOLIDAY", null, null));
        days.add(new PayrollCalculator.AttendanceDay(LocalDate.of(2026, 9, 7), "PRESENT", null, null));
        PayrollCalculator.Summary s = PayrollCalculator.summarise(days, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertThat(s.paidDays()).isEqualByComparingTo("5.5");
        assertThat(s.hoursWorked()).isEqualByComparingTo("13");
        assertThat(s.overtimeHours()).isEqualByComparingTo("1");
        assertThat(s.markedDays()).isEqualTo(7);
        assertThat(s.unmarkedDays()).isEqualTo(23);
        assertThat(s.presentDaysWithoutHours()).isEqualTo(1);
    }

    @Test
    void noHoursAtAllStaysUnknown() {
        List<PayrollCalculator.AttendanceDay> days = List.of(
                new PayrollCalculator.AttendanceDay(LocalDate.of(2026, 9, 1), "PRESENT", null, null));
        PayrollCalculator.Summary s = PayrollCalculator.summarise(days, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertThat(s.hoursWorked() == null).isTrue();
    }

    @Test
    void advancesAreRecoveredOldestFirst() {
        List<BigDecimal> out = PayrollCalculator.allocateFifo(List.of(d("1000"), d("2000")), d("1500"));
        assertThat(out.get(0)).isEqualByComparingTo("0");
        assertThat(out.get(1)).isEqualByComparingTo("1500");
    }
}
