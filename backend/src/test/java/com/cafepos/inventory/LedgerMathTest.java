package com.cafepos.inventory;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerMathTest {

    static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void paymentsClearOldestBillsFirstAndBucketsByAge() {
        LocalDate asOf = LocalDate.of(2026, 10, 1);
        var bills = List.of(
                new LedgerMath.Bill(1, LocalDate.of(2026, 6, 1), bd("1000")),   // 122 days old
                new LedgerMath.Bill(2, LocalDate.of(2026, 8, 20), bd("500")),   // 42 days
                new LedgerMath.Bill(3, LocalDate.of(2026, 9, 25), bd("300")));  // 6 days
        LedgerMath.Aging a = LedgerMath.age(bills, bd("1200"), asOf);
        assertThat(a.outstanding()).isEqualByComparingTo("600");
        assertThat(a.bucket60plus()).isEqualByComparingTo("0");
        assertThat(a.bucket31to60()).isEqualByComparingTo("300");
        assertThat(a.bucket0to30()).isEqualByComparingTo("300");
        assertThat(a.advance()).isEqualByComparingTo("0");
        assertThat(a.openBills()).hasSize(2);
    }

    @Test
    void overpaymentIsAnAdvanceNotNegativeBucket() {
        LedgerMath.Aging a = LedgerMath.age(List.of(new LedgerMath.Bill(1, LocalDate.of(2026, 9, 1), bd("100"))),
                bd("150"), LocalDate.of(2026, 9, 10));
        assertThat(a.outstanding()).isEqualByComparingTo("0");
        assertThat(a.advance()).isEqualByComparingTo("50");
    }

    @Test
    void bucketBoundaries() {
        assertThat(LedgerMath.bucketOf(30)).isEqualTo("0-30");
        assertThat(LedgerMath.bucketOf(31)).isEqualTo("31-60");
        assertThat(LedgerMath.bucketOf(60)).isEqualTo("31-60");
        assertThat(LedgerMath.bucketOf(61)).isEqualTo("60+");
    }
}
