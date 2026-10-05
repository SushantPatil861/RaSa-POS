package com.cafepos.whatsapp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Locale;

/** Meta signs every webhook POST: header X-Hub-Signature-256 = "sha256=" + HMAC-SHA256(app secret, raw body) in hex. */
public final class SignatureVerifier {

    private SignatureVerifier() {}

    /** @return true only when the header is present and matches; compared in constant time. */
    public static boolean verify(String appSecret, byte[] body, String header) {
        if (appSecret == null || appSecret.isBlank() || body == null || header == null) return false;
        String h = header.trim();
        if (!h.regionMatches(true, 0, "sha256=", 0, 7)) return false;
        String given = h.substring(7).toLowerCase(Locale.ROOT);
        String expected = sign(appSecret, body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII));
    }

    /** Lower-case hex HMAC-SHA256. */
    public static String sign(String appSecret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = mac.doFinal(body);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time string comparison (used for the webhook verify token). */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
