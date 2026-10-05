package com.cafepos.imports;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Date parsing for imports. Formats are written the way people say them (dd/MM/yyyy). India reads day first, so
 * DD/MM wins whenever a column is ambiguous (05/06/2026 is 5 June). Parsing is strict: 31/02/2026 is an error.
 */
public final class ImportDates {

    /** Order matters: earlier = preferred when a sample set parses equally well in several formats. */
    public static final List<String> FORMATS = List.of(
            "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "dd/MM/yy", "dd-MM-yy", "dd.MM.yy",
            "d-MMM-yyyy", "d MMM yyyy", "d-MMM-yy", "d MMM yy", "d MMMM yyyy",
            "yyyy-MM-dd", "yyyy/MM/dd", "MM/dd/yyyy", "MM-dd-yyyy");

    private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})([ T].*)?$");
    private static final Map<String, DateTimeFormatter> CACHE = new HashMap<>();

    private ImportDates() {}

    private static synchronized DateTimeFormatter formatter(String friendly) {
        return CACHE.computeIfAbsent(friendly, f -> {
            String p = f.replace("yyyy", "uuuu").replace("yy", "uu")
                    .replaceAll("(?<!M)MM(?!M)", "M").replace("dd", "d");
            return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p)
                    .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
        });
    }

    /** @return the date, or null when the text is blank or does not match (see {@link #parseStrict}). */
    public static LocalDate parse(String text, String format) {
        try {
            return parseStrict(text, format);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * ISO text (what the XLSX reader produces) is always accepted. Anything else must match {@code format}.
     * @throws IllegalArgumentException when the text is not a valid date in that format
     */
    public static LocalDate parseStrict(String text, String format) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("blank date");
        String s = text.trim().replaceAll("(?i)\\bsept\\b", "Sep").replaceAll("\\s+", " ");
        var m = ISO.matcher(s);
        if (m.matches()) {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        return LocalDate.parse(s, formatter(format == null ? "dd/MM/yyyy" : format));
    }

    /** True when the text is an ISO date (the XLSX reader normalises real Excel dates to this). */
    public static boolean isIso(String text) {
        return text != null && ISO.matcher(text.trim()).matches();
    }

    /** Picks the format that parses the most samples; ties go to the earlier (DD/MM first) entry. */
    public static String detect(List<String> samples) {
        String best = "dd/MM/yyyy";
        int bestCount = 0;
        boolean sawNonIso = false;
        for (String s : samples) if (s != null && !s.isBlank() && !isIso(s)) sawNonIso = true;
        if (!sawNonIso) {
            for (String s : samples) if (isIso(s)) return "yyyy-MM-dd";
            return best;
        }
        for (String f : FORMATS) {
            int count = 0;
            for (String s : samples) {
                if (s == null || s.isBlank() || isIso(s)) continue;
                if (parse(s, f) != null) count++;
            }
            if (count > bestCount) {
                best = f;
                bestCount = count;
            }
        }
        return best;
    }
}
