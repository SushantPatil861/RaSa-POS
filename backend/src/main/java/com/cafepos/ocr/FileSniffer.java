package com.cafepos.ocr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Decides what an uploaded file REALLY is from its first bytes (never from its name) and cleans client file names. */
public final class FileSniffer {

    public record Detected(String contentType, String extension, boolean image) {}

    private FileSniffer() {}

    /** @return the detected type for jpg / png / webp / pdf, or null when the file is none of them. */
    public static Detected sniff(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return new Detected("image/jpeg", "jpg", true);
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A
                && b[6] == 0x1A && b[7] == 0x0A) {
            return new Detected("image/png", "png", true);
        }
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B'
                && b[11] == 'P') {
            return new Detected("image/webp", "webp", true);
        }
        if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return new Detected("application/pdf", "pdf", false);
        }
        return null;
    }

    /** Display-only name: no directories, no control characters, a safe character set, at most 100 characters. */
    public static String sanitiseName(String raw) {
        if (raw == null) return "file";
        String s = raw;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.replaceAll("\\p{Cntrl}", "");
        s = s.replaceAll("[^\\p{L}\\p{N}._ ()\\-]", "_");
        s = s.replaceAll("\\.{2,}", ".").replaceAll("\\s+", " ").trim();
        while (s.startsWith(".") || s.startsWith("-")) s = s.substring(1);
        if (s.length() > 100) {
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 && s.length() - dot <= 8 ? s.substring(dot) : "";
            s = s.substring(0, 100 - ext.length()) + ext;
        }
        return s.isBlank() ? "file" : s;
    }

    public static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lower-case extension of a client file name (used for import files only, never for stored documents). */
    public static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT).strip();
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
