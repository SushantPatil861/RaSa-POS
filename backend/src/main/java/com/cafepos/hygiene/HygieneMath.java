package com.cafepos.hygiene;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Framework-free hygiene / temperature rules. */
public final class HygieneMath {

    private HygieneMath() {}

    /** A reading is in range when min <= temp <= max (both ends inclusive). */
    public static boolean inRange(BigDecimal temp, BigDecimal min, BigDecimal max) {
        return temp.compareTo(min) >= 0 && temp.compareTo(max) <= 0;
    }

    /** Result of judging one checklist over a date range. missed holds the start date of each missed period. */
    public record Coverage(int expected, int completed, List<LocalDate> missed) {
        public int missedCount() {
            return expected - completed;
        }
    }

    /**
     * DAILY: every date in [from, to] that is not excluded needs a completion on that date.
     * WEEKLY: every Monday-Sunday week lying fully inside [from, to] needs at least one completion in it.
     * MONTHLY: every calendar month lying fully inside [from, to] needs at least one completion in it.
     * Weekly / monthly periods that are only partly inside the range are not judged (no false "missed").
     * Excluded days (closed / holiday) are never expected for DAILY checks.
     */
    public static Coverage coverage(String frequency, LocalDate from, LocalDate to, Set<LocalDate> excludedDays,
                                    Collection<LocalDate> completedDates) {
        List<LocalDate> missed = new ArrayList<>();
        int expected = 0, completed = 0;
        if (to.isBefore(from)) return new Coverage(0, 0, missed);
        switch (frequency) {
            case "DAILY" -> {
                Set<LocalDate> done = Set.copyOf(completedDates);
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    if (excludedDays.contains(d)) continue;
                    expected++;
                    if (done.contains(d)) completed++; else missed.add(d);
                }
            }
            case "WEEKLY" -> {
                LocalDate monday = from.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
                for (; !monday.plusDays(6).isAfter(to); monday = monday.plusDays(7)) {
                    expected++;
                    if (anyBetween(completedDates, monday, monday.plusDays(6))) completed++; else missed.add(monday);
                }
            }
            case "MONTHLY" -> {
                YearMonth ym = from.getDayOfMonth() == 1 ? YearMonth.from(from) : YearMonth.from(from).plusMonths(1);
                for (; !ym.atEndOfMonth().isAfter(to); ym = ym.plusMonths(1)) {
                    expected++;
                    if (anyBetween(completedDates, ym.atDay(1), ym.atEndOfMonth())) completed++; else missed.add(ym.atDay(1));
                }
            }
            default -> throw new IllegalArgumentException("Unknown frequency " + frequency);
        }
        return new Coverage(expected, completed, missed);
    }

    private static boolean anyBetween(Collection<LocalDate> dates, LocalDate from, LocalDate to) {
        for (LocalDate d : dates) {
            if (!d.isBefore(from) && !d.isAfter(to)) return true;
        }
        return false;
    }

    /** Percentage with 1 decimal, or null when nothing was expected (unknown, not 100%). */
    public static BigDecimal percent(int completed, int expected) {
        if (expected <= 0) return null;
        return BigDecimal.valueOf(completed * 100L).divide(BigDecimal.valueOf(expected), 1, RoundingMode.HALF_UP);
    }
}
