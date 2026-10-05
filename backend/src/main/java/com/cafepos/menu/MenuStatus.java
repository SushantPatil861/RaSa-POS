package com.cafepos.menu;

import java.time.LocalDate;

/**
 * Menu version status. The database stores only DRAFT or SCHEDULED; ACTIVE and ARCHIVED are RESOLVED from the
 * effective dates, so the right menu is always live even if a nightly job did not run.
 */
public enum MenuStatus {
    DRAFT, SCHEDULED, ACTIVE, ARCHIVED;

    /**
     * @param stored            "DRAFT" or "SCHEDULED" as stored
     * @param effectiveFrom     this version's effective date (null for drafts)
     * @param today             current trading day
     * @param nextEffectiveFrom effective date of the next scheduled version after this one (null if none)
     */
    public static MenuStatus resolve(String stored, LocalDate effectiveFrom, LocalDate today, LocalDate nextEffectiveFrom) {
        if (!"SCHEDULED".equals(stored) || effectiveFrom == null) {
            return DRAFT;
        }
        if (effectiveFrom.isAfter(today)) {
            return SCHEDULED;
        }
        if (nextEffectiveFrom != null && !nextEffectiveFrom.isAfter(today)) {
            return ARCHIVED;
        }
        return ACTIVE;
    }

    /** Only DRAFT and future SCHEDULED versions can be edited. ACTIVE and ARCHIVED are frozen history. */
    public boolean editable() {
        return this == DRAFT || this == SCHEDULED;
    }
}
