package com.cafepos.imports;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RowValidatorTest {

    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private static final Map<String, Integer> EXP = Map.of("date", 0, "category", 1, "paidTo", 2, "method", 3, "amount", 4, "notes", 5);

    private RowValidator.Result exp(String... cells) {
        return RowValidator.validate("EXPENSES", List.of(cells), EXP, "dd/MM/yyyy", START, TODAY);
    }

    @Test
    void validExpenseRowIsNormalised() {
        RowValidator.Result r = exp("03/05/2026", "Rent", "Landlord", "Bank transfer", "Rs. 25,000/-", "May rent");
        assertThat(r.ok()).isTrue();
        assertThat(r.mapped()).containsEntry("date", "2026-05-03");
        assertThat(r.mapped()).containsEntry("amount", "25000.00");
        assertThat(r.mapped()).containsEntry("method", "BANK");
        assertThat(r.mapped()).containsEntry("category", "Rent");
        assertThat(r.mapped()).containsEntry("paidTo", "Landlord");
    }

    @Test
    void dateBeforeDataStartIsAnError() {
        RowValidator.Result r = exp("30/04/2026", "", "", "", "100", "");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("before the business data-start date");
    }

    @Test
    void futureDateIsAnError() {
        RowValidator.Result r = exp("06/10/2026", "", "", "", "100", "");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("in the future");
        assertThat(exp("05/10/2026", "", "", "", "100", "").ok()).isTrue(); // today is fine
    }

    @Test
    void missingAndInvalidFieldsAreAllReported() {
        RowValidator.Result r = exp("31/02/2026", "", "", "", "abc", "");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("not a valid dd/MM/yyyy date");
        assertThat(r.error()).contains("not a valid amount");
        RowValidator.Result blank = exp("", "", "", "", "", "");
        assertThat(blank.error()).contains("Date is missing");
        assertThat(blank.error()).contains("Amount is missing");
    }

    @Test
    void negativeAmountIsRejected() {
        assertThat(exp("03/05/2026", "", "", "", "-50", "").ok()).isFalse();
    }

    @Test
    void unknownMethodBecomesOtherAndBlankStaysUnknown() {
        assertThat(exp("03/05/2026", "", "", "Barter", "10", "").mapped()).containsEntry("method", "OTHER");
        assertThat(exp("03/05/2026", "", "", "", "10", "").mapped()).doesNotContainKey("method");
        assertThat(RowValidator.paymentMethod("GPay")).isEqualTo("UPI");
        assertThat(RowValidator.paymentMethod("Cash")).isEqualTo("CASH");
        assertThat(RowValidator.paymentMethod("Credit Card")).isEqualTo("CARD");
    }

    @Test
    void dedupeKeyUsesDateAmountAndPayee() {
        Map<String, String> a = exp("03/05/2026", "Rent", "Landlord", "", "100", "").mapped();
        Map<String, String> b = exp("03/05/2026", "Other", "LANDLORD", "", "100", "").mapped();
        assertThat(RowValidator.dedupeKey("EXPENSES", a)).isEqualTo(RowValidator.dedupeKey("EXPENSES", b));
        Map<String, String> c = exp("03/05/2026", "Rent", "Landlord", "", "101", "").mapped();
        assertThat(RowValidator.dedupeKey("EXPENSES", a)).isNotEqualTo(RowValidator.dedupeKey("EXPENSES", c));
    }

    @Test
    void dailySalesRowChecksTotalsAndOrderCount() {
        Map<String, Integer> map = Map.of("date", 0, "totalSales", 1, "cash", 2, "upi", 3, "ordersCount", 4, "discounts", 5);
        RowValidator.Result ok = RowValidator.validate("DAILY_SALES", List.of("02/05/2026", "12,500", "5000", "7500", "48", "0"), map, "dd/MM/yyyy", START, TODAY);
        assertThat(ok.ok()).isTrue();
        assertThat(ok.mapped()).containsEntry("totalSales", "12500.00");
        assertThat(ok.mapped()).containsEntry("ordersCount", "48");
        RowValidator.Result bad = RowValidator.validate("DAILY_SALES", List.of("02/05/2026", "100", "", "", "4.5", "200"), map, "dd/MM/yyyy", START, TODAY);
        assertThat(bad.ok()).isFalse();
        assertThat(bad.error()).contains("Discounts plus refunds");
        assertThat(bad.error()).contains("not a whole number");
        assertThat(ok.mapped()).doesNotContainKey("refunds"); // unknown stays unknown
    }

    @Test
    void purchaseRowParsesStatusAndInvoice() {
        Map<String, Integer> map = Map.of("date", 0, "supplier", 1, "invoiceNo", 2, "amount", 3, "paymentStatus", 4, "invoiceDate", 5);
        RowValidator.Result r = RowValidator.validate("PURCHASES", List.of("04/05/2026", "ABC Foods", "INV-12", "₹4,200", "Not paid", "03/05/2026"), map, "dd/MM/yyyy", START, TODAY);
        assertThat(r.ok()).isTrue();
        assertThat(r.mapped()).containsEntry("paymentStatus", "UNPAID");
        assertThat(r.mapped()).containsEntry("invoiceDate", "2026-05-03");
        assertThat(r.mapped()).containsEntry("amount", "4200.00");
        RowValidator.Result bad = RowValidator.validate("PURCHASES", List.of("04/05/2026", "", "", "10", "maybe", ""), map, "dd/MM/yyyy", START, TODAY);
        assertThat(bad.ok()).isFalse();
        assertThat(RowValidator.paymentStatus("Paid")).isEqualTo("PAID");
        assertThat(RowValidator.paymentStatus("Part paid")).isEqualTo("PARTIAL");
    }

    @Test
    void xlsxStyleIsoDatesPassWhateverTheFormat() {
        RowValidator.Result r = RowValidator.validate("EXPENSES", List.of("2026-05-03", "", "", "", "99", ""), EXP, "dd/MM/yyyy", START, TODAY);
        assertThat(r.ok()).isTrue();
    }

    @Test
    void duplicatesAgainstExistingRecords() {
        List<RowValidator.Existing> existing = List.of(
                new RowValidator.Existing(LocalDate.of(2026, 5, 3), new java.math.BigDecimal("500.00"), "Landlord", "Rent"));
        // same date + amount + payee
        assertThat(RowValidator.duplicateOf("EXPENSES", exp("03/05/2026", "", "landlord", "", "500", "").mapped(), existing)).isNotNull();
        // same date + amount + category, different payee text
        assertThat(RowValidator.duplicateOf("EXPENSES", exp("03/05/2026", "RENT", "Someone", "", "500", "").mapped(), existing)).isNotNull();
        // no payee and no category: date + amount alone counts
        assertThat(RowValidator.duplicateOf("EXPENSES", exp("03/05/2026", "", "", "", "500.00", "").mapped(), existing)).isNotNull();
        // different amount, different payee, different date: not duplicates
        assertThat(RowValidator.duplicateOf("EXPENSES", exp("03/05/2026", "", "Landlord", "", "501", "").mapped(), existing)).isNull();
        assertThat(RowValidator.duplicateOf("EXPENSES", exp("03/05/2026", "Gas", "Indane", "", "500", "").mapped(), existing)).isNull();
        assertThat(RowValidator.duplicateOf("EXPENSES", exp("04/05/2026", "Rent", "Landlord", "", "500", "").mapped(), existing)).isNull();
    }

    @Test
    void purchaseAndDailyDuplicates() {
        Map<String, Integer> pm = Map.of("date", 0, "supplier", 1, "invoiceNo", 2, "amount", 3);
        List<RowValidator.Existing> existing = List.of(
                new RowValidator.Existing(LocalDate.of(2026, 5, 4), new java.math.BigDecimal("4200.00"), "ABC Foods", "INV-9"));
        Map<String, String> same = RowValidator.validate("PURCHASES", List.of("04/05/2026", "abc foods", "", "4200"), pm, "dd/MM/yyyy", START, TODAY).mapped();
        assertThat(RowValidator.duplicateOf("PURCHASES", same, existing)).isNotNull();
        Map<String, String> other = RowValidator.validate("PURCHASES", List.of("04/05/2026", "XYZ Dairy", "", "4200"), pm, "dd/MM/yyyy", START, TODAY).mapped();
        assertThat(RowValidator.duplicateOf("PURCHASES", other, existing)).isNull();
        Map<String, String> sameInvoice = RowValidator.validate("PURCHASES", List.of("04/05/2026", "XYZ Dairy", "inv-9", "4200"), pm, "dd/MM/yyyy", START, TODAY).mapped();
        assertThat(RowValidator.duplicateOf("PURCHASES", sameInvoice, existing)).isNotNull();

        Map<String, Integer> dm = Map.of("date", 0, "totalSales", 1);
        List<RowValidator.Existing> days = List.of(new RowValidator.Existing(LocalDate.of(2026, 5, 5), new java.math.BigDecimal("9000"), "", ""));
        Map<String, String> day = RowValidator.validate("DAILY_SALES", List.of("05/05/2026", "1"), dm, "dd/MM/yyyy", START, TODAY).mapped();
        assertThat(RowValidator.duplicateOf("DAILY_SALES", day, days)).isNotNull();
        Map<String, String> day2 = RowValidator.validate("DAILY_SALES", List.of("06/05/2026", "1"), dm, "dd/MM/yyyy", START, TODAY).mapped();
        assertThat(RowValidator.duplicateOf("DAILY_SALES", day2, days)).isNull();
    }
}
