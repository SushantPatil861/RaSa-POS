package com.cafepos.whatsapp;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a free-text WhatsApp order ("2 choco waffle, 1 mango shake pls", "do cold coffee aur ek brownie") into
 * proposed order lines by matching against the menu in force today. It NEVER guesses silently: a piece that matches
 * nothing, or that matches several menu items equally well, goes to {@code unmatched} for staff to resolve.
 * Prices are not decided here (the order service uses today's menu price).
 */
public final class OrderTextParser {

    /** One orderable menu line (a product variant on today's menu) plus the product's aliases. */
    public record MenuEntry(long productId, long variantId, String name, String variantName, BigDecimal price,
                            List<String> aliases) {}

    /** confidence: 1.0 = exact name; below ~0.85 staff should double-check. */
    public record Item(String text, long productId, long variantId, String name, BigDecimal qty, BigDecimal unitPrice,
                       double confidence) {}

    public record Result(List<Item> items, List<String> unmatched) {}

    private static final double THRESHOLD = 0.6;
    private static final double AMBIGUITY_GAP = 0.04;

    private static final Map<String, Integer> NUMBER_WORDS = new HashMap<>();
    private static final Set<String> FILLER = new HashSet<>(Arrays.asList(
            "please", "pls", "plz", "plss", "kindly", "bhai", "bhaiya", "bhaiyya", "didi", "ji", "yaar", "sir", "madam", "mam",
            "want", "wanted", "order", "send", "give", "dena", "de", "do", "chahiye", "chahie", "chaiye", "chahiyen", "hai", "hain",
            "mujhe", "mereko", "mujko", "humko", "hume", "liye", "for", "me", "need", "would", "like", "i", "we", "also",
            "thanks", "thank", "you", "hi", "hello", "hey", "hii", "namaste", "good", "morning", "evening", "afternoon",
            "ka", "ki", "ke", "ko", "ok", "okay", "na", "bhi", "wala", "wali", "a", "an", "the", "of", "to", "can", "get"));
    private static final Set<String> UNITS = new HashSet<>(Arrays.asList(
            "x", "pcs", "pc", "piece", "pieces", "plate", "plates", "glass", "glasses", "cup", "cups", "nos", "no", "qty", "bowl", "bowls", "scoop", "scoops"));
    private static final Pattern SPLIT_COMMA = Pattern.compile("[,;\\n\\r|•+]+");
    private static final Pattern SPLIT_AND = Pattern.compile("(?i)\\s+(?:and|aur|&|plus|tatha)\\s+");

    static {
        String[] en = {"one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};
        for (int i = 0; i < en.length; i++) NUMBER_WORDS.put(en[i], i + 1);
        NUMBER_WORDS.put("ek", 1);
        NUMBER_WORDS.put("do", 2);
        NUMBER_WORDS.put("teen", 3);
        NUMBER_WORDS.put("char", 4);
        NUMBER_WORDS.put("chaar", 4);
        NUMBER_WORDS.put("paanch", 5);
        NUMBER_WORDS.put("panch", 5);
        NUMBER_WORDS.put("chhe", 6);
        NUMBER_WORDS.put("saat", 7);
        NUMBER_WORDS.put("aath", 8);
        NUMBER_WORDS.put("nau", 9);
        NUMBER_WORDS.put("das", 10);
    }

    private OrderTextParser() {}

    // ------------------------------------------------------------------ public API

    public static Result parse(String text, List<MenuEntry> menu) {
        List<Item> items = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        if (text == null || text.isBlank()) return new Result(items, unmatched);
        List<Prepared> prepared = new ArrayList<>();
        for (MenuEntry e : menu) prepared.add(new Prepared(e));

        for (String seg : SPLIT_COMMA.split(text)) {
            String s = seg.trim();
            if (s.isEmpty()) continue;
            Outcome o = matchSegment(s, prepared);
            if (o.kind == Kind.FAIL && SPLIT_AND.split(s).length > 1) {
                for (String part : SPLIT_AND.split(s)) {
                    if (part.isBlank()) continue;
                    collect(matchSegment(part.trim(), prepared), items, unmatched);
                }
            } else {
                collect(o, items, unmatched);
            }
        }
        return new Result(mergeSame(items), unmatched);
    }

    /** Lower-case word tokens (letters and digits only). */
    public static List<String> tokens(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT).replace('×', ' ');
        n = n.replaceAll("(?<=\\d)x\\b", " x ").replaceAll("\\bx(?=\\d)", " x ");   // "2x" / "x2"
        n = n.replaceAll("[^a-z0-9\\u0900-\\u097f]+", " ").trim();
        List<String> out = new ArrayList<>();
        if (!n.isEmpty()) out.addAll(Arrays.asList(n.split(" ")));
        return out;
    }

    // ------------------------------------------------------------------ internals

    private enum Kind { MATCH, DROP, FAIL }

    private static final class Outcome {
        Kind kind;
        Item item;
        String failText;
    }

    private static void collect(Outcome o, List<Item> items, List<String> unmatched) {
        if (o.kind == Kind.MATCH) items.add(o.item);
        else if (o.kind == Kind.FAIL) unmatched.add(o.failText);
    }

    private static final class Prepared {
        final MenuEntry entry;
        final List<List<String>> names = new ArrayList<>();   // display name + aliases, as token lists
        final List<String> variantTokens;

        Prepared(MenuEntry e) {
            this.entry = e;
            names.add(stem(tokens(e.name())));
            if (e.aliases() != null) for (String a : e.aliases()) if (a != null && !a.isBlank()) names.add(stem(tokens(a)));
            List<String> v = stem(tokens(e.variantName() == null ? "" : e.variantName()));
            v.remove("regular");
            this.variantTokens = v;
        }
    }

    private static List<String> stem(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String t : in) out.add(stemToken(t));
        return out;
    }

    private static String stemToken(String t) {
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) return t.substring(0, t.length() - 1);
        return t;
    }

    private static final class Attempt {
        BigDecimal qty;
        List<String> q;
        double best;
        List<Scored> top = new ArrayList<>();
    }

    private static Attempt attempt(List<String> tokens, BigDecimal qty, List<Prepared> menu) {
        List<String> t = new ArrayList<>(tokens);
        t.removeIf(FILLER::contains);
        t.removeIf(x -> x.equals("x"));
        Attempt a = new Attempt();
        a.qty = qty;
        a.q = stem(t);
        if (a.q.isEmpty()) return a;
        List<Scored> scored = new ArrayList<>();
        for (Prepared p : menu) {
            double s = score(a.q, p);
            if (s > 0) scored.add(new Scored(p, s));
            a.best = Math.max(a.best, s);
        }
        for (Scored s : scored) if (s.score >= a.best - AMBIGUITY_GAP) a.top.add(s);
        return a;
    }

    private static Outcome matchSegment(String original, List<Prepared> menu) {
        Outcome out = new Outcome();
        List<String> all = tokens(original);
        List<String> withoutQty = new ArrayList<>(all);
        BigDecimal qty = takeQuantity(withoutQty);
        Attempt a = attempt(withoutQty, qty, menu);
        if (qty != null) {
            // "Two Scoop Sundae": the leading number may be part of the product name. Keep the better reading.
            Attempt whole = attempt(all, null, menu);
            if (whole.best > a.best) a = whole;
        }
        if (a.q.isEmpty()) {
            out.kind = Kind.DROP;
            return out;
        }
        BigDecimal quantity = a.qty == null ? BigDecimal.ONE : a.qty;
        if (a.best < THRESHOLD) {
            out.kind = Kind.FAIL;
            out.failText = original.trim();
            return out;
        }
        if (a.top.size() > 1) {
            Set<String> labels = new LinkedHashSet<>();
            for (Scored s : a.top) {
                if (labels.size() >= 4) break;
                labels.add(s.p.entry.name() + (s.p.variantTokens.isEmpty() ? "" : " (" + s.p.entry.variantName() + ")"));
            }
            out.kind = Kind.FAIL;
            out.failText = original.trim() + "  [could be: " + String.join(", ", labels) + "]";
            return out;
        }
        MenuEntry e = a.top.get(0).p.entry;
        String shown = e.name() + ("Regular".equalsIgnoreCase(e.variantName()) ? "" : " (" + e.variantName() + ")");
        out.kind = Kind.MATCH;
        out.item = new Item(original.trim(), e.productId(), e.variantId(), shown, quantity, e.price(),
                Math.round(a.top.get(0).score * 100.0) / 100.0);
        return out;
    }

    private record Scored(Prepared p, double score) {}

    /** Removes a quantity from the tokens and returns it (null when none was found). */
    private static BigDecimal takeQuantity(List<String> t) {
        if (t.isEmpty()) return null;
        // leading (after polite words like "bhaiya"): "2", "two", "ek", "do" (only when more words follow) + optional unit word
        int at = 0;
        while (at < t.size() && FILLER.contains(t.get(at)) && !t.get(at).equals("do")) at++;
        if (at < t.size()) {
            Integer lead = numberOf(t.get(at), t.size() > at + 1);
            if (lead != null) {
                t.remove(at);
                if (at < t.size() && UNITS.contains(t.get(at))) t.remove(at);
                return BigDecimal.valueOf(lead);
            }
        }
        // "x2" written as "x 2" anywhere, or a trailing number / number word ("choco waffle 2", "waffle x 2")
        for (int i = 0; i + 1 < t.size(); i++) {
            if (t.get(i).equals("x") || t.get(i).equals("qty")) {
                Integer n = numberOf(t.get(i + 1), true);
                if (n != null) {
                    t.remove(i + 1);
                    t.remove(i);
                    return BigDecimal.valueOf(n);
                }
            }
        }
        if (t.size() > 1) {
            Integer n = numberOf(t.get(t.size() - 1), false);
            if (n != null) {
                t.remove(t.size() - 1);
                return BigDecimal.valueOf(n);
            }
        }
        return null;
    }

    private static Integer numberOf(String token, boolean wordsAllowDo) {
        if (token.matches("\\d{1,2}")) {
            int v = Integer.parseInt(token);
            return v >= 1 ? v : null;
        }
        Integer w = NUMBER_WORDS.get(token);
        if (w == null) return null;
        if (token.equals("do") && !wordsAllowDo) return null;
        return w;
    }

    /** Dice-style overlap, best over the product name and its aliases; variant words in the query are credited. */
    private static double score(List<String> query, Prepared p) {
        List<String> q = new ArrayList<>(query);
        if (!p.variantTokens.isEmpty() && q.containsAll(p.variantTokens)) q.removeAll(p.variantTokens);
        if (q.isEmpty()) return 0;
        double best = 0;
        for (List<String> cand : p.names) {
            if (cand.isEmpty()) continue;
            double match = 0;
            boolean[] used = new boolean[cand.size()];
            for (String qt : q) {
                int bi = -1;
                double bs = 0;
                for (int i = 0; i < cand.size(); i++) {
                    if (used[i]) continue;
                    double s = tokenSim(qt, cand.get(i));
                    if (s > bs) {
                        bs = s;
                        bi = i;
                    }
                }
                if (bi >= 0 && bs > 0) {
                    used[bi] = true;
                    match += bs;
                }
            }
            best = Math.max(best, 2 * match / (q.size() + cand.size()));
        }
        return best;
    }

    private static double tokenSim(String a, String b) {
        if (a.equals(b)) return 1.0;
        int min = Math.min(a.length(), b.length());
        if (min >= 4 && (a.startsWith(b) || b.startsWith(a))) return 0.8;   // "choco" ~ "chocolate"
        int common = 0;
        while (common < min && a.charAt(common) == b.charAt(common)) common++;
        if (min >= 5 && common >= min - 1) return 0.8;                       // "berry" ~ "berrie"
        return 0;
    }

    private static List<Item> mergeSame(List<Item> items) {
        Map<Long, Item> byVariant = new LinkedHashMap<>();
        for (Item i : items) {
            Item prev = byVariant.get(i.variantId());
            if (prev == null) {
                byVariant.put(i.variantId(), i);
            } else {
                byVariant.put(i.variantId(), new Item(prev.text() + " + " + i.text(), prev.productId(), prev.variantId(),
                        prev.name(), prev.qty().add(i.qty()), prev.unitPrice(), Math.min(prev.confidence(), i.confidence())));
            }
        }
        return new ArrayList<>(byVariant.values());
    }
}
