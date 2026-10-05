package com.cafepos.compliance;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Framework-free generator of compliance due dates from rules (no scheduler: the service calls this on demand and
 * inserts the tasks idempotently, keyed by rule code + period start).
 *
 * Period model: MONTHLY = a calendar month; QUARTERLY = Apr-Jun / Jul-Sep / Oct-Dec / Jan-Mar anchored on the quarter-END
 * month; ANNUAL = due_day of due_month every year. due date = due_day of (period month + monthOffset), clamped to month length.
 * If the period month equals overridePeriodMonth the override offset / day replace the normal ones.
 * ONE_TIME rules are never generated (use manual tasks).
 */
public final class ComplianceCalendarGenerator {

    private static final String[] MON = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private ComplianceCalendarGenerator() {}

    public record Rule(String code, String title, String category, int dueDay, String frequency, int monthOffset,
                       List<Integer> appliesMonths, Integer dueMonth, Integer overridePeriodMonth,
                       Integer overrideMonthOffset, Integer overrideDueDay, String link) {}

    public record Task(String ruleCode, LocalDate periodStart, String periodLabel, String title, String category,
                       LocalDate dueDate, String link) {}

    /**
     * @param earliestPeriodEnd periods that ended before this date are skipped (the business did not exist yet); null = no limit
     */
    public static List<Task> generate(List<Rule> rules, LocalDate from, LocalDate to, LocalDate earliestPeriodEnd) {
        List<Task> out = new ArrayList<>();
        YearMonth first = YearMonth.from(from).minusMonths(15);
        YearMonth last = YearMonth.from(to);
        for (Rule r : rules) {
            switch (r.frequency()) {
                case "MONTHLY" -> {
                    for (YearMonth ym = first; !ym.isAfter(last); ym = ym.plusMonths(1)) {
                        if (r.appliesMonths() != null && !r.appliesMonths().isEmpty()
                                && !r.appliesMonths().contains(ym.getMonthValue())) continue;
                        add(out, r, ym.atDay(1), ym.atEndOfMonth(), monthLabel(ym), dueDate(r, ym), from, to, earliestPeriodEnd);
                    }
                }
                case "QUARTERLY" -> {
                    for (YearMonth ym = first; !ym.isAfter(last); ym = ym.plusMonths(1)) {
                        int m = ym.getMonthValue();
                        if (m != 3 && m != 6 && m != 9 && m != 12) continue;
                        add(out, r, ym.minusMonths(2).atDay(1), ym.atEndOfMonth(), quarterLabel(ym), dueDate(r, ym), from, to, earliestPeriodEnd);
                    }
                }
                case "ANNUAL" -> {
                    if (r.dueMonth() == null) break;
                    for (int y = from.getYear() - 1; y <= to.getYear(); y++) {
                        YearMonth ym = YearMonth.of(y, r.dueMonth());
                        LocalDate due = ym.atDay(Math.min(r.dueDay(), ym.lengthOfMonth()));
                        // an annual obligation is judged by its due date only
                        add(out, r, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), "Due " + y, due, from, to, null);
                    }
                }
                default -> { /* ONE_TIME: manual tasks only */ }
            }
        }
        out.sort(Comparator.comparing(Task::dueDate).thenComparing(Task::ruleCode));
        return out;
    }

    private static void add(List<Task> out, Rule r, LocalDate periodStart, LocalDate periodEnd, String label, LocalDate due,
                            LocalDate from, LocalDate to, LocalDate earliestPeriodEnd) {
        if (due.isBefore(from) || due.isAfter(to)) return;
        if (earliestPeriodEnd != null && periodEnd.isBefore(earliestPeriodEnd)) return;
        out.add(new Task(r.code(), periodStart, label, r.title() + " - " + label, r.category(), due, r.link()));
    }

    /** Due date for the period whose (end) month is ym. */
    public static LocalDate dueDate(Rule r, YearMonth ym) {
        int offset = r.monthOffset();
        int day = r.dueDay();
        if (r.overridePeriodMonth() != null && r.overridePeriodMonth() == ym.getMonthValue()) {
            if (r.overrideMonthOffset() != null) offset = r.overrideMonthOffset();
            if (r.overrideDueDay() != null) day = r.overrideDueDay();
        }
        YearMonth due = ym.plusMonths(offset);
        return due.atDay(Math.min(day, due.lengthOfMonth()));
    }

    public static String monthLabel(YearMonth ym) {
        return MON[ym.getMonthValue() - 1] + " " + ym.getYear();
    }

    /** quarterEnd is the last month of the quarter. "Q1 FY26-27 (Apr-Jun 2026)". */
    public static String quarterLabel(YearMonth quarterEnd) {
        int m = quarterEnd.getMonthValue();
        int fyStart = m >= 4 ? quarterEnd.getYear() : quarterEnd.getYear() - 1;
        int q = switch (m) {
            case 6 -> 1;
            case 9 -> 2;
            case 12 -> 3;
            default -> 4;
        };
        YearMonth start = quarterEnd.minusMonths(2);
        return String.format("Q%d FY%02d-%02d (%s-%s %d)", q, fyStart % 100, (fyStart + 1) % 100,
                MON[start.getMonthValue() - 1], MON[m - 1], quarterEnd.getYear());
    }

    /** ACTIVE (or no expiry recorded), EXPIRING (within reminderDays), EXPIRED (before today). */
    public static String expiryStatus(LocalDate expiresOn, int reminderDays, LocalDate today) {
        if (expiresOn == null) return "ACTIVE";
        if (expiresOn.isBefore(today)) return "EXPIRED";
        if (!expiresOn.isAfter(today.plusDays(reminderDays))) return "EXPIRING";
        return "ACTIVE";
    }
}
