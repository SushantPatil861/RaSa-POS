package com.cafepos.platform;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * GST-inclusive price handling. Menu prices include GST, so tax is EXTRACTED:
 * taxable = inclusive / (1 + rate), tax = inclusive - taxable.
 * Pure Java, no framework dependencies (unit-testable on its own).
 */
public final class TaxMath {

    private TaxMath() {}

    /** Result of splitting one sale line. All amounts have scale 2. */
    public record LineTax(BigDecimal grossInclusive, BigDecimal discount, BigDecimal lineTotal,
                          BigDecimal grossTaxable, BigDecimal discountTaxable, BigDecimal netTaxable,
                          BigDecimal taxAmount, String taxStatus) {}

    public static BigDecimal scale2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Amount before tax. If no rate is configured the amount is returned unchanged. */
    public static BigDecimal extractTaxable(BigDecimal inclusive, BigDecimal ratePercent) {
        if (ratePercent == null) {
            return scale2(inclusive);
        }
        BigDecimal divisor = BigDecimal.ONE.add(ratePercent.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        return scale2(inclusive.divide(divisor, 10, RoundingMode.HALF_UP));
    }

    public static LineTax line(BigDecimal quantity, BigDecimal unitPriceInclusive, BigDecimal discountInclusive,
                               BigDecimal ratePercent) {
        BigDecimal gross = scale2(quantity.multiply(unitPriceInclusive));
        BigDecimal discount = scale2(discountInclusive == null ? BigDecimal.ZERO : discountInclusive);
        BigDecimal total = gross.subtract(discount);
        BigDecimal grossTaxable = extractTaxable(gross, ratePercent);
        BigDecimal discountTaxable = extractTaxable(discount, ratePercent);
        BigDecimal netTaxable = grossTaxable.subtract(discountTaxable);
        BigDecimal tax = total.subtract(netTaxable);
        String status = ratePercent == null ? "NOT_CONFIGURED" : "EXTRACTED";
        return new LineTax(gross, discount, total, grossTaxable, discountTaxable, netTaxable, tax, status);
    }
}
