package com.cafepos.whatsapp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Builds a UPI deep link: upi://pay?pa=<vpa>&pn=<payee>&am=<amount>&cu=INR&tn=<note>. No payment gateway involved. */
public final class UpiLink {

    private static final Pattern VPA = Pattern.compile("^[A-Za-z0-9._\\-]{2,}@[A-Za-z0-9.]{2,}$");

    private UpiLink() {}

    public static boolean validVpa(String vpa) {
        return vpa != null && VPA.matcher(vpa.trim()).matches();
    }

    /**
     * @throws IllegalArgumentException when the UPI id is missing or malformed, or the amount is not above zero
     */
    public static String build(String vpa, String payeeName, BigDecimal amount, String orderNo) {
        if (vpa == null || vpa.isBlank()) throw new IllegalArgumentException("The cafe UPI id is not set (cafe.whatsapp.upi-vpa)");
        if (!validVpa(vpa)) throw new IllegalArgumentException("The cafe UPI id '" + vpa + "' does not look valid (expected name@bank)");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("The amount must be above zero");
        StringBuilder sb = new StringBuilder("upi://pay?pa=").append(vpa.trim());
        sb.append("&pn=").append(enc(payeeName == null || payeeName.isBlank() ? "Cafe" : payeeName.trim()));
        sb.append("&am=").append(amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        sb.append("&cu=INR");
        if (orderNo != null && !orderNo.isBlank()) sb.append("&tn=").append(enc(orderNo.trim()));
        return sb.toString();
    }

    /** Percent-encoding with %20 for spaces (UPI apps do not understand '+'). */
    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
