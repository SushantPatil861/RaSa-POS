package com.cafepos.ocr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort reading of OCR text from an Indian bill / receipt into PROPOSED fields. Every field carries a
 * confidence (HIGH / MEDIUM / LOW); fields that cannot be found are null (unknown stays unknown). This only ever
 * produces a proposal for a human to review: nothing is posted from here.
 */
public final class BillTextParser {

    public record Field<T>(T value, String confidence) {}

    public record Item(String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount, String confidence) {}

    public record Parsed(Field<String> supplierName, Field<String> invoiceNo, Field<LocalDate> invoiceDate,
                         Field<String> gstin, Field<BigDecimal> total, List<Item> items) {}

    private static final String NUM = "\\d[\\d,]*(?:\\.\\d{1,2})?";
    private static final Pattern AMOUNT_TOKEN = Pattern.compile("(?<![\\d/-])" + NUM + "(?!\\d|/\\d|-\\d)");

    private static final Pattern GSTIN = Pattern.compile("\\b([0-3]\\d[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z])\\b");
    private static final Pattern INVOICE_NO = Pattern.compile(
            "(?i)\\b(?:tax\\s+invoice|invoice|inv|bill|receipt|memo|challan)\\s*(?:no\\b|number\\b|num\\b|#)\\.?\\s*[:\\-#.]?\\s*([A-Za-z0-9][A-Za-z0-9/\\-]{0,24})");
    private static final Pattern CASH_MEMO_NO = Pattern.compile("(?i)^(?:no|sr\\.? ?no)\\.?\\s*[:\\-#.]?\\s*(\\d{1,12})\\b");

    private static final Pattern DATE_DMY = Pattern.compile("\\b(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4}|\\d{2})\\b");
    private static final Pattern DATE_MON = Pattern.compile(
            "(?i)\\b(\\d{1,2})[\\s\\-/.]*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[\\s\\-/.,]*(\\d{4}|\\d{2})\\b");
    private static final Pattern DATE_ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final String MONTHS = "janfebmaraprmayjunjulaugsepoctnovdec";

    private static final Pattern SUPPLIER_SKIP = Pattern.compile(
            "(?i)^(?:tax\\s+invoice|invoice|cash\\s*memo|cash\\s*bill|credit\\s*memo|receipt|bill\\b|gstin|gst\\b|original|duplicate|"
                    + "estimate|quotation|ph\\b|phone|tel\\b|mob|mobile|e-?mail|www|http|fssai|pan\\b|date|customer|buyer|address|"
                    + "dl\\s*no|d\\.l|welcome|thank|subject|sold\\s+to|bill\\s+to|ship\\s+to|no\\b|sr\\b|sl\\b|time|table|cashier)");

    private static final Pattern ITEM_SKIP = Pattern.compile(
            "(?i)\\b(total|sub\\s*total|subtotal|grand|cgst|sgst|igst|gst|tax|round|discount|balance|paid|change|cash|upi|card|"
                    + "gstin|invoice|bill\\s*no|date|phone|mob|tel|fssai|hsn|amount\\s+in\\s+words|rupees|only|qty|rate|particulars|description|"
                    + "thank|visit|savings|payable|due|received|tendered)\\b");

    private static final Pattern ITEM_QTY_RATE = Pattern.compile(
            "^(?:\\d{1,3}\\s*[.)]\\s+)?(.*?[A-Za-z].*?)\\s+(\\d+(?:\\.\\d+)?)\\s*(?:kgs?|gms?|g|ltrs?|l|ml|pcs?|nos?|box|pkts?|doz|dz)?\\s*(?:x|X|\\*|@)\\s*(" + NUM + ")\\s*(?:=|-)?\\s*(" + NUM + ")\\s*$");
    private static final Pattern ITEM_TABULAR = Pattern.compile(
            "^(?:\\d{1,3}\\s*[.)]?\\s+)?(.*?[A-Za-z].*?)\\s+(\\d+(?:\\.\\d+)?)\\s*(?:kgs?|gms?|g|ltrs?|l|ml|pcs?|nos?|box|pkts?|bags?|btl|units?|doz|dz)?\\s+("
                    + NUM + ")\\s+(\\d[\\d,]*\\.\\d{2})\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_LEADING_QTY = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*(?:x|X|\\*)?\\s+(.*?[A-Za-z].*?)\\s+(" + NUM + ")\\s+(\\d[\\d,]*\\.\\d{2})\\s*$");
    private static final Pattern ITEM_AMOUNT_ONLY = Pattern.compile("^(?:\\d{1,3}\\s*[.)]\\s+)?(.*?[A-Za-z]{2}.*?)\\s+(\\d[\\d,]*\\.\\d{2})\\s*$");

    private BillTextParser() {}

    public static Parsed parse(String rawText) {
        List<String> lines = lines(rawText);
        List<Item> items = items(lines);
        return new Parsed(supplier(lines), invoiceNo(lines), invoiceDate(lines), gstin(lines), total(lines, items), items);
    }

    // ------------------------------------------------------------------ lines

    static List<String> lines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String l : raw.split("\\R")) {
            String s = l.replace('₹', ' ').replace('|', ' ').replace('\t', ' ').replaceAll("\\s+", " ").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ supplier

    private static Field<String> supplier(List<String> lines) {
        int limit = Math.min(lines.size(), 8);
        for (int i = 0; i < limit; i++) {
            String l = lines.get(i);
            if (SUPPLIER_SKIP.matcher(l).find()) continue;
            if (GSTIN.matcher(l.toUpperCase(Locale.ROOT)).find()) continue;
            int letters = 0, nonSpace = 0;
            for (char c : l.toCharArray()) {
                if (!Character.isWhitespace(c)) nonSpace++;
                if (Character.isLetter(c)) letters++;
            }
            if (letters < 3 || letters * 2 < nonSpace) continue;
            String name = l.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}.)]+$", "").trim();
            if (name.isEmpty()) continue;
            return new Field<>(name, i < 3 ? "MEDIUM" : "LOW");
        }
        return null;
    }

    // ------------------------------------------------------------------ invoice number

    private static Field<String> invoiceNo(List<String> lines) {
        for (String l : lines) {
            Matcher m = INVOICE_NO.matcher(l);
            while (m.find()) {
                String token = m.group(1);
                if (token.chars().anyMatch(Character::isDigit)) return new Field<>(token, "HIGH");
            }
        }
        for (int i = 0; i < Math.min(lines.size(), 12); i++) {
            Matcher m = CASH_MEMO_NO.matcher(lines.get(i));
            if (m.find()) return new Field<>(m.group(1), "MEDIUM");
        }
        return null;
    }

    // ------------------------------------------------------------------ date

    private static final Pattern DUE = Pattern.compile("(?i)\\b(due|expiry|expires|expiring)\\b");

    private static Field<LocalDate> invoiceDate(List<String> lines) {
        LocalDate firstAny = null;
        for (String l : lines) {
            // "Bill Date: 2026-05-09   Due Date: 2026-05-24" -> only look at the part before "Due"
            Matcher due = DUE.matcher(l);
            String part = due.find() ? l.substring(0, due.start()) : l;
            LocalDate d = firstDate(part);
            if (d == null) continue;
            if (part.toLowerCase(Locale.ROOT).contains("date")) return new Field<>(d, "HIGH");
            if (firstAny == null) firstAny = d;
        }
        return firstAny == null ? null : new Field<>(firstAny, "MEDIUM");
    }

    static LocalDate firstDate(String line) {
        Matcher m = DATE_ISO.matcher(line);
        while (m.find()) {
            LocalDate d = make(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            if (d != null) return d;
        }
        m = DATE_MON.matcher(line);
        while (m.find()) {
            int mon = MONTHS.indexOf(m.group(2).toLowerCase(Locale.ROOT)) / 3 + 1;
            LocalDate d = make(year(m.group(3)), mon, Integer.parseInt(m.group(1)));
            if (d != null) return d;
        }
        m = DATE_DMY.matcher(line);
        while (m.find()) {
            LocalDate d = make(year(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            if (d != null) return d;
        }
        return null;
    }

    private static int year(String y) {
        int v = Integer.parseInt(y);
        return y.length() == 2 ? 2000 + v : v;
    }

    private static LocalDate make(int y, int mon, int d) {
        if (y < 2000 || y > 2100) return null;
        try {
            return LocalDate.of(y, mon, d);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ GSTIN

    private static Field<String> gstin(List<String> lines) {
        for (String l : lines) {
            String up = l.toUpperCase(Locale.ROOT);
            Matcher m = GSTIN.matcher(up);
            if (!m.find()) {
                m = GSTIN.matcher(up.replace(" ", "")); // "27 AAPFU0939F1ZV" split by the OCR
                if (!m.find()) continue;
            }
            String g = m.group(1);
            int state = Integer.parseInt(g.substring(0, 2));
            if (state < 1 || state > 38) continue;
            return new Field<>(g, validGstinChecksum(g) ? "HIGH" : "MEDIUM");
        }
        return null;
    }

    /** GSTIN check character (mod-36 weighted sum). A failed check usually means an OCR misread digit. */
    public static boolean validGstinChecksum(String g) {
        if (g == null || g.length() != 15) return false;
        String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int v = chars.indexOf(g.charAt(i));
            if (v < 0) return false;
            int product = v * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        int check = (36 - sum % 36) % 36;
        return chars.charAt(check) == g.charAt(14);
    }

    // ------------------------------------------------------------------ items

    private static List<Item> items(List<String> lines) {
        List<Item> out = new ArrayList<>();
        for (String l : lines) {
            if (out.size() >= 100) break;
            if (ITEM_SKIP.matcher(l).find()) continue;
            Item it = item(l);
            if (it != null) out.add(it);
        }
        return out;
    }

    private static Item item(String l) {
        Matcher m = ITEM_QTY_RATE.matcher(l);
        if (m.matches()) return build(m.group(1), m.group(2), m.group(3), m.group(4));
        m = ITEM_TABULAR.matcher(l);
        if (m.matches()) return build(m.group(1), m.group(2), m.group(3), m.group(4));
        m = ITEM_LEADING_QTY.matcher(l);
        if (m.matches()) return build(m.group(2), m.group(1), m.group(3), m.group(4));
        m = ITEM_AMOUNT_ONLY.matcher(l);
        if (m.matches()) {
            String name = cleanName(m.group(1));
            BigDecimal amt = num(m.group(2));
            if (name == null || amt == null) return null;
            return new Item(name, null, null, amt, "LOW");
        }
        return null;
    }

    private static Item build(String rawName, String q, String r, String a) {
        String name = cleanName(rawName);
        BigDecimal qty = num(q), rate = num(r), amt = num(a);
        if (name == null || qty == null || rate == null || amt == null || qty.signum() <= 0) return null;
        BigDecimal expected = qty.multiply(rate);
        BigDecimal tolerance = new BigDecimal("0.51").max(amt.multiply(new BigDecimal("0.01")));
        boolean consistent = expected.subtract(amt).abs().compareTo(tolerance) <= 0;
        return new Item(name, qty, rate, amt, consistent ? "HIGH" : "LOW");
    }

    private static String cleanName(String s) {
        String n = s.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N})]+$", "").replaceAll("\\s+", " ").trim();
        long letters = n.chars().filter(Character::isLetter).count();
        return letters >= 2 ? n : null;
    }

    private static BigDecimal num(String s) {
        try {
            return new BigDecimal(s.replace(",", "")).setScale(2, RoundingMode.HALF_UP);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ total

    private static final Pattern P1 = Pattern.compile("(?i)\\b(grand\\s*total|amount\\s+payable|net\\s+payable|net\\s+amount|total\\s+payable|balance\\s+payable|amount\\s+due|net\\s+total)\\b");
    private static final Pattern P2 = Pattern.compile("(?i)\\b(total\\s+amount|bill\\s+total|invoice\\s+total|invoice\\s+value|bill\\s+amount|total)\\b");
    private static final Pattern P2_NOT = Pattern.compile("(?i)\\b(sub\\s*total|subtotal|total\\s+(?:qty|quantity|items?|tax|gst|cgst|sgst|igst|discount|savings|saved|units?|pcs)|round)\\b");
    private static final BigDecimal MAX_PLAUSIBLE = new BigDecimal("10000000");

    private static Field<BigDecimal> total(List<String> lines, List<Item> items) {
        List<BigDecimal> p1 = new ArrayList<>(), p2 = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            boolean isP1 = P1.matcher(l).find();
            boolean isP2 = !isP1 && P2.matcher(l).find() && !P2_NOT.matcher(l).find();
            if (!isP1 && !isP2) continue;
            BigDecimal v = lastAmount(l);
            if (v == null && i + 1 < lines.size()) {
                String next = lines.get(i + 1);
                if (next.replaceAll("[^A-Za-z]", "").isEmpty()) v = lastAmount(next);
            }
            if (v == null || v.signum() <= 0 || v.compareTo(MAX_PLAUSIBLE) >= 0) continue;
            (isP1 ? p1 : p2).add(v);
        }
        BigDecimal chosen;
        String conf;
        if (!p1.isEmpty()) {
            chosen = max(p1);
            conf = "HIGH";
        } else if (!p2.isEmpty()) {
            chosen = max(p2);
            conf = "MEDIUM";
        } else {
            chosen = null;
            for (String l : lines) {
                Matcher m = Pattern.compile("(\\d[\\d,]*\\.\\d{2})\\b").matcher(l);
                while (m.find()) {
                    BigDecimal v = num(m.group(1));
                    if (v != null && v.signum() > 0 && v.compareTo(MAX_PLAUSIBLE) < 0 && (chosen == null || v.compareTo(chosen) > 0)) chosen = v;
                }
            }
            if (chosen == null) return null;
            return new Field<>(chosen, "LOW");
        }
        if (!items.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Item it : items) sum = sum.add(it.amount());
            BigDecimal tol = new BigDecimal("1.00").max(chosen.multiply(new BigDecimal("0.01")));
            if (sum.subtract(chosen).abs().compareTo(tol) <= 0) conf = "HIGH";
        }
        return new Field<>(chosen, conf);
    }

    private static BigDecimal max(List<BigDecimal> l) {
        BigDecimal m = l.get(0);
        for (BigDecimal v : l) if (v.compareTo(m) > 0) m = v;
        return m;
    }

    /** The last money-looking number on a line ("GRAND TOTAL Rs. 1,234.50/-" -> 1234.50). */
    static BigDecimal lastAmount(String line) {
        Matcher m = AMOUNT_TOKEN.matcher(line);
        BigDecimal last = null;
        while (m.find()) {
            String t = m.group();
            if (t.replace(",", "").replace(".", "").length() >= 10) continue; // phone numbers
            BigDecimal v = num(t);
            if (v != null) last = v;
        }
        return last;
    }
}
