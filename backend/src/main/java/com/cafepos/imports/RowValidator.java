package com.cafepos.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates and normalises ONE imported row. Pure: no database. Duplicate detection against existing records is
 * done by the service using the values returned here (see {@link Result#mapped()}).
 *
 * Normalised values (all strings, ready to store as JSON): date / invoiceDate = ISO yyyy-MM-dd, money = "1234.50",
 * ordersCount = "12", method = CASH|UPI|CARD|BANK|OTHER, paymentStatus = PAID|UNPAID|PARTIAL.
 */
public final class RowValidator {

    public record Result(boolean ok, String error, Map<String, String> mapped) {}

    private static final List<String> DAILY_MONEY = List.of("cash", "upi", "card", "whatsapp", "swiggy", "zomato", "discounts", "refunds");

    private RowValidator() {}

    public static Result validate(String target, List<String> row, Map<String, Integer> mapping, String dateFormat,
                                  LocalDate dataStart, LocalDate today) {
        List<String> errors = new ArrayList<>();
        Map<String, String> m = new LinkedHashMap<>();

        LocalDate date = date("date", "Date", row, mapping, dateFormat, errors);
        if (date != null) {
            if (date.isAfter(today)) errors.add("Date " + date + " is in the future");
            else if (dataStart != null && date.isBefore(dataStart)) errors.add("Date " + date + " is before the business data-start date " + dataStart);
            m.put("date", date.toString());
        }

        switch (target) {
            case "EXPENSES" -> {
                BigDecimal amount = money("amount", "Amount", true, row, mapping, errors);
                if (amount != null) {
                    if (amount.signum() < 0) errors.add("Amount cannot be negative");
                    m.put("amount", amount.toPlainString());
                }
                put(m, "category", text(row, mapping, "category"));
                put(m, "paidTo", text(row, mapping, "paidTo"));
                String method = text(row, mapping, "method");
                if (method != null) m.put("method", paymentMethod(method));
                put(m, "notes", text(row, mapping, "notes"));
            }
            case "PURCHASES" -> {
                BigDecimal amount = money("amount", "Bill total", true, row, mapping, errors);
                if (amount != null) {
                    if (amount.signum() < 0) errors.add("Bill total cannot be negative");
                    m.put("amount", amount.toPlainString());
                }
                put(m, "supplier", text(row, mapping, "supplier"));
                put(m, "invoiceNo", text(row, mapping, "invoiceNo"));
                String invDate = text(row, mapping, "invoiceDate");
                if (invDate != null && mapping.get("invoiceDate") != null && !mapping.get("invoiceDate").equals(mapping.get("date"))) {
                    try {
                        LocalDate d = ImportDates.parseStrict(invDate, dateFormat);
                        if (d.isAfter(today)) errors.add("Invoice date " + d + " is in the future");
                        m.put("invoiceDate", d.toString());
                    } catch (RuntimeException e) {
                        errors.add("Invoice date '" + invDate + "' is not a valid " + dateFormat + " date");
                    }
                }
                String ps = text(row, mapping, "paymentStatus");
                if (ps != null) {
                    String norm = paymentStatus(ps);
                    if (norm == null) errors.add("Payment status '" + ps + "' is not understood (use Paid, Unpaid or Partial)");
                    else m.put("paymentStatus", norm);
                }
                put(m, "notes", text(row, mapping, "notes"));
            }
            case "DAILY_SALES" -> {
                BigDecimal total = money("totalSales", "Total sales", true, row, mapping, errors);
                if (total != null) {
                    if (total.signum() < 0) errors.add("Total sales cannot be negative");
                    m.put("totalSales", total.toPlainString());
                }
                BigDecimal disc = BigDecimal.ZERO, ref = BigDecimal.ZERO;
                for (String k : DAILY_MONEY) {
                    BigDecimal v = money(k, label(k), false, row, mapping, errors);
                    if (v == null) continue;
                    if (v.signum() < 0) errors.add(label(k) + " cannot be negative");
                    m.put(k, v.toPlainString());
                    if (k.equals("discounts")) disc = v;
                    if (k.equals("refunds")) ref = v;
                }
                if (total != null && disc.add(ref).compareTo(total) > 0) errors.add("Discounts plus refunds are more than total sales");
                String oc = text(row, mapping, "ordersCount");
                if (oc != null) {
                    try {
                        BigDecimal n = IndianAmount.parse(oc);
                        if (n == null || n.signum() < 0 || n.stripTrailingZeros().scale() > 0) throw new NumberFormatException();
                        m.put("ordersCount", String.valueOf(n.intValueExact()));
                    } catch (RuntimeException e) {
                        errors.add("Number of orders '" + oc + "' is not a whole number");
                    }
                }
                put(m, "notes", text(row, mapping, "notes"));
            }
            default -> errors.add("Unknown import type " + target);
        }
        return new Result(errors.isEmpty(), String.join("; ", errors), m);
    }

    /** Key used to spot the same record twice inside one file (and to describe it to the user). */
    public static String dedupeKey(String target, Map<String, String> m) {
        String d = m.getOrDefault("date", "");
        return switch (target) {
            case "EXPENSES" -> d + "|" + m.getOrDefault("amount", "") + "|"
                    + lower(m.get("paidTo") != null ? m.get("paidTo") : m.get("category"));
            case "PURCHASES" -> d + "|" + m.getOrDefault("amount", "") + "|" + lower(m.get("supplier")) + "|" + lower(m.get("invoiceNo"));
            default -> d;
        };
    }


    // ------------------------------------------------------------------ duplicates against existing records

    /** An existing ACTIVE record, reduced to what is compared: expense (party = paid to, ref = category), purchase (party = supplier, ref = invoice no). */
    public record Existing(LocalDate date, BigDecimal amount, String party, String ref) {}

    /** @return a plain-English description of the first existing record this row duplicates, or null. */
    public static String duplicateOf(String target, Map<String, String> m, List<Existing> existing) {
        String d = m.get("date");
        if (d == null) return null;
        LocalDate date = LocalDate.parse(d);
        BigDecimal amount = m.get(amountKey(target)) == null ? null : new BigDecimal(m.get(amountKey(target)));
        for (Existing e : existing) {
            if (!date.equals(e.date())) continue;
            switch (target) {
                case "DAILY_SALES" -> {
                    return "A quick daily entry already exists for " + date + " (only one is allowed per day)";
                }
                case "EXPENSES" -> {
                    if (amount == null || e.amount().compareTo(amount) != 0) continue;
                    String payee = lower(m.get("paidTo")), cat = lower(m.get("category"));
                    boolean noKey = payee.isEmpty() && cat.isEmpty();
                    if (noKey || (!payee.isEmpty() && payee.equals(lower(e.party()))) || (!cat.isEmpty() && cat.equals(lower(e.ref())))) {
                        return "Looks like an expense already recorded on " + date + " for the same amount"
                                + (noKey ? "" : " and payee/category");
                    }
                }
                case "PURCHASES" -> {
                    if (amount == null || e.amount().compareTo(amount) != 0) continue;
                    String sup = lower(m.get("supplier")), inv = lower(m.get("invoiceNo"));
                    if (sup.isEmpty() || sup.equals(lower(e.party())) || (!inv.isEmpty() && inv.equals(lower(e.ref())))) {
                        return "Looks like a purchase already recorded on " + date + " for the same amount"
                                + (sup.isEmpty() ? "" : " and supplier");
                    }
                }
                default -> { }
            }
        }
        return null;
    }

    private static String amountKey(String target) {
        return "DAILY_SALES".equals(target) ? "totalSales" : "amount";
    }

    // ------------------------------------------------------------------ helpers

    public static String paymentMethod(String text) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        if (t.contains("cash")) return "CASH";
        if (t.contains("upi") || t.contains("gpay") || t.contains("google pay") || t.contains("phonepe") || t.contains("paytm") || t.contains("bhim") || t.equals("qr")) return "UPI";
        if (t.contains("card") || t.contains("pos") || t.contains("visa") || t.contains("master")) return "CARD";
        if (t.contains("bank") || t.contains("neft") || t.contains("imps") || t.contains("rtgs") || t.contains("cheque") || t.contains("check") || t.contains("transfer") || t.contains("online")) return "BANK";
        return "OTHER";
    }

    /** @return PAID, UNPAID or PARTIAL; null when the text is not understood. */
    public static String paymentStatus(String text) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        if (t.contains("part")) return "PARTIAL";
        if (t.contains("not paid") || t.contains("unpaid") || t.contains("pending") || t.contains("credit") || t.contains("due") || t.equals("no") || t.equals("n")) return "UNPAID";
        if (t.contains("paid") || t.equals("yes") || t.equals("y") || t.contains("settled") || t.contains("cleared")) return "PAID";
        return null;
    }

    private static String label(String k) {
        return switch (k) {
            case "cash" -> "Cash";
            case "upi" -> "UPI";
            case "card" -> "Card";
            case "whatsapp" -> "WhatsApp";
            case "swiggy" -> "Swiggy";
            case "zomato" -> "Zomato";
            case "discounts" -> "Discounts";
            case "refunds" -> "Refunds";
            default -> k;
        };
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null) m.put(k, v);
    }

    /** Trimmed cell for a mapped field, or null if the field is not mapped / the cell is blank. */
    static String text(List<String> row, Map<String, Integer> mapping, String field) {
        Integer idx = mapping.get(field);
        if (idx == null || idx < 0 || idx >= row.size()) return null;
        String v = row.get(idx);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static LocalDate date(String field, String label, List<String> row, Map<String, Integer> mapping,
                                  String dateFormat, List<String> errors) {
        String raw = text(row, mapping, field);
        if (raw == null) {
            errors.add(label + " is missing");
            return null;
        }
        try {
            return ImportDates.parseStrict(raw, dateFormat);
        } catch (RuntimeException e) {
            errors.add(label + " '" + raw + "' is not a valid " + dateFormat + " date");
            return null;
        }
    }

    private static BigDecimal money(String field, String label, boolean required, List<String> row,
                                    Map<String, Integer> mapping, List<String> errors) {
        String raw = text(row, mapping, field);
        if (raw == null) {
            if (required) errors.add(label + " is missing");
            return null;
        }
        try {
            BigDecimal v = IndianAmount.parse(raw);
            if (v == null && required) errors.add(label + " is missing");
            return v;
        } catch (NumberFormatException e) {
            errors.add(label + " '" + raw + "' is not a valid amount");
            return null;
        }
    }
}
