package com.cafepos.platform;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** The trading day an instant belongs to. A café open past midnight keeps late sales on the previous day. */
public final class BusinessDay {

    private BusinessDay() {}

    public static LocalDate of(Instant now, ZoneId zone, int rolloverHour) {
        return now.atZone(zone).minusHours(rolloverHour).toLocalDate();
    }
}
