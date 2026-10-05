package com.cafepos.platform;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TaxMathTest {

    @Test
    void extractsGstFromInclusivePrice() {
        // 105 inclusive at 5% -> 100 taxable, 5 tax
        assertThat(TaxMath.extractTaxable(new BigDecimal("105.00"), new BigDecimal("5")))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void lineSplitsTaxAndKeepsTotalExact() {
        TaxMath.LineTax l = TaxMath.line(new BigDecimal("2"), new BigDecimal("210"), BigDecimal.ZERO, new BigDecimal("5"));
        assertThat(l.lineTotal()).isEqualByComparingTo("420.00");
        assertThat(l.netTaxable()).isEqualByComparingTo("400.00");
        assertThat(l.taxAmount()).isEqualByComparingTo("20.00");
        assertThat(l.netTaxable().add(l.taxAmount())).isEqualByComparingTo(l.lineTotal());
        assertThat(l.taxStatus()).isEqualTo("EXTRACTED");
    }

    @Test
    void discountReducesTaxableProportionally() {
        TaxMath.LineTax l = TaxMath.line(BigDecimal.ONE, new BigDecimal("210"), new BigDecimal("21"), new BigDecimal("5"));
        assertThat(l.lineTotal()).isEqualByComparingTo("189.00");
        assertThat(l.netTaxable().add(l.taxAmount())).isEqualByComparingTo("189.00");
    }

    @Test
    void noRateMeansNotConfiguredAndNoTaxInvented() {
        TaxMath.LineTax l = TaxMath.line(BigDecimal.ONE, new BigDecimal("100"), BigDecimal.ZERO, null);
        assertThat(l.taxStatus()).isEqualTo("NOT_CONFIGURED");
        assertThat(l.taxAmount()).isEqualByComparingTo("0.00");
        assertThat(l.netTaxable()).isEqualByComparingTo("100.00");
    }
}
