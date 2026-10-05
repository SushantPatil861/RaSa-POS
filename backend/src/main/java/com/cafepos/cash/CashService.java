package com.cafepos.cash;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Daily cash reconciliation and payment-settlement matching.
 * expected cash = opening float + cash sales (v_daily_sales.cash) - cash expenses. A difference is never forced to zero:
 * it can be closed UNEXPLAINED (flagged). Closed days reopen only with a reason (audited).
 * Cookie management is deferred: the design document does not define it concretely.
 */
@Service
public class CashService {

    private static final Set<String> CATEGORIES = Set.of("CHANGE_SHORT", "CASH_TAKEN_BY_OWNER", "BANK_DEPOSIT", "REFUND",
            "ROUNDING", "OTHER", "UNKNOWN");
    private static final Set<String> METHODS = Set.of("UPI", "CARD", "WHATSAPP", "SWIGGY", "ZOMATO", "OTHER_ONLINE");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    // ------------------------------------------------------------ rows / DTOs
    public record SalesCash(String basis, BigDecimal cash, BigDecimal totalInclusive) {}

    public record DayRow(long id, BigDecimal openingFloat, BigDecimal expectedCash, BigDecimal countedCash,
                         BigDecimal difference, String status, String notes, int reopenCount, OffsetDateTime closedAt,
                         String closedByName) {}

    public record ExplanationDto(long id, String category, BigDecimal amount, String note, OffsetDateTime createdAt) {}

    public record CashDayDto(LocalDate txnDate, Long id, String status, String basis, BigDecimal openingFloat,
                             BigDecimal suggestedOpeningFloat, BigDecimal cashSales, BigDecimal cashExpenses,
                             BigDecimal supplierCashPayments, BigDecimal liveExpectedCash, BigDecimal expectedCash,
                             BigDecimal countedCash, BigDecimal difference, BigDecimal explained, BigDecimal unexplained,
                             List<String> flags, List<ExplanationDto> explanations, String notes, int reopenCount,
                             OffsetDateTime closedAt, String closedByName) {}

    public record DayRequest(BigDecimal openingFloat, BigDecimal countedCash, String notes) {}

    public record ExplanationRequest(String category, BigDecimal amount, String note) {}

    public record ListRow(LocalDate txnDate, String status, BigDecimal openingFloat, BigDecimal expectedCash,
                          BigDecimal countedCash, BigDecimal difference, BigDecimal explained) {}

    public record DaySummary(LocalDate txnDate, String status, BigDecimal openingFloat, BigDecimal expectedCash,
                             BigDecimal countedCash, BigDecimal difference, BigDecimal explained, BigDecimal unexplained,
                             List<String> flags) {}

    public record SettlementRow(long id, LocalDate txnDate, String method, BigDecimal receivedAmount, String reference,
                                String notes, OffsetDateTime entryAt, String voidState, String voidReason) {}

    public record SettlementRequest(LocalDate txnDate, String method, BigDecimal receivedAmount, String reference, String notes) {}

    public record SummaryQueryRow(LocalDate txnDate, String method, BigDecimal expected, BigDecimal received, Integer entries) {}

    public record SettlementDay(LocalDate txnDate, String method, BigDecimal expected, BigDecimal received,
                                BigDecimal difference, String status, int entries) {}

    public record MethodTotal(String method, BigDecimal expected, BigDecimal received, BigDecimal difference,
                              int pendingDays, int daysWithDifference) {}

    public record SettlementSummary(LocalDate from, LocalDate to, List<SettlementDay> rows, List<MethodTotal> totals,
                                    List<String> notes) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;

    public CashService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
    }

    // ------------------------------------------------------------ cash days

    public List<DaySummary> days(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("Choose a valid date range");
        List<ListRow> rows = jdbc.sql("""
                select c.txn_date, c.status, c.opening_float, c.expected_cash, c.counted_cash, c.difference,
                       coalesce((select sum(e.amount) from cash_day_explanations e
                                 where e.cash_day_id = c.id and e.category <> 'UNKNOWN'), 0) as explained
                from cash_days c where c.business_id = :b and c.txn_date between :f and :t order by c.txn_date desc
                """).param("b", props.businessId()).param("f", from).param("t", to).query(ListRow.class).list();
        List<DaySummary> out = new ArrayList<>();
        for (ListRow r : rows) {
            BigDecimal unexplained = r.difference() == null ? null : r.difference().subtract(r.explained());
            List<String> flags = new ArrayList<>();
            if ("DRAFT".equals(r.status())) flags.add("DRAFT");
            if ("CLOSED".equals(r.status()) && unexplained != null && unexplained.abs().compareTo(TOLERANCE) >= 0) flags.add("UNEXPLAINED");
            out.add(new DaySummary(r.txnDate(), r.status(), r.openingFloat(), r.expectedCash(), r.countedCash(), r.difference(),
                    r.explained(), unexplained, flags));
        }
        return out;
    }

    public CashDayDto day(LocalDate date) {
        long b = props.businessId();
        SalesCash sales = jdbc.sql("select basis, cash, total_inclusive from v_daily_sales where business_id = :b and txn_date = :d")
                .param("b", b).param("d", date).query(SalesCash.class).optional().orElse(null);
        BigDecimal cashExpenses = jdbc.sql("""
                select coalesce(sum(amount), 0) from expenses
                where business_id = :b and void_state = 'ACTIVE' and payment_method = 'CASH' and txn_date = :d
                """).param("b", b).param("d", date).query(BigDecimal.class).single();
        BigDecimal supplierCash = jdbc.sql("""
                select coalesce(sum(amount), 0) from supplier_payments
                where business_id = :b and void_state = 'ACTIVE' and method = 'CASH' and txn_date = :d
                """).param("b", b).param("d", date).query(BigDecimal.class).single();
        DayRow row = findRow(date);
        BigDecimal suggested = jdbc.sql("""
                select counted_cash from cash_days where business_id = :b and status = 'CLOSED' and txn_date < :d
                order by txn_date desc limit 1
                """).param("b", b).param("d", date).query(BigDecimal.class).optional().orElse(null);

        BigDecimal cashSales = sales == null || sales.cash() == null ? BigDecimal.ZERO : sales.cash();
        BigDecimal opening = row == null ? BigDecimal.ZERO : row.openingFloat();
        BigDecimal live = opening.add(cashSales).subtract(cashExpenses);

        List<ExplanationDto> expl = row == null ? List.of() : jdbc.sql("""
                select id, category, amount, note, created_at from cash_day_explanations where cash_day_id = :c order by id
                """).param("c", row.id()).query(ExplanationDto.class).list();
        BigDecimal explained = BigDecimal.ZERO;
        for (ExplanationDto e : expl) {
            if (!"UNKNOWN".equals(e.category())) explained = explained.add(e.amount());
        }
        boolean closed = row != null && "CLOSED".equals(row.status());
        BigDecimal expected = closed ? row.expectedCash() : live;
        BigDecimal counted = row == null ? null : row.countedCash();
        BigDecimal difference = closed ? row.difference() : (counted == null ? null : counted.subtract(live));
        BigDecimal unexplained = difference == null ? null : difference.subtract(explained);

        List<String> flags = new ArrayList<>();
        if (row == null) flags.add("OPENING_FLOAT_NOT_SET");
        if (sales == null) flags.add("NO_SALES_RECORDED");
        else if ("SUMMARY".equals(sales.basis()) && cashSales.signum() == 0 && sales.totalInclusive() != null
                && sales.totalInclusive().signum() > 0) flags.add("CASH_SPLIT_NOT_ENTERED");
        if (row != null && !closed) flags.add("DRAFT");
        if (closed) {
            if (unexplained != null && unexplained.abs().compareTo(TOLERANCE) >= 0) flags.add("UNEXPLAINED");
            if (row.expectedCash() != null && row.expectedCash().subtract(live).abs().compareTo(TOLERANCE) >= 0) {
                flags.add("CHANGED_SINCE_CLOSE");
            }
            if (row.reopenCount() > 0) flags.add("CORRECTED");
        }
        return new CashDayDto(date, row == null ? null : row.id(), row == null ? "NOT_STARTED" : row.status(),
                sales == null ? null : sales.basis(), opening, suggested, sales == null ? null : sales.cash(), cashExpenses,
                supplierCash, live, expected, counted, difference, explained, unexplained, flags, expl,
                row == null ? null : row.notes(), row == null ? 0 : row.reopenCount(), row == null ? null : row.closedAt(),
                row == null ? null : row.closedByName());
    }

    @Transactional
    public CashDayDto save(LocalDate date, DayRequest r) {
        CurrentUser.require("cash.close");
        checkDate(date);
        if (r.openingFloat() != null && r.openingFloat().signum() < 0) throw ApiException.badRequest("Opening float cannot be negative");
        if (r.countedCash() != null && r.countedCash().signum() < 0) throw ApiException.badRequest("Counted cash cannot be negative");
        DayRow row = findRow(date);
        if (row != null && "CLOSED".equals(row.status())) {
            throw ApiException.conflict("This cash day is closed. Reopen it (with a reason) to change it");
        }
        BigDecimal opening = r.openingFloat() != null ? r.openingFloat() : (row == null ? BigDecimal.ZERO : row.openingFloat());
        BigDecimal counted = r.countedCash() != null ? r.countedCash() : (row == null ? null : row.countedCash());
        BigDecimal live = liveExpected(date, opening);
        BigDecimal diff = counted == null ? null : counted.subtract(live);
        String notes = r.notes() == null ? (row == null ? null : row.notes()) : (r.notes().isBlank() ? null : r.notes().trim());
        if (row == null) {
            long id = jdbc.sql("""
                    insert into cash_days (business_id, txn_date, opening_float, expected_cash, counted_cash, difference, notes, created_by)
                    values (:b, :d, :o, :e, :c, :df, :n, :u) returning id
                    """).param("b", props.businessId()).param("d", date).param("o", opening).param("e", live)
                    .param("c", counted).param("df", diff).param("n", notes).param("u", CurrentUser.id())
                    .query(Long.class).single();
            audit.record("CASH_DAY", id, "CREATE", date, null, snapshot(id), null, "MANUAL");
        } else {
            String before = snapshot(row.id());
            jdbc.sql("""
                    update cash_days set opening_float = :o, expected_cash = :e, counted_cash = :c, difference = :df,
                           notes = :n, updated_at = now() where id = :id
                    """).param("o", opening).param("e", live).param("c", counted).param("df", diff).param("n", notes)
                    .param("id", row.id()).update();
            audit.record("CASH_DAY", row.id(), "UPDATE", date, before, snapshot(row.id()), "Draft saved", "MANUAL");
        }
        return day(date);
    }

    @Transactional
    public CashDayDto addExplanation(LocalDate date, ExplanationRequest r) {
        CurrentUser.require("cash.close");
        DayRow row = requireDraft(date);
        if (r.category() == null || !CATEGORIES.contains(r.category())) {
            throw ApiException.badRequest("Category must be one of " + String.join(", ", new java.util.TreeSet<>(CATEGORIES)));
        }
        if (r.amount() == null || r.amount().signum() == 0) throw ApiException.badRequest("Enter an amount (negative if cash is missing)");
        String before = snapshot(row.id());
        jdbc.sql("insert into cash_day_explanations (cash_day_id, category, amount, note, created_by) values (:c, :cat, :a, :n, :u)")
                .param("c", row.id()).param("cat", r.category()).param("a", r.amount())
                .param("n", r.note() == null || r.note().isBlank() ? null : r.note().trim()).param("u", CurrentUser.id()).update();
        audit.record("CASH_DAY", row.id(), "ADD_EXPLANATION", date, before, snapshot(row.id()), r.category(), "MANUAL");
        return day(date);
    }

    @Transactional
    public CashDayDto removeExplanation(LocalDate date, long explanationId) {
        CurrentUser.require("cash.close");
        DayRow row = requireDraft(date);
        String before = snapshot(row.id());
        int n = jdbc.sql("delete from cash_day_explanations where id = :id and cash_day_id = :c")
                .param("id", explanationId).param("c", row.id()).update();
        if (n == 0) throw ApiException.notFound("Explanation");
        audit.record("CASH_DAY", row.id(), "REMOVE_EXPLANATION", date, before, snapshot(row.id()), "Explanation removed from draft", "MANUAL");
        return day(date);
    }

    @Transactional
    public CashDayDto close(LocalDate date) {
        CurrentUser.require("cash.close");
        DayRow row = requireDraft(date);
        if (row.countedCash() == null) throw ApiException.badRequest("Enter the counted cash before closing the day");
        BigDecimal live = liveExpected(date, row.openingFloat());
        BigDecimal diff = row.countedCash().subtract(live);
        String before = snapshot(row.id());
        jdbc.sql("""
                update cash_days set status = 'CLOSED', expected_cash = :e, difference = :df, closed_by = :u, closed_at = now(),
                       updated_at = now() where id = :id
                """).param("e", live).param("df", diff).param("u", CurrentUser.id()).param("id", row.id()).update();
        audit.record("CASH_DAY", row.id(), "CLOSE", date, before, snapshot(row.id()), null, "MANUAL");
        return day(date);
    }

    @Transactional
    public CashDayDto reopen(LocalDate date, String reason) {
        CurrentUser.require("cash.close");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to reopen a closed cash day");
        DayRow row = findRow(date);
        if (row == null) throw ApiException.notFound("Cash day");
        if (!"CLOSED".equals(row.status())) throw ApiException.conflict("This cash day is not closed");
        String before = snapshot(row.id());
        jdbc.sql("""
                update cash_days set status = 'DRAFT', closed_by = null, closed_at = null, reopen_count = reopen_count + 1,
                       updated_at = now() where id = :id
                """).param("id", row.id()).update();
        audit.record("CASH_DAY", row.id(), "REOPEN", date, before, snapshot(row.id()), reason, "MANUAL");
        return day(date);
    }

    private DayRow requireDraft(LocalDate date) {
        DayRow row = findRow(date);
        if (row == null) throw ApiException.conflict("Save the opening float and counted cash for this day first");
        if ("CLOSED".equals(row.status())) throw ApiException.conflict("This cash day is closed. Reopen it (with a reason) to change it");
        return row;
    }

    private DayRow findRow(LocalDate date) {
        return jdbc.sql("""
                select c.id, c.opening_float, c.expected_cash, c.counted_cash, c.difference, c.status, c.notes, c.reopen_count,
                       c.closed_at, u.name as closed_by_name
                from cash_days c left join users u on u.id = c.closed_by
                where c.business_id = :b and c.txn_date = :d
                """).param("b", props.businessId()).param("d", date).query(DayRow.class).optional().orElse(null);
    }

    private BigDecimal liveExpected(LocalDate date, BigDecimal opening) {
        BigDecimal sales = jdbc.sql("select coalesce(cash, 0) from v_daily_sales where business_id = :b and txn_date = :d")
                .param("b", props.businessId()).param("d", date).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        BigDecimal exp = jdbc.sql("""
                select coalesce(sum(amount), 0) from expenses
                where business_id = :b and void_state = 'ACTIVE' and payment_method = 'CASH' and txn_date = :d
                """).param("b", props.businessId()).param("d", date).query(BigDecimal.class).single();
        return opening.add(sales).subtract(exp);
    }

    private void checkDate(LocalDate date) {
        if (date == null) throw ApiException.badRequest("Date is required");
        if (date.isAfter(clock.today())) throw ApiException.badRequest("Date cannot be in the future");
        if (date.isBefore(clock.dataStartDate())) throw ApiException.badRequest("Date is before the business data-start date " + clock.dataStartDate());
    }

    private String snapshot(long id) {
        return jdbc.sql("""
                select jsonb_build_object('day', to_jsonb(c), 'explanations',
                       (select coalesce(jsonb_agg(to_jsonb(e) order by e.id), '[]'::jsonb) from cash_day_explanations e where e.cash_day_id = c.id))::text
                from cash_days c where c.id = :id
                """).param("id", id).query(String.class).single();
    }

    // ------------------------------------------------------------ payment settlements

    public List<SettlementRow> settlements(LocalDate from, LocalDate to, String method, boolean includeVoided) {
        return jdbc.sql("""
                select id, txn_date, method, received_amount, reference, notes, entry_at, void_state, void_reason
                from payment_settlements
                where business_id = :b and (:inc or void_state = 'ACTIVE')
                  and (cast(:f as date) is null or txn_date >= :f) and (cast(:t as date) is null or txn_date <= :t)
                  and (cast(:m as text) is null or method = :m)
                order by txn_date desc, id desc limit 400
                """).param("b", props.businessId()).param("inc", includeVoided).param("f", from).param("t", to)
                .param("m", method == null || method.isBlank() ? null : method).query(SettlementRow.class).list();
    }

    @Transactional
    public SettlementRow createSettlement(SettlementRequest r) {
        CurrentUser.require("cash.close");
        checkDate(r.txnDate());
        if (r.method() == null || !METHODS.contains(r.method())) {
            throw ApiException.badRequest("Method must be one of " + String.join(", ", new java.util.TreeSet<>(METHODS)));
        }
        if (r.receivedAmount() == null || r.receivedAmount().signum() < 0) throw ApiException.badRequest("Received amount must be zero or more");
        long id = jdbc.sql("""
                insert into payment_settlements (business_id, txn_date, method, received_amount, reference, notes, created_by)
                values (:b, :d, :m, :a, :ref, :n, :u) returning id
                """).param("b", props.businessId()).param("d", r.txnDate()).param("m", r.method()).param("a", r.receivedAmount())
                .param("ref", blank(r.reference())).param("n", blank(r.notes())).param("u", CurrentUser.id())
                .query(Long.class).single();
        audit.record("PAYMENT_SETTLEMENT", id, "CREATE", r.txnDate(), null, settlementSnapshot(id), null, "MANUAL");
        return settlement(id);
    }

    @Transactional
    public SettlementRow voidSettlement(long id, String reason) {
        CurrentUser.require("cash.close");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a settlement");
        SettlementRow cur = jdbc.sql("""
                select id, txn_date, method, received_amount, reference, notes, entry_at, void_state, void_reason
                from payment_settlements where id = :id and business_id = :b
                """).param("id", id).param("b", props.businessId()).query(SettlementRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Settlement"));
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("Already voided");
        String before = settlementSnapshot(id);
        jdbc.sql("update payment_settlements set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now() where id = :id")
                .param("r", reason).param("u", CurrentUser.id()).param("id", id).update();
        audit.record("PAYMENT_SETTLEMENT", id, "VOID", cur.txnDate(), before, settlementSnapshot(id), reason, "MANUAL");
        return settlement(id);
    }

    public SettlementSummary settlementSummary(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("Choose a valid date range");
        List<SummaryQueryRow> rows = jdbc.sql("""
                with exp as (
                  select v.txn_date, m.method, m.amount
                  from v_daily_sales v
                  cross join lateral (values ('UPI', v.upi), ('CARD', v.card), ('WHATSAPP', v.whatsapp),
                                             ('SWIGGY', v.swiggy), ('ZOMATO', v.zomato), ('OTHER_ONLINE', v.other_online)) as m(method, amount)
                  where v.business_id = :b and v.txn_date between :f and :t and m.amount is not null and m.amount <> 0
                ), rec as (
                  select txn_date, method, sum(received_amount) as received, count(*) as entries
                  from payment_settlements
                  where business_id = :b and void_state = 'ACTIVE' and txn_date between :f and :t group by txn_date, method
                )
                select coalesce(e.txn_date, r.txn_date) as txn_date, coalesce(e.method, r.method) as method,
                       e.amount as expected, r.received, r.entries::int as entries
                from exp e full join rec r on r.txn_date = e.txn_date and r.method = e.method
                order by 1, 2
                """).param("b", props.businessId()).param("f", from).param("t", to).query(SummaryQueryRow.class).list();
        List<SettlementDay> days = new ArrayList<>();
        Map<String, BigDecimal[]> totals = new LinkedHashMap<>();   // expected, received, diff
        Map<String, int[]> counts = new LinkedHashMap<>();          // pending, withDifference
        for (SummaryQueryRow r : rows) {
            String status;
            BigDecimal diff = null;
            if (r.received() == null) {
                status = "PENDING";
            } else if (r.expected() == null) {
                status = "NO_SALES_EXPECTED";
                diff = r.received();
            } else {
                diff = r.received().subtract(r.expected());
                status = diff.abs().compareTo(TOLERANCE) < 0 ? "MATCHED" : (diff.signum() < 0 ? "SHORT" : "EXCESS");
            }
            days.add(new SettlementDay(r.txnDate(), r.method(), r.expected(), r.received(), diff, status,
                    r.entries() == null ? 0 : r.entries()));
            BigDecimal[] t = totals.computeIfAbsent(r.method(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            int[] c = counts.computeIfAbsent(r.method(), k -> new int[2]);
            if (r.expected() != null) t[0] = t[0].add(r.expected());
            if (r.received() != null) t[1] = t[1].add(r.received());
            if ("PENDING".equals(status)) c[0]++;
            if ("SHORT".equals(status) || "EXCESS".equals(status) || "NO_SALES_EXPECTED".equals(status)) c[1]++;
        }
        List<MethodTotal> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : totals.entrySet()) {
            BigDecimal[] t = e.getValue();
            int[] c = counts.get(e.getKey());
            out.add(new MethodTotal(e.getKey(), t[0], t[1], t[1].subtract(t[0]), c[0], c[1]));
        }
        List<String> notes = new ArrayList<>();
        notes.add("PENDING means no settlement has been recorded for that day yet (it is not treated as zero received).");
        notes.add("Aggregator payouts (Swiggy, Zomato) are normally net of commission, so a SHORT difference may simply be commission - record it in the notes.");
        notes.add("Cash is reconciled on the Cash close sheet, not here.");
        return new SettlementSummary(from, to, days, out, notes);
    }

    private SettlementRow settlement(long id) {
        return jdbc.sql("""
                select id, txn_date, method, received_amount, reference, notes, entry_at, void_state, void_reason
                from payment_settlements where id = :id
                """).param("id", id).query(SettlementRow.class).single();
    }

    private String settlementSnapshot(long id) {
        return jdbc.sql("select to_jsonb(s)::text from payment_settlements s where s.id = :id").param("id", id)
                .query(String.class).single();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
