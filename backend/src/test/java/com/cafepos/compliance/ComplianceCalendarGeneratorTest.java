package com.cafepos.compliance;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceCalendarGeneratorTest {

    private static final ComplianceCalendarGenerator.Rule GSTR1 = new ComplianceCalendarGenerator.Rule(
            "GSTR1_QRMP", "GSTR-1 (quarterly)", "GST", 13, "QUARTERLY", 1, null, null, null, null, null, "/gst");
    private static final ComplianceCalendarGenerator.Rule GSTR3B = new ComplianceCalendarGenerator.Rule(
            "GSTR3B_QRMP", "GSTR-3B (quarterly)", "GST", 22, "QUARTERLY", 1, null, null, null, null, null, "/gst");
    private static final ComplianceCalendarGenerator.Rule PMT06 = new ComplianceCalendarGenerator.Rule(
            "PMT06_QRMP", "PMT-06", "GST", 25, "MONTHLY", 1, List.of(1, 2, 4, 5, 7, 8, 10, 11), null, null, null, null, "/gst");
    private static final ComplianceCalendarGenerator.Rule TDS_DEP = new ComplianceCalendarGenerator.Rule(
            "TDS_DEPOSIT", "TDS deposit", "TDS", 7, "MONTHLY", 1, null, null, 3, 1, 30, "/tds");
    private static final ComplianceCalendarGenerator.Rule TDS_RET = new ComplianceCalendarGenerator.Rule(
            "TDS_RETURN_QTR", "TDS return", "TDS", 31, "QUARTERLY", 1, null, null, 3, 2, 31, "/tds");
    private static final ComplianceCalendarGenerator.Rule GSTR9 = new ComplianceCalendarGenerator.Rule(
            "GSTR9_ANNUAL", "GSTR-9", "GST", 31, "ANNUAL", 0, null, 12, null, null, null, "/gst");

    private static List<ComplianceCalendarGenerator.Task> gen(ComplianceCalendarGenerator.Rule r, LocalDate from, LocalDate to) {
        return ComplianceCalendarGenerator.generate(List.of(r), from, to, null);
    }

    @Test
    void goldenQ1Fy2627Gstr1IsDue13Jul2026() {
        List<ComplianceCalendarGenerator.Task> t = gen(GSTR1, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        assertThat(t).hasSize(1);
        assertThat(t.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(t.get(0).periodStart()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(t.get(0).periodLabel()).isEqualTo("Q1 FY26-27 (Apr-Jun 2026)");
    }

    @Test
    void gstr3bIs22ndAndAllFourQuartersOfTheYearAreCovered() {
        List<ComplianceCalendarGenerator.Task> t = gen(GSTR3B, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        assertThat(t).hasSize(4);   // Jul, Oct, Jan, plus Apr (Q4 FY25-26 due 22-Apr-2026)
        assertThat(t.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 4, 22));
        assertThat(t.get(1).dueDate()).isEqualTo(LocalDate.of(2026, 7, 22));
        assertThat(t.get(2).dueDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(t.get(3).dueDate()).isEqualTo(LocalDate.of(2027, 1, 22));
    }

    @Test
    void periodsEndedBeforeTheBusinessStartedAreSkipped() {
        List<ComplianceCalendarGenerator.Task> t = ComplianceCalendarGenerator.generate(List.of(GSTR3B),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 12, 31), LocalDate.of(2026, 5, 1));
        assertThat(t).hasSize(2);   // Q4 FY25-26 (ended 31-Mar-2026) is skipped
        assertThat(t.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 7, 22));
    }

    @Test
    void pmt06OnlyForFirstTwoMonthsOfEachQuarter() {
        List<ComplianceCalendarGenerator.Task> t = gen(PMT06, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 12, 31));
        List<LocalDate> due = t.stream().map(ComplianceCalendarGenerator.Task::dueDate).toList();
        // Apr(25-May) May(25-Jun) Jul(25-Aug) Aug(25-Sep) Oct(25-Nov) Nov(25-Dec); Jun/Sep/Dec periods excluded
        assertThat(due).containsExactly(LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 25), LocalDate.of(2026, 8, 25),
                LocalDate.of(2026, 9, 25), LocalDate.of(2026, 11, 25), LocalDate.of(2026, 12, 25));
    }

    @Test
    void tdsDepositIs7thExceptMarchWhichIs30April() {
        List<ComplianceCalendarGenerator.Task> t = gen(TDS_DEP, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 5, 31));
        List<LocalDate> due = t.stream().map(ComplianceCalendarGenerator.Task::dueDate).toList();
        // periods Dec..Apr; the March period uses the override (30 April) instead of 7 April
        assertThat(due).containsExactly(LocalDate.of(2027, 1, 7), LocalDate.of(2027, 2, 7), LocalDate.of(2027, 3, 7),
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 5, 7));
    }

    @Test
    void tdsQuarterlyReturnDatesAre31Jul31Oct31Jan31May() {
        List<ComplianceCalendarGenerator.Task> t = gen(TDS_RET, LocalDate.of(2026, 6, 1), LocalDate.of(2027, 5, 31));
        List<LocalDate> due = t.stream().map(ComplianceCalendarGenerator.Task::dueDate).toList();
        assertThat(due).containsExactly(LocalDate.of(2026, 7, 31), LocalDate.of(2026, 10, 31),
                LocalDate.of(2027, 1, 31), LocalDate.of(2027, 5, 31));
    }

    @Test
    void annualRuleFallsOnItsDueMonthEachYear() {
        List<ComplianceCalendarGenerator.Task> t = gen(GSTR9, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31));
        assertThat(t).hasSize(2);
        assertThat(t.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(t.get(1).dueDate()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void dueDayIsClampedToTheLengthOfTheMonth() {
        ComplianceCalendarGenerator.Rule r = new ComplianceCalendarGenerator.Rule("X", "X", "OTHER", 31, "MONTHLY", 1,
                null, null, null, null, null, null);
        assertThat(ComplianceCalendarGenerator.dueDate(r, YearMonth.of(2027, 1))).isEqualTo(LocalDate.of(2027, 2, 28));
    }

    @Test
    void expiryStatusUsesReminderWindow() {
        LocalDate today = LocalDate.of(2026, 10, 5);
        assertThat(ComplianceCalendarGenerator.expiryStatus(null, 30, today)).isEqualTo("ACTIVE");
        assertThat(ComplianceCalendarGenerator.expiryStatus(LocalDate.of(2026, 10, 4), 30, today)).isEqualTo("EXPIRED");
        assertThat(ComplianceCalendarGenerator.expiryStatus(LocalDate.of(2026, 10, 5), 30, today)).isEqualTo("EXPIRING");
        assertThat(ComplianceCalendarGenerator.expiryStatus(LocalDate.of(2026, 11, 4), 30, today)).isEqualTo("EXPIRING");
        assertThat(ComplianceCalendarGenerator.expiryStatus(LocalDate.of(2026, 11, 5), 30, today)).isEqualTo("ACTIVE");
    }
}
