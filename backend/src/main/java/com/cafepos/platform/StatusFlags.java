package com.cafepos.platform;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Derives the data-status labels shown next to every figure.
 * CONFIRMED, IMPORTED, MANUAL, ESTIMATED, PARTIAL, BACKDATED, CORRECTED (+ VOIDED).
 * MISSING is not a record property: it is derived from the ABSENCE of records (see Data Health).
 */
public final class StatusFlags {

    private static final Set<String> IMPORT_SOURCES = Set.of("EXCEL", "CSV", "IMPORT");

    private StatusFlags() {}

    public static List<String> of(String source, String confirmationState, boolean estimated, boolean partial,
                                  int revision, LocalDate txnDate, OffsetDateTime entryAt, ZoneId zone,
                                  String voidState) {
        List<String> flags = new ArrayList<>();
        if ("CONFIRMED".equals(confirmationState)) flags.add("CONFIRMED");
        if (IMPORT_SOURCES.contains(source)) flags.add("IMPORTED");
        else if ("MANUAL".equals(source)) flags.add("MANUAL");
        if (estimated) flags.add("ESTIMATED");
        if (partial) flags.add("PARTIAL");
        if (backdatedDays(txnDate, entryAt, zone) > 0) flags.add("BACKDATED");
        if (revision > 0) flags.add("CORRECTED");
        if ("VOIDED".equals(voidState)) flags.add("VOIDED");
        return flags;
    }

    /** Number of days between the transaction date and the date it was entered (0 if same day). */
    public static long backdatedDays(LocalDate txnDate, OffsetDateTime entryAt, ZoneId zone) {
        if (txnDate == null || entryAt == null) return 0;
        LocalDate entryDate = entryAt.atZoneSameInstant(zone).toLocalDate();
        long d = ChronoUnit.DAYS.between(txnDate, entryDate);
        return Math.max(d, 0);
    }
}
