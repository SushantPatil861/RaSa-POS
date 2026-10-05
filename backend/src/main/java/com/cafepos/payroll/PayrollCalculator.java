package com.cafepos.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-free payroll arithmetic. All rounding HALF_UP to 2 decimals.
 * PF / ESI / professional tax are NOT computed: enter them as "other deductions" with a label, as advised by your CA.
 */
public final class PayrollCalculator {

    public static final BigDecimal HOURS_PER_DAY = new BigDecimal("8");
    public static final BigDecimal DEFAULT_OT_MULTIPLIER = new BigDecimal("1.5");

    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private PayrollCalculator() {}

    public record AttendanceDay(LocalDate date, String status, BigDecimal hours, BigDecimal overtimeHours) {}

    /** hoursWorked is null when no marked day carries hours (unknown, not zero). */
    public record Summary(BigDecimal paidDays, BigDecimal hoursWorked, BigDecimal overtimeHours, int markedDays,
                          int unmarkedDays, int presentDaysWithoutHours) {}

    public record Input(String salaryType, BigDecimal baseRate, int daysInMonth, BigDecimal paidDays,
                        BigDecimal hours, BigDecimal overtimeHours, BigDecimal overtimeMultiplier,
                        BigDecimal otherAdditions, BigDecimal otherDeductions, BigDecimal advanceOutstanding,
                        BigDecimal advanceRequested) {}

    public record Result(BigDecimal gross, BigDecimal overtimePay, BigDecimal otherAdditions,
                         BigDecimal netBeforeRecovery, BigDecimal advanceRecovery, BigDecimal otherDeductions,
                         BigDecimal net, boolean deductionsExceededEarnings, Map<String, String> breakdown) {}

    /** PRESENT, PAID_LEAVE, WEEKLY_OFF, HOLIDAY = 1 day; HALF_DAY = 0.5; ABSENT = 0. */
    public static BigDecimal paidValue(String status) {
        if (status == null) return BigDecimal.ZERO;
        return switch (status) {
            case "PRESENT", "PAID_LEAVE", "WEEKLY_OFF", "HOLIDAY" -> BigDecimal.ONE;
            case "HALF_DAY" -> HALF;
            default -> BigDecimal.ZERO;
        };
    }

    /**
     * Summarises marked attendance. The employment window [from, to] (inclusive, within the month) decides which
     * unmarked days are reported as missing; days outside it are neither paid nor flagged.
     */
    public static Summary summarise(List<AttendanceDay> days, LocalDate from, LocalDate to) {
        BigDecimal paid = BigDecimal.ZERO, hours = null, ot = BigDecimal.ZERO;
        int marked = 0, noHours = 0;
        java.util.Set<LocalDate> seen = new java.util.HashSet<>();
        for (AttendanceDay d : days) {
            if (d.date().isBefore(from) || d.date().isAfter(to)) continue;
            seen.add(d.date());
            marked++;
            paid = paid.add(paidValue(d.status()));
            if (d.hours() != null) {
                hours = (hours == null ? BigDecimal.ZERO : hours).add(d.hours());
            } else if ("PRESENT".equals(d.status()) || "HALF_DAY".equals(d.status())) {
                noHours++;
            }
            if (d.overtimeHours() != null) ot = ot.add(d.overtimeHours());
        }
        int window = (int) (to.toEpochDay() - from.toEpochDay() + 1);
        return new Summary(paid, hours, ot, marked, Math.max(window - seen.size(), 0), noHours);
    }

    /** Hourly-equivalent used for overtime: MONTHLY base / days / 8, DAILY rate / 8, HOURLY rate. */
    public static BigDecimal hourlyEquivalent(String salaryType, BigDecimal baseRate, int daysInMonth) {
        return switch (salaryType) {
            case "MONTHLY" -> baseRate.divide(BigDecimal.valueOf(daysInMonth), 10, RoundingMode.HALF_UP)
                    .divide(HOURS_PER_DAY, 10, RoundingMode.HALF_UP);
            case "DAILY" -> baseRate.divide(HOURS_PER_DAY, 10, RoundingMode.HALF_UP);
            case "HOURLY" -> baseRate;
            default -> throw new IllegalArgumentException("Unknown salary type " + salaryType);
        };
    }

    public static Result calculate(Input in) {
        Map<String, String> b = new LinkedHashMap<>();
        BigDecimal paidDays = nz(in.paidDays());
        BigDecimal gross;
        switch (in.salaryType()) {
            case "MONTHLY" -> {
                BigDecimal capped = paidDays.min(BigDecimal.valueOf(in.daysInMonth()));
                gross = in.baseRate().multiply(capped)
                        .divide(BigDecimal.valueOf(in.daysInMonth()), 2, RoundingMode.HALF_UP);
                b.put("gross", money(in.baseRate()) + " / " + in.daysInMonth() + " days x " + capped.stripTrailingZeros().toPlainString() + " paid days");
            }
            case "DAILY" -> {
                gross = in.baseRate().multiply(paidDays).setScale(2, RoundingMode.HALF_UP);
                b.put("gross", money(in.baseRate()) + " per day x " + paidDays.stripTrailingZeros().toPlainString() + " paid days");
            }
            case "HOURLY" -> {
                BigDecimal h = nz(in.hours());
                gross = in.baseRate().multiply(h).setScale(2, RoundingMode.HALF_UP);
                b.put("gross", money(in.baseRate()) + " per hour x " + h.stripTrailingZeros().toPlainString() + " hours");
            }
            default -> throw new IllegalArgumentException("Unknown salary type " + in.salaryType());
        }

        BigDecimal otHours = nz(in.overtimeHours());
        BigDecimal mult = in.overtimeMultiplier() == null ? DEFAULT_OT_MULTIPLIER : in.overtimeMultiplier();
        BigDecimal otPay = ZERO;
        if (otHours.signum() > 0) {
            BigDecimal eq = hourlyEquivalent(in.salaryType(), in.baseRate(), in.daysInMonth());
            otPay = eq.multiply(mult).multiply(otHours).setScale(2, RoundingMode.HALF_UP);
            b.put("overtime", otHours.stripTrailingZeros().toPlainString() + " h x " + mult.stripTrailingZeros().toPlainString()
                    + " x hourly-equivalent " + eq.setScale(4, RoundingMode.HALF_UP));
        }

        BigDecimal add = money(in.otherAdditions());
        BigDecimal ded = money(in.otherDeductions());
        BigDecimal earnings = gross.add(otPay).add(add);
        BigDecimal before = earnings.subtract(ded);
        boolean exceeded = before.signum() < 0;
        if (exceeded) {
            before = ZERO;
            b.put("deductionsCapped", "Other deductions were more than earnings; net before advance recovery is held at 0.00");
        }

        BigDecimal requested = money(in.advanceRequested());
        BigDecimal outstanding = money(in.advanceOutstanding());
        BigDecimal recovery = requested.min(outstanding).min(before);
        if (recovery.signum() < 0) recovery = ZERO;
        if (recovery.compareTo(requested) < 0) {
            b.put("advanceRecovery", "Requested " + requested + " but limited to " + recovery
                    + " (cannot exceed outstanding " + outstanding + " or net pay " + before + ")");
        }
        BigDecimal net = before.subtract(recovery);
        return new Result(gross, otPay, add, before, recovery, ded, net, exceeded, b);
    }

    /** Spreads recovered money over advances oldest-first; returns each advance's outstanding balance (same order). */
    public static List<BigDecimal> allocateFifo(List<BigDecimal> advanceAmounts, BigDecimal recovered) {
        BigDecimal left = nz(recovered);
        List<BigDecimal> out = new ArrayList<>();
        for (BigDecimal a : advanceAmounts) {
            BigDecimal take = a.min(left);
            left = left.subtract(take);
            out.add(a.subtract(take));
        }
        return out;
    }

    public static BigDecimal money(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
