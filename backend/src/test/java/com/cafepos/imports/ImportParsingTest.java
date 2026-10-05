package com.cafepos.imports;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportParsingTest {

    // ---------------------------------------------------------------- CSV

    @Test
    void parsesSimpleCsvWithCrlfAndTrailingNewline() {
        List<List<String>> rows = TabularParser.parseCsv("Date,Amount\r\n01/05/2026,500\r\n02/05/2026,600\r\n");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsExactly("Date", "Amount");
        assertThat(rows.get(2)).containsExactly("02/05/2026", "600");
    }

    @Test
    void parsesQuotesCommasAndEscapedQuotes() {
        List<List<String>> rows = TabularParser.parseCsv("a,b,c\n\"Ram, Sons\",\"He said \"\"hi\"\"\",\"1,23,456.50\"\n");
        assertThat(rows.get(1)).containsExactly("Ram, Sons", "He said \"hi\"", "1,23,456.50");
    }

    @Test
    void keepsNewlinesInsideQuotes() {
        List<List<String>> rows = TabularParser.parseCsv("a,b\n\"line1\nline2\",x\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get(0)).isEqualTo("line1\nline2");
        assertThat(rows.get(1).get(1)).isEqualTo("x");
    }

    @Test
    void stripsBomAndHandlesSemicolonAndTab() {
        byte[] bom = "﻿Date;Amount\n01-05-2026;500\n".getBytes(StandardCharsets.UTF_8);
        List<List<String>> rows = TabularParser.parseCsv(bom);
        assertThat(rows.get(0)).containsExactly("Date", "Amount");
        assertThat(TabularParser.parseCsv("a\tb\n1\t2\n").get(1)).containsExactly("1", "2");
    }

    @Test
    void keepsBlankRowsSoRowNumbersMatchTheFile() {
        List<List<String>> rows = TabularParser.parseCsv("a,b\n\n1,2\n");
        assertThat(rows).hasSize(3);
        assertThat(TabularParser.isBlank(rows.get(1))).isTrue();
        assertThat(TabularParser.isBlank(rows.get(2))).isFalse();
    }

    @Test
    void emptyCellsAndShortRowsAreKept() {
        List<List<String>> rows = TabularParser.parseCsv("a,b,c\n1,,3\n4\n");
        assertThat(rows.get(1)).containsExactly("1", "", "3");
        assertThat(rows.get(2)).containsExactly("4");
        assertThat(TabularParser.width(rows)).isEqualTo(3);
    }

    @Test
    void fallsBackToWindows1252WhenNotUtf8() {
        byte[] data = "Item,Amount\nCafé rent,500\n".getBytes(Charset.forName("windows-1252"));
        assertThat(TabularParser.parseCsv(data).get(1).get(0)).isEqualTo("Café rent");
    }

    // ---------------------------------------------------------------- amounts

    @Test
    void parsesIndianAmounts() {
        assertThat(IndianAmount.parse("1,23,456.50")).isEqualByComparingTo("123456.50");
        assertThat(IndianAmount.parse("Rs. 500/-")).isEqualByComparingTo("500");
        assertThat(IndianAmount.parse("₹500")).isEqualByComparingTo("500");
        assertThat(IndianAmount.parse("₹ 1,23,456")).isEqualByComparingTo("123456");
        assertThat(IndianAmount.parse("INR 2,500.00")).isEqualByComparingTo("2500");
        assertThat(IndianAmount.parse("Rs 1,000/- only")).isEqualByComparingTo("1000");
        assertThat(IndianAmount.parse("1,234,567")).isEqualByComparingTo("1234567");
        assertThat(IndianAmount.parse("500.555")).isEqualByComparingTo("500.56");
        assertThat(IndianAmount.parse("  42 ")).isEqualByComparingTo("42");
        assertThat(IndianAmount.parse(".5")).isEqualByComparingTo("0.50");
    }

    @Test
    void parsesNegativesAndBlank() {
        assertThat(IndianAmount.parse("-500")).isEqualByComparingTo("-500");
        assertThat(IndianAmount.parse("(250.00)")).isEqualByComparingTo("-250");
        assertThat(IndianAmount.parse("")).isNull();
        assertThat(IndianAmount.parse("   ")).isNull();
        assertThat(IndianAmount.parse(null)).isNull();
    }

    @Test
    void rejectsGarbageAndAmbiguousAmounts() {
        for (String bad : new String[]{"abc", "12,34", "1.234,50", "5-5", "Rs", "1,2,3", "12.34.56", "99999999999999"}) {
            boolean threw = false;
            try {
                IndianAmount.parse(bad);
            } catch (NumberFormatException e) {
                threw = true;
            }
            assertThat(threw).isTrue();
        }
    }

    // ---------------------------------------------------------------- dates

    @Test
    void parsesCommonIndianDateFormats() {
        assertThat(ImportDates.parseStrict("05/06/2026", "dd/MM/yyyy")).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(ImportDates.parseStrict("5/6/2026", "dd/MM/yyyy")).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(ImportDates.parseStrict("05-06-26", "dd-MM-yy")).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(ImportDates.parseStrict("5-May-2026", "d-MMM-yyyy")).isEqualTo(LocalDate.of(2026, 5, 5));
        assertThat(ImportDates.parseStrict("05-may-2026", "d-MMM-yyyy")).isEqualTo(LocalDate.of(2026, 5, 5));
        assertThat(ImportDates.parseStrict("2026-05-07", "yyyy-MM-dd")).isEqualTo(LocalDate.of(2026, 5, 7));
        assertThat(ImportDates.parseStrict("07.05.2026", "dd.MM.yyyy")).isEqualTo(LocalDate.of(2026, 5, 7));
    }

    @Test
    void isoTextIsAlwaysAccepted() {
        assertThat(ImportDates.parseStrict("2026-05-03", "dd/MM/yyyy")).isEqualTo(LocalDate.of(2026, 5, 3));
        assertThat(ImportDates.parseStrict("2026-05-03 00:00:00", "dd/MM/yyyy")).isEqualTo(LocalDate.of(2026, 5, 3));
    }

    @Test
    void invalidDatesAreRejected() {
        assertThat(ImportDates.parse("31/02/2026", "dd/MM/yyyy")).isNull();
        assertThat(ImportDates.parse("32/01/2026", "dd/MM/yyyy")).isNull();
        assertThat(ImportDates.parse("13/13/2026", "dd/MM/yyyy")).isNull();
        assertThat(ImportDates.parse("05/06/26", "dd/MM/yyyy")).isNull(); // 2-digit year does not satisfy yyyy
        assertThat(ImportDates.parse("hello", "dd/MM/yyyy")).isNull();
        assertThat(ImportDates.parse("", "dd/MM/yyyy")).isNull();
    }

    @Test
    void detectsFormatAndPrefersDayFirst() {
        assertThat(ImportDates.detect(List.of("13/05/2026", "05/06/2026"))).isEqualTo("dd/MM/yyyy");
        assertThat(ImportDates.detect(List.of("05/06/2026", "07/06/2026"))).isEqualTo("dd/MM/yyyy"); // ambiguous -> DD/MM
        assertThat(ImportDates.detect(List.of("05-06-26", "13-06-26"))).isEqualTo("dd-MM-yy");
        assertThat(ImportDates.detect(List.of("1-May-2026", "12-May-2026"))).isEqualTo("d-MMM-yyyy");
        assertThat(ImportDates.detect(List.of("05/13/2026", "05/14/2026"))).isEqualTo("MM/dd/yyyy");
        assertThat(ImportDates.detect(List.of("2026-05-01", "2026-05-02"))).isEqualTo("yyyy-MM-dd");
        assertThat(ImportDates.detect(List.of())).isEqualTo("dd/MM/yyyy");
    }

    @Test
    void amountsAreScaledToTwoPlaces() {
        BigDecimal v = IndianAmount.parse("12");
        assertThat(v.scale()).isEqualTo(2);
    }
}
