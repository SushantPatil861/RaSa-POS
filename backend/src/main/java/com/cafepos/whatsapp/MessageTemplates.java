package com.cafepos.whatsapp;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Texts for outbound messages. Plain strings so they are easy to review and test. */
public final class MessageTemplates {

    private MessageTemplates() {}

    /** Indian digit grouping with the rupee sign: 1234567.5 -> "₹12,34,567.50". */
    public static String inr(BigDecimal amount) {
        BigDecimal a = amount.setScale(2, RoundingMode.HALF_UP);
        String plain = a.abs().toPlainString();
        String intPart = plain.substring(0, plain.indexOf('.'));
        String frac = plain.substring(plain.indexOf('.'));
        StringBuilder sb = new StringBuilder();
        int n = intPart.length();
        if (n <= 3) {
            sb.append(intPart);
        } else {
            sb.append(group(intPart.substring(0, n - 3))).append(',').append(intPart.substring(n - 3));
        }
        return (a.signum() < 0 ? "-" : "") + "₹" + sb + frac;
    }

    private static String group(String s) { // pairs from the right: "1234" -> "12,34"
        StringBuilder sb = new StringBuilder();
        int first = s.length() % 2;
        if (first > 0) sb.append(s, 0, first);
        for (int i = first; i < s.length(); i += 2) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s, i, i + 2);
        }
        return sb.toString();
    }

    private static String hi(String name) {
        return name == null || name.isBlank() || name.equals("Customer") ? "Hi" : "Hi " + name.trim();
    }

    public static String orderConfirmed(String name, String orderNo, BigDecimal total, String cafe) {
        return hi(name) + ", your order " + orderNo + " is confirmed. Total: " + inr(total)
                + ". We will message you when it is ready. - " + cafe;
    }

    public static String orderReady(String name, String orderNo, BigDecimal total, String cafe) {
        return hi(name) + ", your order " + orderNo + " is ready. Total: " + inr(total) + ". See you soon! - " + cafe;
    }

    public static String paymentRequest(String name, String orderNo, BigDecimal total, String vpa, String link, String cafe) {
        return hi(name) + ", please pay " + inr(total) + " for order " + orderNo + ".\n"
                + "UPI ID: " + vpa.trim() + "\n"
                + "Tap to pay (opens your UPI app): " + link + "\n"
                + "Your order is marked paid once we see the payment. - " + cafe;
    }
}
