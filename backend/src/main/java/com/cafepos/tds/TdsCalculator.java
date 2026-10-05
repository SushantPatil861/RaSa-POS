package com.cafepos.tds;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Framework-free TDS arithmetic. A WORKPAPER aid only: thresholds and rates come from the effective-dated
 * tds_sections table (seed values are marked "verify with CA"). Nothing here claims a deduction is legally due.
 */
public final class TdsCalculator {

    public static final String BELOW_THRESHOLD = "BELOW_THRESHOLD";
    public static final String DEDUCT = "DEDUCT";
    public static final String CHECK_WITH_CA = "CHECK_WITH_CA";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");

    private TdsCalculator() {}

    /** The section row in force for a payment (null in compute() = unknown section / no rule). */
    public record SectionRate(String code, String payeeType, BigDecimal rate, BigDecimal singleThreshold,
                              BigDecimal annualThreshold) {}

    /** tdsAmount is null when the answer is unknown (CHECK_WITH_CA); 0.00 when below threshold. */
    public record Result(String thresholdStatus, BigDecimal rate, BigDecimal tdsAmount, boolean panMissing,
                         List<String> notes) {
        public String noteText() {
            return notes.isEmpty() ? null : String.join(" | ", notes);
        }
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static boolean looksLikePan(String pan) {
        return !isBlank(pan) && PAN.matcher(pan.trim().toUpperCase()).matches();
    }

    public static BigDecimal tdsOn(BigDecimal payment, BigDecimal ratePct) {
        return payment.multiply(ratePct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /**
     * @param section       the rate row in force, or null when the section / payee type is unknown
     * @param payment       this payment (ex-GST where the CA says GST is shown separately)
     * @param priorFyTotal  earlier active payments to the same deductee under the same section in the same financial year
     * @param pan           deductee PAN as entered (may be blank)
     */
    public static Result compute(SectionRate section, BigDecimal payment, BigDecimal priorFyTotal, String pan) {
        List<String> notes = new ArrayList<>();
        boolean panMissing = isBlank(pan);
        if (panMissing) {
            notes.add("PAN missing: higher rate may apply, ask CA");
        } else if (!looksLikePan(pan)) {
            notes.add("PAN format looks wrong - check it against the deductee's PAN card");
        }
        if (section == null) {
            notes.add("No rate found for this section, payee type and date - ask CA");
            return new Result(CHECK_WITH_CA, null, null, panMissing, notes);
        }
        BigDecimal prior = priorFyTotal == null ? BigDecimal.ZERO : priorFyTotal;
        BigDecimal single = section.singleThreshold();
        BigDecimal annual = section.annualThreshold();
        BigDecimal rate = section.rate();

        if (single == null && annual == null) {
            notes.add("No threshold is configured for section " + section.code() + " - ask CA. Indicative TDS at "
                    + rate.stripTrailingZeros().toPlainString() + "% would be " + tdsOn(payment, rate));
            return new Result(CHECK_WITH_CA, rate, null, panMissing, notes);
        }
        boolean singleCrossed = single != null && payment.compareTo(single) > 0;
        boolean annualCrossed = annual != null && prior.add(payment).compareTo(annual) > 0;
        if (singleCrossed || annualCrossed) {
            if (annualCrossed && !singleCrossed && prior.signum() > 0 && prior.compareTo(annual) <= 0) {
                notes.add("Annual threshold crossed with this payment; earlier payments this year (" + prior.setScale(2, RoundingMode.HALF_UP)
                        + ") may also need TDS - ask CA");
            }
            return new Result(DEDUCT, rate, tdsOn(payment, rate), panMissing, notes);
        }
        notes.add("Below threshold so far this financial year; keep adding entries so the running total is tracked");
        return new Result(BELOW_THRESHOLD, rate, BigDecimal.ZERO.setScale(2), panMissing, notes);
    }

    /** Deposit due date: 7th of the following month; for March payments, 30 April. */
    public static LocalDate depositDueDate(LocalDate txnDate) {
        if (txnDate.getMonthValue() == 3) {
            return LocalDate.of(txnDate.getYear(), 4, 30);
        }
        return YearMonth.from(txnDate).plusMonths(1).atDay(7);
    }

    /** Calendar year in which the Indian financial year (Apr-Mar) containing the date starts. */
    public static int fyStartYear(LocalDate d) {
        return d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
    }

    public static String fyLabel(int fyStartYear) {
        return String.format("FY%02d-%02d", fyStartYear % 100, (fyStartYear + 1) % 100);
    }
}
