package com.cafepos.imports;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderMatcherTest {

    @Test
    void normalisesNames() {
        assertThat(HeaderMatcher.normalise("Paid To:")).isEqualTo("paid to");
        assertThat(HeaderMatcher.normalise("  Amt. ")).isEqualTo("amt");
        assertThat(HeaderMatcher.normalise("Amount (₹)")).isEqualTo("amount rs");
        assertThat(HeaderMatcher.normalise("expense_type")).isEqualTo("expense type");
    }

    @Test
    void suggestsExpenseColumnsFromTypicalHeaders() {
        Map<String, Integer> m = HeaderMatcher.suggest("EXPENSES",
                List.of("Date", "Expense Type", "Vendor", "Paid Via", "Amount (Rs.)", "Remarks"));
        assertThat(m).containsEntry("date", 0);
        assertThat(m).containsEntry("category", 1);
        assertThat(m).containsEntry("paidTo", 2);
        assertThat(m).containsEntry("method", 3);
        assertThat(m).containsEntry("amount", 4);
        assertThat(m).containsEntry("notes", 5);
    }

    @Test
    void handlesTerseHeaders() {
        Map<String, Integer> m = HeaderMatcher.suggest("EXPENSES", List.of("Dt", "Head", "Rs", "Mode", "Supplier"));
        assertThat(m).containsEntry("date", 0);
        assertThat(m).containsEntry("category", 1);
        assertThat(m).containsEntry("amount", 2);
        assertThat(m).containsEntry("method", 3);
        assertThat(m).containsEntry("paidTo", 4);
    }

    @Test
    void aColumnIsNeverUsedTwice() {
        Map<String, Integer> m = HeaderMatcher.suggest("EXPENSES", List.of("Date", "Total"));
        assertThat(m).containsEntry("date", 0);
        assertThat(m).containsEntry("amount", 1);
        assertThat(m.size()).isEqualTo(2);
    }

    @Test
    void unknownHeadersGiveNoSuggestion() {
        Map<String, Integer> m = HeaderMatcher.suggest("EXPENSES", List.of("Foo", "Bar", ""));
        assertThat(m).isEmpty();
    }

    @Test
    void dailySalesSpecificColumnsWinOverGenericTotal() {
        Map<String, Integer> m = HeaderMatcher.suggest("DAILY_SALES",
                List.of("Date", "Cash Sales", "UPI", "Card", "Total Sales", "No of Orders", "Discount"));
        assertThat(m).containsEntry("date", 0);
        assertThat(m).containsEntry("cash", 1);
        assertThat(m).containsEntry("upi", 2);
        assertThat(m).containsEntry("card", 3);
        assertThat(m).containsEntry("totalSales", 4);
        assertThat(m).containsEntry("ordersCount", 5);
        assertThat(m).containsEntry("discounts", 6);
    }

    @Test
    void purchaseSheetWithOnlyInvoiceDateStillGetsADate() {
        Map<String, Integer> m = HeaderMatcher.suggest("PURCHASES", List.of("Invoice No", "Invoice Date", "Supplier Name", "Bill Amount"));
        assertThat(m).containsEntry("invoiceNo", 0);
        assertThat(m).containsEntry("invoiceDate", 1);
        assertThat(m).containsEntry("date", 1);
        assertThat(m).containsEntry("supplier", 2);
        assertThat(m).containsEntry("amount", 3);
    }

    @Test
    void requiredFieldsAreListedFirst() {
        assertThat(HeaderMatcher.fields("EXPENSES").get(0).required()).isTrue();
        assertThat(HeaderMatcher.isTarget("EXPENSES")).isTrue();
        assertThat(HeaderMatcher.isTarget("NOPE")).isFalse();
    }
}
