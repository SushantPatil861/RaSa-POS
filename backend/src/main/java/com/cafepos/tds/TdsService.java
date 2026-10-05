package com.cafepos.tds;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.platform.StatusFlags;
import com.cafepos.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * TDS / TCS register. A record-keeping aid for the owner's CA: rates and thresholds are effective-dated data
 * (tds_sections), a missing PAN is only FLAGGED (no higher-rate maths), and unknowns stay unknown.
 */
@Service
public class TdsService {

    private static final Set<String> PAYEE_TYPES = Set.of("ANY", "INDIVIDUAL_HUF", "OTHER");
    private static final Set<String> SOURCES = Set.of("MANUAL", "EXCEL", "CSV", "OCR", "WHATSAPP", "COUNTER", "IMPORT", "SYSTEM", "OTHER");
    private static final Set<String> PLATFORMS = Set.of("SWIGGY", "ZOMATO", "OTHER");

    public record SectionDto(long id, String code, String description, String payeeType, BigDecimal rate,
                             BigDecimal singleThreshold, BigDecimal annualThreshold, LocalDate effectiveFrom,
                             String verifyNote, String source, boolean current) {}

    public record SectionRequest(String code, String description, String payeeType, BigDecimal rate,
                                 BigDecimal singleThreshold, BigDecimal annualThreshold, LocalDate effectiveFrom,
                                 String verifyNote) {}

    public record SectionRate(long id, String code, String payeeType, BigDecimal rate, BigDecimal singleThreshold,
                              BigDecimal annualThreshold) {}

    public record EntryRow(long id, LocalDate txnDate, OffsetDateTime entryAt, String deducteeName, String deducteePan,
                           String section, String payeeType, BigDecimal paymentAmount, BigDecimal rateApplied,
                           BigDecimal tdsAmount, String thresholdStatus, String thresholdNote, LocalDate depositDueDate,
                           LocalDate depositedOn, String challanNo, String status, Long expenseId, String notes,
                           String source, int revision, int version, String voidState, String voidReason,
                           int fyStartYear, String createdByName) {}

    public record EntryDto(long id, LocalDate txnDate, OffsetDateTime entryAt, String deducteeName, String deducteePan,
                           String section, String payeeType, BigDecimal paymentAmount, BigDecimal rateApplied,
                           BigDecimal tdsAmount, String thresholdStatus, String thresholdNote, LocalDate depositDueDate,
                           LocalDate depositedOn, String challanNo, String status, Long expenseId, String notes,
                           String source, int revision, int version, String voidState, String voidReason,
                           String fyLabel, String createdByName, boolean panMissing, boolean overdue,
                           List<String> flags) {}

    public record EntryRequest(LocalDate txnDate, String deducteeName, String deducteePan, String section,
                               String payeeType, BigDecimal paymentAmount, Long expenseId, String notes, String source,
                               String reason, Integer version) {}

    public record DepositRequest(LocalDate depositedOn, String challanNo, BigDecimal tdsAmount) {}

    public record ThresholdRow(int fyStartYear, String deducteeKey, String deducteeName, String deducteePan,
                               String section, String payeeType, long entries, BigDecimal totalPaid, BigDecimal totalTds,
                               BigDecimal singleThreshold, BigDecimal annualThreshold, boolean annualCrossed,
                               BigDecimal remainingToAnnual, boolean panMissing) {}

    public record ThresholdDto(String fyLabel, int fyStartYear, List<ThresholdRow> rows, String note) {}

    public record PendingDto(long count, BigDecimal totalTds, long overdueCount, long checkWithCaCount,
                             List<EntryDto> items) {}

    public record TcsRow(long id, String platform, String platformName, LocalDate periodStart, LocalDate periodEnd,
                         BigDecimal tcsAmount, boolean gstTcs, String notes, String source, String voidState,
                         String voidReason, int version, OffsetDateTime createdAt) {}

    public record TcsRequest(String platform, String platformName, LocalDate periodStart, LocalDate periodEnd,
                             BigDecimal tcsAmount, Boolean gstTcs, String notes, String source) {}

    public record TcsSummary(List<TcsRow> rows, BigDecimal totalGstTcs, BigDecimal totalOtherTcs) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;

    public TdsService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ section master (effective-dated)

    public List<SectionDto> sections(LocalDate asOf, boolean includeHistory) {
        CurrentUser.require("tds.view");
        LocalDate d = asOf != null ? asOf : clock.today();
        List<SectionDto> all = jdbc.sql("""
                select s.id, s.code, s.description, s.payee_type, s.rate, s.single_threshold, s.annual_threshold,
                       s.effective_from, s.verify_note, s.source,
                       (s.effective_from = (select max(x.effective_from) from tds_sections x
                                             where x.business_id = s.business_id and x.code = s.code
                                               and x.payee_type = s.payee_type and x.effective_from <= :d)) as current
                from tds_sections s where s.business_id = :b
                order by s.code, s.payee_type, s.effective_from desc
                """).param("b", props.businessId()).param("d", d).query(SectionDto.class).list();
        if (includeHistory) return all;
        return all.stream().filter(s -> s.current() || s.effectiveFrom().isAfter(d)).toList();
    }

    /** Never edits a row: a change is a NEW row with its own effective date. Owner only (tds.configure). */
    @Transactional
    public SectionDto addSection(SectionRequest r) {
        CurrentUser.require("tds.configure");
        if (r.code() == null || r.code().isBlank()) throw ApiException.badRequest("Section code is required");
        if (r.description() == null || r.description().isBlank()) throw ApiException.badRequest("Description is required");
        String pt = r.payeeType() == null || r.payeeType().isBlank() ? "ANY" : r.payeeType();
        if (!PAYEE_TYPES.contains(pt)) throw ApiException.badRequest("Payee type must be ANY, INDIVIDUAL_HUF or OTHER");
        if (r.rate() == null || r.rate().signum() < 0 || r.rate().compareTo(new BigDecimal("100")) > 0) {
            throw ApiException.badRequest("Rate must be between 0 and 100");
        }
        if ((r.singleThreshold() != null && r.singleThreshold().signum() < 0)
                || (r.annualThreshold() != null && r.annualThreshold().signum() < 0)) {
            throw ApiException.badRequest("Thresholds cannot be negative");
        }
        if (r.effectiveFrom() == null) throw ApiException.badRequest("Effective-from date is required");
        long id;
        try {
            id = jdbc.sql("""
                    insert into tds_sections (business_id, code, description, payee_type, rate, single_threshold,
                                              annual_threshold, effective_from, verify_note, source, created_by)
                    values (:b, :c, :d, :pt, :r, :st, :at, :ef, :vn, 'USER', :u) returning id
                    """)
                    .param("b", props.businessId()).param("c", r.code().trim()).param("d", r.description().trim())
                    .param("pt", pt).param("r", r.rate()).param("st", r.singleThreshold()).param("at", r.annualThreshold())
                    .param("ef", r.effectiveFrom()).param("vn", blankToNull(r.verifyNote())).param("u", CurrentUser.id())
                    .query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("There is already a row for this section and payee type with that effective date. Use a later date.");
        }
        audit.record("TDS_SECTION", id, "CREATE", r.effectiveFrom(),
                null, jdbc.sql("select to_jsonb(s)::text from tds_sections s where s.id = :id").param("id", id).query(String.class).single(),
                "New effective-dated rate/threshold row", "MANUAL");
        return jdbc.sql("""
                select s.id, s.code, s.description, s.payee_type, s.rate, s.single_threshold, s.annual_threshold,
                       s.effective_from, s.verify_note, s.source, true as current
                from tds_sections s where s.id = :id
                """).param("id", id).query(SectionDto.class).single();
    }

    SectionRate resolveSection(String code, String payeeType, LocalDate date) {
        return jdbc.sql("""
                select id, code, payee_type, rate, single_threshold, annual_threshold from tds_sections
                where business_id = :b and upper(code) = upper(:c) and effective_from <= :d
                  and payee_type in (:pt, 'ANY')
                order by effective_from desc, case when payee_type = :pt then 0 else 1 end
                limit 1
                """).param("b", props.businessId()).param("c", code).param("d", date).param("pt", payeeType)
                .query(SectionRate.class).optional().orElse(null);
    }

    // ------------------------------------------------------------------ entries

    private static final String ENTRY_SELECT = """
            select e.id, e.txn_date, e.entry_at, e.deductee_name, e.deductee_pan, e.section, e.payee_type,
                   e.payment_amount, e.rate_applied, e.tds_amount, e.threshold_status, e.threshold_note,
                   e.deposit_due_date, e.deposited_on, e.challan_no, e.status, e.expense_id, e.notes, e.source,
                   e.revision, e.version, e.void_state, e.void_reason, e.fy_start_year, u.name as created_by_name
            from tds_entries e left join users u on u.id = e.created_by
            """;

    public List<EntryDto> list(LocalDate from, LocalDate to, String status, String section, boolean includeVoided, int limit) {
        CurrentUser.require("tds.view");
        if (status != null && !status.isBlank() && !Set.of("PENDING", "DEPOSITED").contains(status)) {
            throw ApiException.badRequest("Status must be PENDING or DEPOSITED");
        }
        LocalDate today = clock.today();
        return jdbc.sql(ENTRY_SELECT + """
                where e.business_id = :b
                  and (cast(:f as date) is null or e.txn_date >= :f)
                  and (cast(:t as date) is null or e.txn_date <= :t)
                  and (cast(:st as text) is null or e.status = :st)
                  and (cast(:sec as text) is null or upper(e.section) = upper(:sec))
                  and (:inc or e.void_state = 'ACTIVE')
                order by e.txn_date desc, e.id desc limit :lim
                """).param("b", props.businessId()).param("f", from).param("t", to)
                .param("st", status == null || status.isBlank() ? null : status)
                .param("sec", section == null || section.isBlank() ? null : section)
                .param("inc", includeVoided).param("lim", Math.min(Math.max(limit, 1), 500))
                .query(EntryRow.class).list().stream().map(r -> toDto(r, today)).toList();
    }

    public EntryDto get(long id) {
        CurrentUser.require("tds.view");
        return toDto(row(id), clock.today());
    }

    @Transactional
    public EntryDto create(EntryRequest r) {
        CurrentUser.require("tds.manage");
        validate(r);
        String source = r.source() == null || r.source().isBlank() ? "MANUAL" : r.source();
        if (!SOURCES.contains(source)) throw ApiException.badRequest("Unknown source: " + source);
        checkExpense(r.expenseId());
        Calc c = calc(r, 0L);
        long id = jdbc.sql("""
                insert into tds_entries (business_id, txn_date, deductee_name, deductee_pan, section, payee_type, payment_amount,
                                         rate_applied, tds_amount, threshold_status, threshold_note, section_id, deposit_due_date,
                                         expense_id, notes, source, created_by, updated_by)
                values (:b, :d, :n, :pan, :sec, :pt, :amt, :rate, :tds, :ts, :tn, :sid, :due, :eid, :notes, :src, :u, :u)
                returning id
                """)
                .param("b", props.businessId()).param("d", r.txnDate()).param("n", r.deducteeName().trim())
                .param("pan", panOrNull(r.deducteePan())).param("sec", c.sectionCode()).param("pt", c.payeeType())
                .param("amt", r.paymentAmount()).param("rate", c.result().rate()).param("tds", c.result().tdsAmount())
                .param("ts", c.result().thresholdStatus()).param("tn", c.result().noteText())
                .param("sid", c.section() == null ? null : c.section().id()).param("due", c.dueDate())
                .param("eid", r.expenseId()).param("notes", blankToNull(r.notes())).param("src", source)
                .param("u", CurrentUser.id()).query(Long.class).single();
        audit.record("TDS_ENTRY", id, "CREATE", r.txnDate(), null, snapshot(id), null, source);
        return get(id);
    }

    @Transactional
    public EntryDto update(long id, EntryRequest r) {
        CurrentUser.require("tds.manage");
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason is required to change a TDS entry");
        EntryRow cur = row(id);
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("This entry is voided and cannot be edited");
        if ("DEPOSITED".equals(cur.status())) {
            throw ApiException.conflict("This entry is marked deposited. Remove the deposit mark (with a reason) before correcting it.");
        }
        if (r.version() != null && r.version() != cur.version()) {
            throw ApiException.conflict("This entry was changed by someone else. Reload it and try again.");
        }
        validate(r);
        checkExpense(r.expenseId());
        Calc c = calc(r, id);
        String before = snapshot(id);
        jdbc.sql("""
                update tds_entries set txn_date = :d, deductee_name = :n, deductee_pan = :pan, section = :sec, payee_type = :pt,
                       payment_amount = :amt, rate_applied = :rate, tds_amount = :tds, threshold_status = :ts,
                       threshold_note = :tn, section_id = :sid, deposit_due_date = :due, expense_id = :eid, notes = :notes,
                       revision = revision + 1, version = version + 1, updated_by = :u, updated_at = now()
                where id = :id and business_id = :b
                """)
                .param("d", r.txnDate()).param("n", r.deducteeName().trim()).param("pan", panOrNull(r.deducteePan()))
                .param("sec", c.sectionCode()).param("pt", c.payeeType()).param("amt", r.paymentAmount())
                .param("rate", c.result().rate()).param("tds", c.result().tdsAmount()).param("ts", c.result().thresholdStatus())
                .param("tn", c.result().noteText()).param("sid", c.section() == null ? null : c.section().id())
                .param("due", c.dueDate()).param("eid", r.expenseId()).param("notes", blankToNull(r.notes()))
                .param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("TDS_ENTRY", id, "UPDATE", r.txnDate(), before, snapshot(id), r.reason(), "MANUAL");
        return get(id);
    }

    @Transactional
    public EntryDto markDeposited(long id, DepositRequest r) {
        CurrentUser.require("tds.manage");
        EntryRow cur = row(id);
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("This entry is voided");
        if ("DEPOSITED".equals(cur.status())) throw ApiException.conflict("Already marked as deposited");
        if (TdsCalculator.BELOW_THRESHOLD.equals(cur.thresholdStatus())) {
            throw ApiException.conflict("No deduction is due on this entry yet (below threshold), so there is nothing to deposit");
        }
        if (r.depositedOn() == null) throw ApiException.badRequest("Enter the date the TDS was deposited");
        if (r.depositedOn().isBefore(cur.txnDate())) throw ApiException.badRequest("Deposit date cannot be before the payment date");
        if (r.depositedOn().isAfter(clock.today())) throw ApiException.badRequest("Deposit date cannot be in the future");
        if (cur.tdsAmount() == null && (r.tdsAmount() == null || r.tdsAmount().signum() <= 0)) {
            throw ApiException.badRequest("The TDS amount is not known for this entry. Enter the amount your CA confirmed.");
        }
        if (r.tdsAmount() != null && r.tdsAmount().signum() < 0) throw ApiException.badRequest("TDS amount cannot be negative");
        String before = snapshot(id);
        jdbc.sql("""
                update tds_entries set status = 'DEPOSITED', deposited_on = :d, challan_no = :c,
                       tds_amount = coalesce(:amt, tds_amount),
                       threshold_status = case when threshold_status = 'CHECK_WITH_CA' and cast(:amt as numeric) is not null
                                               then 'DEDUCT' else threshold_status end,
                       threshold_note = case when threshold_status = 'CHECK_WITH_CA' and cast(:amt as numeric) is not null
                                             then coalesce(threshold_note || ' | ', '') || 'TDS amount entered manually at deposit'
                                             else threshold_note end,
                       version = version + 1, updated_by = :u, updated_at = now()
                where id = :id and business_id = :b and status = 'PENDING'
                """).param("d", r.depositedOn()).param("c", blankToNull(r.challanNo())).param("amt", r.tdsAmount())
                .param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("TDS_ENTRY", id, "DEPOSIT", cur.txnDate(), before, snapshot(id), null, "MANUAL");
        return get(id);
    }

    @Transactional
    public EntryDto unmarkDeposit(long id, String reason) {
        CurrentUser.require("tds.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to remove a deposit mark");
        EntryRow cur = row(id);
        if (!"DEPOSITED".equals(cur.status())) throw ApiException.conflict("This entry is not marked as deposited");
        String before = snapshot(id);
        jdbc.sql("""
                update tds_entries set status = 'PENDING', deposited_on = null, challan_no = null,
                       version = version + 1, updated_by = :u, updated_at = now()
                where id = :id and business_id = :b
                """).param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("TDS_ENTRY", id, "UNMARK_DEPOSIT", cur.txnDate(), before, snapshot(id), reason, "MANUAL");
        return get(id);
    }

    @Transactional
    public EntryDto voidEntry(long id, String reason) {
        CurrentUser.require("tds.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a TDS entry");
        EntryRow cur = row(id);
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("Already voided");
        String before = snapshot(id);
        jdbc.sql("""
                update tds_entries set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now(),
                       version = version + 1, updated_at = now()
                where id = :id and business_id = :b
                """).param("r", reason).param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("TDS_ENTRY", id, "VOID", cur.txnDate(), before, snapshot(id), reason, "MANUAL");
        return get(id);
    }

    // ------------------------------------------------------------------ annual thresholds (per deductee + section + FY)

    public ThresholdDto thresholds(Integer fyStartYear) {
        CurrentUser.require("tds.view");
        int fy = fyStartYear != null ? fyStartYear : TdsCalculator.fyStartYear(clock.today());
        List<ThresholdQueryRow> raw = jdbc.sql("""
                select g.fy_start_year, g.deductee_key, g.deductee_name, g.deductee_pan, g.section, g.payee_type,
                       g.entries, g.total_paid, g.total_tds, sr.single_threshold, sr.annual_threshold
                from (
                  select fy_start_year, deductee_key, max(deductee_name) as deductee_name, max(deductee_pan) as deductee_pan,
                         section, max(payee_type) as payee_type, count(*) as entries, sum(payment_amount) as total_paid,
                         sum(tds_amount) as total_tds, max(txn_date) as last_date
                  from tds_entries
                  where business_id = :b and void_state = 'ACTIVE' and fy_start_year = :fy
                  group by fy_start_year, deductee_key, section
                ) g
                left join lateral (
                  select s.single_threshold, s.annual_threshold from tds_sections s
                  where s.business_id = :b and upper(s.code) = upper(g.section) and s.effective_from <= g.last_date
                    and s.payee_type in (g.payee_type, 'ANY')
                  order by s.effective_from desc, case when s.payee_type = g.payee_type then 0 else 1 end
                  limit 1
                ) sr on true
                order by g.section, g.total_paid desc
                """).param("b", props.businessId()).param("fy", fy).query(ThresholdQueryRow.class).list();
        List<ThresholdRow> rows = new ArrayList<>();
        for (ThresholdQueryRow q : raw) {
            boolean crossed = q.annualThreshold() != null && q.totalPaid().compareTo(q.annualThreshold()) > 0;
            BigDecimal remaining = q.annualThreshold() == null ? null : q.annualThreshold().subtract(q.totalPaid()).max(BigDecimal.ZERO);
            rows.add(new ThresholdRow(q.fyStartYear(), q.deducteeKey(), q.deducteeName(), q.deducteePan(), q.section(),
                    q.payeeType(), q.entries(), q.totalPaid(), q.totalTds(), q.singleThreshold(), q.annualThreshold(),
                    crossed, remaining, TdsCalculator.isBlank(q.deducteePan())));
        }
        return new ThresholdDto(TdsCalculator.fyLabel(fy), fy, rows,
                "Running totals per deductee (PAN, else name) and section for the financial year, from active entries. "
                        + "Thresholds are the seed values in the section master - verify with your CA. A deductee entered with and "
                        + "without a PAN appears as two rows.");
    }

    public record ThresholdQueryRow(int fyStartYear, String deducteeKey, String deducteeName, String deducteePan,
                                    String section, String payeeType, long entries, BigDecimal totalPaid,
                                    BigDecimal totalTds, BigDecimal singleThreshold, BigDecimal annualThreshold) {}

    // ------------------------------------------------------------------ pending deposits dashboard

    public PendingDto pending() {
        CurrentUser.require("tds.view");
        LocalDate today = clock.today();
        List<EntryDto> items = jdbc.sql(ENTRY_SELECT + """
                where e.business_id = :b and e.void_state = 'ACTIVE' and e.status = 'PENDING'
                  and e.threshold_status in ('DEDUCT','CHECK_WITH_CA')
                order by e.deposit_due_date nulls last, e.id
                """).param("b", props.businessId()).query(EntryRow.class).list().stream().map(r -> toDto(r, today)).toList();
        long count = 0, overdue = 0, check = 0;
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (EntryDto e : items) {
            if (TdsCalculator.CHECK_WITH_CA.equals(e.thresholdStatus())) {
                check++;
                continue;
            }
            count++;
            if (e.overdue()) overdue++;
            if (e.tdsAmount() != null) total = total.add(e.tdsAmount());
        }
        return new PendingDto(count, total, overdue, check, items);
    }

    // ------------------------------------------------------------------ TCS credits received from platforms

    public TcsSummary tcs(LocalDate from, LocalDate to, boolean includeVoided) {
        CurrentUser.require("tds.view");
        List<TcsRow> rows = jdbc.sql("""
                select id, platform, platform_name, period_start, period_end, tcs_amount, gst_tcs, notes, source,
                       void_state, void_reason, version, created_at
                from tcs_credits
                where business_id = :b
                  and (cast(:f as date) is null or period_end >= :f)
                  and (cast(:t as date) is null or period_start <= :t)
                  and (:inc or void_state = 'ACTIVE')
                order by period_start desc, id desc limit 500
                """).param("b", props.businessId()).param("f", from).param("t", to).param("inc", includeVoided)
                .query(TcsRow.class).list();
        BigDecimal gstTcs = BigDecimal.ZERO.setScale(2), other = BigDecimal.ZERO.setScale(2);
        for (TcsRow r : rows) {
            if (!"ACTIVE".equals(r.voidState())) continue;
            if (r.gstTcs()) gstTcs = gstTcs.add(r.tcsAmount()); else other = other.add(r.tcsAmount());
        }
        return new TcsSummary(rows, gstTcs, other);
    }

    @Transactional
    public TcsRow addTcs(TcsRequest r) {
        CurrentUser.require("tds.manage");
        if (r.platform() == null || !PLATFORMS.contains(r.platform())) throw ApiException.badRequest("Platform must be SWIGGY, ZOMATO or OTHER");
        if ("OTHER".equals(r.platform()) && (r.platformName() == null || r.platformName().isBlank())) {
            throw ApiException.badRequest("Enter the platform name");
        }
        if (r.periodStart() == null || r.periodEnd() == null || r.periodEnd().isBefore(r.periodStart())) {
            throw ApiException.badRequest("Enter the period the credit covers");
        }
        if (r.tcsAmount() == null || r.tcsAmount().signum() < 0) throw ApiException.badRequest("TCS amount must be zero or more");
        String source = r.source() == null || r.source().isBlank() ? "MANUAL" : r.source();
        if (!Set.of("MANUAL", "IMPORT").contains(source)) throw ApiException.badRequest("Source must be MANUAL or IMPORT");
        long id = jdbc.sql("""
                insert into tcs_credits (business_id, platform, platform_name, period_start, period_end, tcs_amount, gst_tcs,
                                         notes, source, created_by)
                values (:b, :p, :pn, :ps, :pe, :a, :g, :n, :s, :u) returning id
                """).param("b", props.businessId()).param("p", r.platform()).param("pn", blankToNull(r.platformName()))
                .param("ps", r.periodStart()).param("pe", r.periodEnd()).param("a", r.tcsAmount())
                .param("g", Boolean.TRUE.equals(r.gstTcs())).param("n", blankToNull(r.notes())).param("s", source)
                .param("u", CurrentUser.id()).query(Long.class).single();
        audit.record("TCS_CREDIT", id, "CREATE", r.periodEnd(), null, tcsSnapshot(id), null, source);
        return tcsRow(id);
    }

    @Transactional
    public TcsRow voidTcs(long id, String reason) {
        CurrentUser.require("tds.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a TCS credit");
        TcsRow cur = tcsRow(id);
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("Already voided");
        String before = tcsSnapshot(id);
        jdbc.sql("""
                update tcs_credits set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now(),
                       version = version + 1 where id = :id and business_id = :b
                """).param("r", reason).param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("TCS_CREDIT", id, "VOID", cur.periodEnd(), before, tcsSnapshot(id), reason, "MANUAL");
        return tcsRow(id);
    }

    private TcsRow tcsRow(long id) {
        return jdbc.sql("""
                select id, platform, platform_name, period_start, period_end, tcs_amount, gst_tcs, notes, source,
                       void_state, void_reason, version, created_at
                from tcs_credits where id = :id and business_id = :b
                """).param("id", id).param("b", props.businessId()).query(TcsRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("TCS credit"));
    }

    // ------------------------------------------------------------------ internals

    private record Calc(SectionRate section, String sectionCode, String payeeType, TdsCalculator.Result result,
                        LocalDate dueDate) {}

    private Calc calc(EntryRequest r, long selfId) {
        String pt = r.payeeType() == null || r.payeeType().isBlank() ? "ANY" : r.payeeType();
        String code = r.section().trim();
        SectionRate sec = resolveSection(code, pt, r.txnDate());
        String canonical = sec != null ? sec.code() : code.toUpperCase();
        BigDecimal prior = jdbc.sql("""
                select coalesce(sum(payment_amount), 0) from tds_entries
                where business_id = :b and void_state = 'ACTIVE' and upper(section) = upper(:sec) and fy_start_year = :fy
                  and deductee_key = coalesce(nullif(upper(btrim(cast(:pan as text))), ''), lower(btrim(:name)))
                  and txn_date <= :d and id <> :self
                """).param("b", props.businessId()).param("sec", canonical).param("fy", TdsCalculator.fyStartYear(r.txnDate()))
                .param("pan", panOrNull(r.deducteePan())).param("name", r.deducteeName()).param("d", r.txnDate())
                .param("self", selfId).query(BigDecimal.class).single();
        TdsCalculator.SectionRate cs = sec == null ? null
                : new TdsCalculator.SectionRate(sec.code(), sec.payeeType(), sec.rate(), sec.singleThreshold(), sec.annualThreshold());
        TdsCalculator.Result res = TdsCalculator.compute(cs, r.paymentAmount(), prior, r.deducteePan());
        LocalDate due = TdsCalculator.BELOW_THRESHOLD.equals(res.thresholdStatus()) ? null : TdsCalculator.depositDueDate(r.txnDate());
        return new Calc(sec, canonical, pt, res, due);
    }

    private void validate(EntryRequest r) {
        if (r.txnDate() == null) throw ApiException.badRequest("Payment date is required");
        if (r.txnDate().isAfter(clock.today())) throw ApiException.badRequest("Payment date cannot be in the future");
        if (r.txnDate().isBefore(clock.dataStartDate())) {
            throw ApiException.badRequest("Date is before the business data-start date " + clock.dataStartDate());
        }
        if (r.deducteeName() == null || r.deducteeName().isBlank()) throw ApiException.badRequest("Deductee (payee) name is required");
        if (r.section() == null || r.section().isBlank()) throw ApiException.badRequest("TDS section is required");
        if (r.paymentAmount() == null || r.paymentAmount().signum() <= 0) throw ApiException.badRequest("Payment amount must be more than zero");
        if (r.payeeType() != null && !r.payeeType().isBlank() && !PAYEE_TYPES.contains(r.payeeType())) {
            throw ApiException.badRequest("Payee type must be ANY, INDIVIDUAL_HUF or OTHER");
        }
    }

    private void checkExpense(Long expenseId) {
        if (expenseId == null) return;
        long n = jdbc.sql("select count(*) from expenses where id = :id and business_id = :b")
                .param("id", expenseId).param("b", props.businessId()).query(Long.class).single();
        if (n == 0) throw ApiException.badRequest("The linked expense does not exist");
    }

    private EntryRow row(long id) {
        return jdbc.sql(ENTRY_SELECT + " where e.id = :id and e.business_id = :b")
                .param("id", id).param("b", props.businessId()).query(EntryRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("TDS entry"));
    }

    private EntryDto toDto(EntryRow r, LocalDate today) {
        boolean overdue = "PENDING".equals(r.status()) && "ACTIVE".equals(r.voidState()) && r.depositDueDate() != null
                && !TdsCalculator.BELOW_THRESHOLD.equals(r.thresholdStatus()) && r.depositDueDate().isBefore(today);
        return new EntryDto(r.id(), r.txnDate(), r.entryAt(), r.deducteeName(), r.deducteePan(), r.section(), r.payeeType(),
                r.paymentAmount(), r.rateApplied(), r.tdsAmount(), r.thresholdStatus(), r.thresholdNote(),
                r.depositDueDate(), r.depositedOn(), r.challanNo(), r.status(), r.expenseId(), r.notes(), r.source(),
                r.revision(), r.version(), r.voidState(), r.voidReason(), TdsCalculator.fyLabel(r.fyStartYear()),
                r.createdByName(), TdsCalculator.isBlank(r.deducteePan()), overdue,
                StatusFlags.of(r.source(), "CONFIRMED", false, false, r.revision(), r.txnDate(), r.entryAt(),
                        clock.zone(), r.voidState()));
    }

    private String snapshot(long id) {
        return jdbc.sql("select to_jsonb(e)::text from tds_entries e where e.id = :id").param("id", id)
                .query(String.class).single();
    }

    private String tcsSnapshot(long id) {
        return jdbc.sql("select to_jsonb(t)::text from tcs_credits t where t.id = :id").param("id", id)
                .query(String.class).single();
    }

    private static String panOrNull(String pan) {
        return pan == null || pan.isBlank() ? null : pan.trim().toUpperCase();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
