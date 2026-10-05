package com.cafepos.imports;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Suggests which column of an uploaded sheet is which field, by header name. Names are normalised (case, dots,
 * underscores) and compared with a synonym list. A suggestion is only a suggestion: the user sees and can change it.
 */
public final class HeaderMatcher {

    /** One mappable field. */
    public record FieldSpec(String key, String label, boolean required) {}

    private record Rule(FieldSpec spec, List<String> synonyms) {}

    private static final Map<String, List<Rule>> RULES = new LinkedHashMap<>();

    private static Rule r(String key, String label, boolean required, String... synonyms) {
        return new Rule(new FieldSpec(key, label, required), Arrays.stream(synonyms).map(HeaderMatcher::normalise).toList());
    }

    static {
        // Order = matching priority: specific fields first, generic ones (amount, total) last so they take what is left.
        RULES.put("EXPENSES", List.of(
                r("date", "Date", true, "date", "dt", "day", "txn date", "transaction date", "expense date", "voucher date", "bill date", "paid on", "on"),
                r("paidTo", "Paid to", false, "paid to", "vendor", "supplier", "payee", "party", "shop", "beneficiary", "to", "name", "vendor name", "party name"),
                r("method", "Payment mode", false, "mode", "payment mode", "payment method", "paid via", "paid by", "pay mode", "method", "via", "payment type", "mode of payment"),
                r("category", "Category", false, "category", "head", "expense type", "expense head", "expense category", "type", "account", "ledger", "group"),
                r("notes", "Notes", false, "notes", "note", "remarks", "remark", "comment", "comments", "narration", "description", "particulars", "details", "memo"),
                r("amount", "Amount", true, "amount", "amt", "rs", "inr", "total", "value", "cost", "price", "expense amount", "debit", "paid amount", "payment amount", "amount paid", "rupees")));
        RULES.put("DAILY_SALES", List.of(
                r("date", "Date", true, "date", "dt", "day", "sales date", "txn date", "transaction date", "business date"),
                r("cash", "Cash", false, "cash", "cash sales", "cash sale", "cash received"),
                r("upi", "UPI", false, "upi", "gpay", "phonepe", "paytm", "upi sales", "upi sale", "online upi", "qr"),
                r("card", "Card", false, "card", "card sales", "card sale", "pos", "debit card", "credit card"),
                r("whatsapp", "WhatsApp", false, "whatsapp", "whatsapp sales", "wa", "whatsapp orders"),
                r("swiggy", "Swiggy", false, "swiggy", "swiggy sales"),
                r("zomato", "Zomato", false, "zomato", "zomato sales"),
                r("discounts", "Discounts", false, "discount", "discounts", "disc", "less discount"),
                r("refunds", "Refunds", false, "refund", "refunds", "returns", "return"),
                r("ordersCount", "Number of orders", false, "orders", "no of orders", "number of orders", "order count", "bills", "no of bills", "bill count", "bill", "transactions", "covers"),
                r("notes", "Notes", false, "notes", "note", "remarks", "remark", "comment", "comments", "narration"),
                r("totalSales", "Total sales", true, "total sales", "sales", "total", "gross sales", "gross", "amount", "revenue", "net sales", "sale", "day sales", "turnover", "collection", "total sale", "grand total")));
        RULES.put("PURCHASES", List.of(
                r("invoiceDate", "Invoice date", false, "invoice date", "bill date", "inv date"),
                r("invoiceNo", "Invoice no.", false, "invoice no", "invoice number", "bill no", "bill number", "inv no", "invoice", "bill", "inv", "challan no", "voucher no"),
                r("date", "Purchase date", true, "date", "purchase date", "dt", "day", "txn date", "transaction date", "received on"),
                r("supplier", "Supplier", false, "supplier", "vendor", "party", "paid to", "seller", "shop", "supplier name", "vendor name", "party name", "name"),
                r("paymentStatus", "Payment status", false, "payment status", "status", "paid status", "paid unpaid"),
                r("notes", "Notes", false, "notes", "note", "remarks", "remark", "comment", "comments", "narration", "description", "particulars", "items"),
                r("amount", "Bill total", true, "total", "amount", "bill amount", "invoice amount", "grand total", "value", "amt", "rs", "net amount", "invoice value", "bill value", "total amount")));
    }

    private HeaderMatcher() {}

    public static boolean isTarget(String target) {
        return RULES.containsKey(target);
    }

    public static List<FieldSpec> fields(String target) {
        List<Rule> rules = RULES.get(target);
        if (rules == null) throw new IllegalArgumentException("Unknown import type: " + target);
        // Show the fields in a human order: required first, as listed otherwise.
        List<FieldSpec> out = new ArrayList<>();
        for (Rule x : rules) if (x.spec().required()) out.add(x.spec());
        for (Rule x : rules) if (!x.spec().required()) out.add(x.spec());
        return out;
    }

    public static Set<String> fieldKeys(String target) {
        Set<String> s = new HashSet<>();
        for (FieldSpec f : fields(target)) s.add(f.key());
        return s;
    }

    /** Lower-case, punctuation to spaces, single spaces: "Paid To:" -> "paid to", "Amt." -> "amt". */
    public static String normalise(String header) {
        if (header == null) return "";
        String s = header.toLowerCase(Locale.ROOT).replace("₹", " rs ").replace("&", " and ");
        s = s.replaceAll("[^a-z0-9]+", " ").trim();
        return s;
    }

    /** @return field key -> zero-based column index, only for fields where a column was found. */
    public static Map<String, Integer> suggest(String target, List<String> headers) {
        List<Rule> rules = RULES.get(target);
        if (rules == null) throw new IllegalArgumentException("Unknown import type: " + target);
        List<String> norm = new ArrayList<>();
        for (String h : headers) norm.add(normalise(h));
        Set<Integer> used = new HashSet<>();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Rule rule : rules) {
            int bestCol = -1;
            int bestScore = 0;
            for (int c = 0; c < norm.size(); c++) {
                if (used.contains(c) || norm.get(c).isEmpty()) continue;
                int score = score(norm.get(c), rule.synonyms());
                if (score > bestScore) {
                    bestScore = score;
                    bestCol = c;
                }
            }
            if (bestCol >= 0) {
                out.put(rule.spec().key(), bestCol);
                used.add(bestCol);
            }
        }
        // A purchase sheet that only has "Invoice Date" still has a date: reuse that column.
        if ("PURCHASES".equals(target) && !out.containsKey("date") && out.containsKey("invoiceDate")) {
            out.put("date", out.get("invoiceDate"));
        }
        return out;
    }

    /** Exact synonym = 100 + length; whole-word containment (synonyms of 4+ letters only) = 10 + length; otherwise 0. */
    private static int score(String header, List<String> synonyms) {
        int best = 0;
        String padded = " " + header + " ";
        for (String syn : synonyms) {
            if (header.equals(syn)) best = Math.max(best, 100 + syn.length());
            else if (syn.length() >= 4 && padded.contains(" " + syn + " ")) best = Math.max(best, 10 + syn.length());
        }
        return best;
    }
}
