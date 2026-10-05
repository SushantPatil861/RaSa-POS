package com.cafepos.ocr;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Rules for when a confirmed OCR record must be labelled ESTIMATED. Pure, so it is unit-tested without Spring. */
public final class DraftReview {

    /** The figures that matter most: if one was a low-confidence OCR guess and nobody touched it, the record is an estimate. */
    public static final List<String> KEY_FIELDS = List.of("totalAmount", "txnDate", "supplierName");

    private DraftReview() {}

    /**
     * @param proposedValues      what OCR proposed, by field name (text form)
     * @param proposedConfidence  HIGH / MEDIUM / LOW by field name
     * @param submitted           what the reviewer submitted, by field name (text form)
     * @return true when any key field was proposed with LOW confidence and was submitted unchanged
     */
    public static boolean anyLowUnedited(Map<String, String> proposedValues, Map<String, String> proposedConfidence,
                                         Map<String, String> submitted) {
        for (String k : KEY_FIELDS) {
            String proposed = proposedValues.get(k);
            if (proposed == null || proposed.isBlank()) continue;
            if (!"LOW".equals(proposedConfidence.get(k))) continue;
            if (same(proposed, submitted.get(k))) return true;
        }
        return false;
    }

    static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.trim(), y = b.trim();
        try {
            return new BigDecimal(x.replace(",", "")).compareTo(new BigDecimal(y.replace(",", ""))) == 0;
        } catch (NumberFormatException e) {
            return x.equalsIgnoreCase(y);
        }
    }
}
