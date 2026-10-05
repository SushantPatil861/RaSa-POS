package com.cafepos.imports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** Parses money as people type it in India: "1,23,456.50", "Rs. 500/-", "₹500", "(250)", "INR 1,000". */
public final class IndianAmount {

    private static final Pattern PLAIN = Pattern.compile("\\d+(\\.\\d+)?|\\.\\d+");
    private static final Pattern SCI = Pattern.compile("\\d+(\\.\\d+)?[eE][+-]?\\d+");
    // 1,23,456 (Indian) or 1,234,567 (international) or no commas at all
    private static final Pattern GROUPED = Pattern.compile("\\d{1,3}(,\\d{2})*,\\d{3}|\\d{1,3}(,\\d{3})+");
    private static final Pattern LEAD_CCY = Pattern.compile("^(?i)(rs\\.?|inr|rupees|₹)\\s*");
    private static final Pattern TRAIL_CCY = Pattern.compile("(?i)\\s*(/-|/=|-|only|rs\\.?|inr)$");
    private static final BigDecimal LIMIT = new BigDecimal("100000000000"); // 1e11, safely inside numeric(14,2)

    private IndianAmount() {}

    /** @return the amount rounded to 2 decimals, or null when the cell is blank. */
    public static BigDecimal parse(String raw) {
        if (raw == null) return null;
        String s = raw.replace(' ', ' ').trim();
        if (s.isEmpty()) return null;
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")") && s.length() > 2) {
            negative = true;
            s = s.substring(1, s.length() - 1).trim();
        }
        for (int pass = 0; pass < 2; pass++) { // sign and currency can come in either order: "-₹5", "₹-5"
            if (s.startsWith("-") || s.startsWith("−")) {
                negative = true;
                s = s.substring(1).trim();
            }
            s = LEAD_CCY.matcher(s).replaceFirst("").trim();
        }
        s = TRAIL_CCY.matcher(s).replaceFirst("").trim();
        s = TRAIL_CCY.matcher(s).replaceFirst("").trim(); // "500/- only"
        s = s.replace(" ", "");
        if (s.isEmpty()) throw new NumberFormatException("'" + raw + "' is not an amount");
        BigDecimal v;
        if (SCI.matcher(s).matches()) {
            v = new BigDecimal(s);
        } else {
            String intPart = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
            String frac = s.contains(".") ? s.substring(s.indexOf('.')) : "";
            if (frac.contains(",")) throw new NumberFormatException("'" + raw + "' is not an amount");
            if (intPart.contains(",")) {
                if (!GROUPED.matcher(intPart).matches()) throw new NumberFormatException("'" + raw + "' is not an amount");
                intPart = intPart.replace(",", "");
            }
            String plain = intPart + frac;
            if (!PLAIN.matcher(plain).matches()) throw new NumberFormatException("'" + raw + "' is not an amount");
            v = new BigDecimal(plain);
        }
        v = v.setScale(2, RoundingMode.HALF_UP);
        if (v.abs().compareTo(LIMIT) >= 0) throw new NumberFormatException("'" + raw + "' is too large to be an amount");
        return negative ? v.negate() : v;
    }
}
