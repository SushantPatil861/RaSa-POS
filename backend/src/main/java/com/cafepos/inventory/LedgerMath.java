package com.cafepos.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Framework-free supplier ledger maths: payments are applied to the oldest credit bills first (FIFO), then bucketed by age. */
public final class LedgerMath {

    public record Bill(long id, LocalDate date, BigDecimal amount) {}

    public record OpenBill(long id, LocalDate date, BigDecimal original, BigDecimal remaining, long ageDays, String bucket) {}

    public record Aging(BigDecimal bucket0to30, BigDecimal bucket31to60, BigDecimal bucket60plus,
                        BigDecimal outstanding, BigDecimal advance, List<OpenBill> openBills) {}

    private LedgerMath() {}

    public static String bucketOf(long ageDays) {
        if (ageDays <= 30) return "0-30";
        if (ageDays <= 60) return "31-60";
        return "60+";
    }

    /** Bills must be credit bills (not paid at purchase). Payments larger than all bills become an advance. */
    public static Aging age(List<Bill> bills, BigDecimal paymentsTotal, LocalDate asOf) {
        List<Bill> sorted = new ArrayList<>(bills);
        sorted.sort((a, b) -> {
            int c = a.date().compareTo(b.date());
            return c != 0 ? c : Long.compare(a.id(), b.id());
        });
        BigDecimal left = paymentsTotal == null ? BigDecimal.ZERO : paymentsTotal;
        BigDecimal b1 = BigDecimal.ZERO;
        BigDecimal b2 = BigDecimal.ZERO;
        BigDecimal b3 = BigDecimal.ZERO;
        List<OpenBill> open = new ArrayList<>();
        for (Bill bill : sorted) {
            BigDecimal remaining = bill.amount();
            if (left.signum() > 0) {
                BigDecimal used = left.min(remaining);
                remaining = remaining.subtract(used);
                left = left.subtract(used);
            }
            if (remaining.signum() > 0) {
                long age = Math.max(ChronoUnit.DAYS.between(bill.date(), asOf), 0);
                String bucket = bucketOf(age);
                switch (bucket) {
                    case "0-30" -> b1 = b1.add(remaining);
                    case "31-60" -> b2 = b2.add(remaining);
                    default -> b3 = b3.add(remaining);
                }
                open.add(new OpenBill(bill.id(), bill.date(), bill.amount(), remaining, age, bucket));
            }
        }
        return new Aging(b1, b2, b3, b1.add(b2).add(b3), left, open);
    }
}
