package com.cafepos.compliance;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compliance calendar. Scheduler-free: due-date tasks are generated on demand (idempotently) whenever the calendar or
 * dashboard is opened. Default rules are seeded as "confirm with your CA" and can be edited by the owner.
 */
@Service
public class ComplianceService {

    private static final Set<String> CATEGORIES = Set.of("GST", "TDS", "PAYROLL", "FSSAI", "LICENCE", "OTHER");
    private static final Set<String> FREQUENCIES = Set.of("MONTHLY", "QUARTERLY", "ANNUAL", "ONE_TIME");
    private static final int MAX_WINDOW_DAYS = 800;

    public record RuleDto(long id, String code, String title, String category, int dueDay, String frequency,
                          int monthOffset, String appliesMonths, Integer dueMonth, Integer overridePeriodMonth,
                          Integer overrideMonthOffset, Integer overrideDueDay, String notes, String link, boolean active,
                          String source, OffsetDateTime updatedAt) {}

    public record RuleRequest(String code, String title, String category, Integer dueDay, String frequency,
                              Integer monthOffset, String appliesMonths, Integer dueMonth, Integer overridePeriodMonth,
                              Integer overrideMonthOffset, Integer overrideDueDay, String notes, String link,
                              Boolean active, String reason) {}

    public record TaskRow(long id, String ruleCode, LocalDate periodStart, String periodLabel, String title, String category,
                          LocalDate dueDate, String status, LocalDate completedOn, String completedByName, String notes,
                          String link, boolean generated) {}

    public record TaskDto(long id, String ruleCode, LocalDate periodStart, String periodLabel, String title, String category,
                          LocalDate dueDate, String status, LocalDate completedOn, String completedByName, String notes,
                          String link, boolean generated, boolean overdue, long daysToDue) {}

    public record TaskRequest(String title, String category, LocalDate dueDate, String notes, String link) {}

    public record CompleteRequest(LocalDate completedOn, String notes) {}

    public record DocRow(long id, String name, String number, LocalDate issuedOn, LocalDate expiresOn, int reminderDays,
                         Long fileDocumentId, String notes, boolean active) {}

    public record DocDto(long id, String name, String number, LocalDate issuedOn, LocalDate expiresOn, int reminderDays,
                         Long fileDocumentId, String notes, boolean active, String expiryStatus, Long daysToExpiry) {}

    public record DocRequest(String name, String number, LocalDate issuedOn, LocalDate expiresOn, Integer reminderDays,
                             Long fileDocumentId, String notes, Boolean active) {}

    public record PendingTds(long pendingCount, BigDecimal amount, long overdueCount, long checkWithCaCount) {}

    public record DraftWorkpaper(long id, String periodType, LocalDate periodStart, int version) {}

    public record RunStatus(long runId, LocalDate month, String status) {}

    public record PayrollStatus(RunStatus unfinishedRun, boolean lastMonthRunMissing, LocalDate lastMonth) {}

    public record Dashboard(LocalDate today, List<TaskDto> overdue, List<TaskDto> due7, List<TaskDto> due30,
                            List<DocDto> expiringDocuments, long documentsMissingExpiry, PendingTds pendingTds,
                            List<DraftWorkpaper> draftGstWorkpapers, PayrollStatus payroll, List<String> notes) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;

    public ComplianceService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ rules

    private static final String RULE_SELECT = """
            select id, code, title, category, due_day, frequency, month_offset,
                   array_to_string(applies_months, ',') as applies_months, due_month, override_period_month,
                   override_month_offset, override_due_day, notes, link, active, source, updated_at
            from compliance_rules
            """;

    public List<RuleDto> rules() {
        CurrentUser.require("compliance.view");
        return jdbc.sql(RULE_SELECT + " where business_id = :b order by category, code").param("b", props.businessId())
                .query(RuleDto.class).list();
    }

    private RuleDto rule(long id) {
        return jdbc.sql(RULE_SELECT + " where id = :id and business_id = :b").param("id", id).param("b", props.businessId())
                .query(RuleDto.class).optional().orElseThrow(() -> ApiException.notFound("Compliance rule"));
    }

    @Transactional
    public RuleDto updateRule(long id, RuleRequest r) {
        CurrentUser.require("compliance.configure");
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason is required to change a due-date rule");
        rule(id);
        Validated v = validateRule(r);
        String before = ruleSnapshot(id);
        jdbc.sql("""
                update compliance_rules set title = :t, category = :c, due_day = :d, frequency = :f, month_offset = :mo,
                       applies_months = cast(:am as int[]), due_month = :dm, override_period_month = :opm,
                       override_month_offset = :omo, override_due_day = :odd, notes = :n, link = :l, active = :a,
                       source = 'EDITED', updated_by = :u, updated_at = now()
                where id = :id and business_id = :b
                """)
                .param("t", r.title().trim()).param("c", r.category()).param("d", r.dueDay()).param("f", r.frequency())
                .param("mo", v.monthOffset()).param("am", v.monthsLiteral()).param("dm", r.dueMonth())
                .param("opm", r.overridePeriodMonth()).param("omo", r.overrideMonthOffset()).param("odd", r.overrideDueDay())
                .param("n", blank(r.notes())).param("l", blank(r.link())).param("a", r.active() == null || r.active())
                .param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("COMPLIANCE_RULE", id, "UPDATE", null, before, ruleSnapshot(id), r.reason(), "MANUAL");
        return rule(id);
    }

    @Transactional
    public RuleDto createRule(RuleRequest r) {
        CurrentUser.require("compliance.configure");
        if (r.code() == null || r.code().isBlank()) throw ApiException.badRequest("A short code is required");
        Validated v = validateRule(r);
        long id;
        try {
            id = jdbc.sql("""
                    insert into compliance_rules (business_id, code, title, category, due_day, frequency, month_offset,
                                                  applies_months, due_month, override_period_month, override_month_offset,
                                                  override_due_day, notes, link, active, source, updated_by)
                    values (:b, :code, :t, :c, :d, :f, :mo, cast(:am as int[]), :dm, :opm, :omo, :odd, :n, :l, :a, 'USER', :u)
                    returning id
                    """)
                    .param("b", props.businessId()).param("code", r.code().trim().toUpperCase()).param("t", r.title().trim())
                    .param("c", r.category()).param("d", r.dueDay()).param("f", r.frequency()).param("mo", v.monthOffset())
                    .param("am", v.monthsLiteral()).param("dm", r.dueMonth()).param("opm", r.overridePeriodMonth())
                    .param("omo", r.overrideMonthOffset()).param("odd", r.overrideDueDay()).param("n", blank(r.notes()))
                    .param("l", blank(r.link())).param("a", r.active() == null || r.active()).param("u", CurrentUser.id())
                    .query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A rule with code " + r.code().trim().toUpperCase() + " already exists");
        }
        audit.record("COMPLIANCE_RULE", id, "CREATE", null, null, ruleSnapshot(id), null, "MANUAL");
        return rule(id);
    }

    private record Validated(int monthOffset, String monthsLiteral) {}

    private Validated validateRule(RuleRequest r) {
        if (r.title() == null || r.title().isBlank()) throw ApiException.badRequest("Title is required");
        if (r.category() == null || !CATEGORIES.contains(r.category())) throw ApiException.badRequest("Unknown category");
        if (r.frequency() == null || !FREQUENCIES.contains(r.frequency())) throw ApiException.badRequest("Frequency must be MONTHLY, QUARTERLY, ANNUAL or ONE_TIME");
        if (r.dueDay() == null || r.dueDay() < 1 || r.dueDay() > 31) throw ApiException.badRequest("Due day must be between 1 and 31");
        int offset = r.monthOffset() == null ? 0 : r.monthOffset();
        if (offset < 0 || offset > 12) throw ApiException.badRequest("Month offset must be between 0 and 12");
        if ("ANNUAL".equals(r.frequency()) && (r.dueMonth() == null || r.dueMonth() < 1 || r.dueMonth() > 12)) {
            throw ApiException.badRequest("An annual rule needs a due month (1-12)");
        }
        if (r.overridePeriodMonth() != null && (r.overridePeriodMonth() < 1 || r.overridePeriodMonth() > 12)) {
            throw ApiException.badRequest("Override period month must be 1-12");
        }
        if (r.overrideMonthOffset() != null && (r.overrideMonthOffset() < 0 || r.overrideMonthOffset() > 12)) {
            throw ApiException.badRequest("Override month offset must be 0-12");
        }
        if (r.overrideDueDay() != null && (r.overrideDueDay() < 1 || r.overrideDueDay() > 31)) {
            throw ApiException.badRequest("Override due day must be 1-31");
        }
        String literal = null;
        if (r.appliesMonths() != null && !r.appliesMonths().isBlank()) {
            TreeSet<Integer> months = new TreeSet<>();
            for (String p : r.appliesMonths().split("[,\\s]+")) {
                if (p.isBlank()) continue;
                int m;
                try {
                    m = Integer.parseInt(p.trim());
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("Applies-to months must be numbers 1-12 separated by commas");
                }
                if (m < 1 || m > 12) throw ApiException.badRequest("Applies-to months must be numbers 1-12 separated by commas");
                months.add(m);
            }
            if (!months.isEmpty()) {
                StringBuilder sb = new StringBuilder("{");
                for (Integer m : months) sb.append(sb.length() > 1 ? "," : "").append(m);
                literal = sb.append('}').toString();
            }
        }
        return new Validated(offset, literal);
    }

    // ------------------------------------------------------------------ generation (on demand, idempotent)

    /** Inserts missing tasks for due dates inside [from, to]. Existing tasks (and their status) are never touched. */
    @Transactional
    public int ensureGenerated(LocalDate from, LocalDate to) {
        List<ComplianceCalendarGenerator.Rule> rules = new ArrayList<>();
        for (RuleDto r : jdbc.sql(RULE_SELECT + " where business_id = :b and active order by id").param("b", props.businessId())
                .query(RuleDto.class).list()) {
            rules.add(new ComplianceCalendarGenerator.Rule(r.code(), r.title(), r.category(), r.dueDay(), r.frequency(),
                    r.monthOffset(), parseMonths(r.appliesMonths()), r.dueMonth(), r.overridePeriodMonth(),
                    r.overrideMonthOffset(), r.overrideDueDay(), r.link()));
        }
        int inserted = 0;
        for (ComplianceCalendarGenerator.Task t : ComplianceCalendarGenerator.generate(rules, from, to, clock.dataStartDate())) {
            inserted += jdbc.sql("""
                    insert into compliance_tasks (business_id, rule_code, period_start, period_label, title, category, due_date,
                                                  link, generated)
                    values (:b, :rc, :ps, :pl, :t, :c, :d, :l, true)
                    on conflict (business_id, rule_code, period_start) where rule_code is not null do nothing
                    """).param("b", props.businessId()).param("rc", t.ruleCode()).param("ps", t.periodStart())
                    .param("pl", t.periodLabel()).param("t", t.title()).param("c", t.category()).param("d", t.dueDate())
                    .param("l", t.link()).update();
        }
        return inserted;
    }

    private static List<Integer> parseMonths(String csv) {
        if (csv == null || csv.isBlank()) return null;
        List<Integer> out = new ArrayList<>();
        for (String p : csv.split(",")) if (!p.isBlank()) out.add(Integer.valueOf(p.trim()));
        return out;
    }

    // ------------------------------------------------------------------ calendar + tasks

    private static final String TASK_SELECT = """
            select t.id, t.rule_code, t.period_start, t.period_label, t.title, t.category, t.due_date, t.status,
                   t.completed_on, u.name as completed_by_name, t.notes, t.link, t.generated
            from compliance_tasks t left join users u on u.id = t.completed_by
            """;

    /** Generates what is missing for the window, then returns tasks due in it plus any OPEN task already overdue before it. */
    @Transactional
    public List<TaskDto> calendar(LocalDate from, LocalDate to) {
        CurrentUser.require("compliance.view");
        LocalDate today = clock.today();
        LocalDate f = from != null ? from : today.withDayOfMonth(1).minusMonths(2);
        LocalDate t = to != null ? to : today.plusDays(150);
        if (t.isBefore(f)) throw ApiException.badRequest("'To' cannot be before 'from'");
        if (ChronoUnit.DAYS.between(f, t) > MAX_WINDOW_DAYS) throw ApiException.badRequest("Choose a window of at most " + MAX_WINDOW_DAYS + " days");
        ensureGenerated(f, t);
        return jdbc.sql(TASK_SELECT + """
                where t.business_id = :b and ((t.due_date >= :f and t.due_date <= :t) or (t.status = 'OPEN' and t.due_date < :f))
                order by t.due_date, t.id
                """).param("b", props.businessId()).param("f", f).param("t", t).query(TaskRow.class).list().stream()
                .map(r -> toDto(r, today)).toList();
    }

    @Transactional
    public TaskDto addTask(TaskRequest r) {
        CurrentUser.require("compliance.manage");
        if (r.title() == null || r.title().isBlank()) throw ApiException.badRequest("Title is required");
        if (r.dueDate() == null) throw ApiException.badRequest("Due date is required");
        String cat = r.category() == null || r.category().isBlank() ? "OTHER" : r.category();
        if (!CATEGORIES.contains(cat)) throw ApiException.badRequest("Unknown category");
        long id = jdbc.sql("""
                insert into compliance_tasks (business_id, title, category, due_date, notes, link, created_by)
                values (:b, :t, :c, :d, :n, :l, :u) returning id
                """).param("b", props.businessId()).param("t", r.title().trim()).param("c", cat).param("d", r.dueDate())
                .param("n", blank(r.notes())).param("l", blank(r.link())).param("u", CurrentUser.id()).query(Long.class).single();
        audit.record("COMPLIANCE_TASK", id, "CREATE", r.dueDate(), null, taskSnapshot(id), null, "MANUAL");
        return task(id);
    }

    @Transactional
    public TaskDto complete(long id, CompleteRequest r) {
        CurrentUser.require("compliance.manage");
        TaskDto cur = task(id);
        if (!"OPEN".equals(cur.status())) throw ApiException.conflict("This task is already " + cur.status());
        LocalDate today = clock.today();
        LocalDate on = r.completedOn() != null ? r.completedOn() : today;
        if (on.isAfter(today)) throw ApiException.badRequest("Completion date cannot be in the future");
        String before = taskSnapshot(id);
        jdbc.sql("""
                update compliance_tasks set status = 'DONE', completed_on = :d, completed_by = :u,
                       notes = coalesce(:n, notes), updated_at = now() where id = :id and business_id = :b
                """).param("d", on).param("u", CurrentUser.id()).param("n", blank(r.notes())).param("id", id)
                .param("b", props.businessId()).update();
        audit.record("COMPLIANCE_TASK", id, "COMPLETE", cur.dueDate(), before, taskSnapshot(id), r.notes(), "MANUAL");
        return task(id);
    }

    @Transactional
    public TaskDto skip(long id, String reason) {
        CurrentUser.require("compliance.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to skip a task");
        TaskDto cur = task(id);
        if (!"OPEN".equals(cur.status())) throw ApiException.conflict("This task is already " + cur.status());
        String before = taskSnapshot(id);
        jdbc.sql("""
                update compliance_tasks set status = 'SKIPPED', completed_on = :d, completed_by = :u,
                       notes = :n, updated_at = now() where id = :id and business_id = :b
                """).param("d", clock.today()).param("u", CurrentUser.id()).param("n", "Skipped: " + reason.trim())
                .param("id", id).param("b", props.businessId()).update();
        audit.record("COMPLIANCE_TASK", id, "SKIP", cur.dueDate(), before, taskSnapshot(id), reason, "MANUAL");
        return task(id);
    }

    @Transactional
    public TaskDto reopen(long id, String reason) {
        CurrentUser.require("compliance.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to reopen a task");
        TaskDto cur = task(id);
        if ("OPEN".equals(cur.status())) throw ApiException.conflict("This task is already open");
        String before = taskSnapshot(id);
        jdbc.sql("""
                update compliance_tasks set status = 'OPEN', completed_on = null, completed_by = null, updated_at = now()
                where id = :id and business_id = :b
                """).param("id", id).param("b", props.businessId()).update();
        audit.record("COMPLIANCE_TASK", id, "REOPEN", cur.dueDate(), before, taskSnapshot(id), reason, "MANUAL");
        return task(id);
    }

    private TaskDto task(long id) {
        return toDto(jdbc.sql(TASK_SELECT + " where t.id = :id and t.business_id = :b").param("id", id).param("b", props.businessId())
                .query(TaskRow.class).optional().orElseThrow(() -> ApiException.notFound("Task")), clock.today());
    }

    private TaskDto toDto(TaskRow r, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, r.dueDate());
        return new TaskDto(r.id(), r.ruleCode(), r.periodStart(), r.periodLabel(), r.title(), r.category(), r.dueDate(),
                r.status(), r.completedOn(), r.completedByName(), r.notes(), r.link(), r.generated(),
                "OPEN".equals(r.status()) && days < 0, days);
    }

    // ------------------------------------------------------------------ licences / documents

    public List<DocDto> documents(boolean includeInactive) {
        CurrentUser.require("compliance.view");
        LocalDate today = clock.today();
        return jdbc.sql("""
                select id, name, number, issued_on, expires_on, reminder_days, file_document_id, notes, active
                from compliance_documents where business_id = :b and (:inc or active)
                order by expires_on nulls last, name
                """).param("b", props.businessId()).param("inc", includeInactive).query(DocRow.class).list().stream()
                .map(d -> docDto(d, today)).toList();
    }

    @Transactional
    public DocDto addDocument(DocRequest r) {
        CurrentUser.require("compliance.manage");
        validateDoc(r);
        long id = jdbc.sql("""
                insert into compliance_documents (business_id, name, number, issued_on, expires_on, reminder_days,
                                                  file_document_id, notes, active, created_by)
                values (:b, :n, :num, :i, :e, :rd, :f, :notes, :a, :u) returning id
                """).param("b", props.businessId()).param("n", r.name().trim()).param("num", blank(r.number()))
                .param("i", r.issuedOn()).param("e", r.expiresOn()).param("rd", r.reminderDays() == null ? 30 : r.reminderDays())
                .param("f", r.fileDocumentId()).param("notes", blank(r.notes())).param("a", r.active() == null || r.active())
                .param("u", CurrentUser.id()).query(Long.class).single();
        audit.record("COMPLIANCE_DOCUMENT", id, "CREATE", null, null, docSnapshot(id), null, "MANUAL");
        return doc(id);
    }

    @Transactional
    public DocDto updateDocument(long id, DocRequest r) {
        CurrentUser.require("compliance.manage");
        validateDoc(r);
        doc(id);
        String before = docSnapshot(id);
        jdbc.sql("""
                update compliance_documents set name = :n, number = :num, issued_on = :i, expires_on = :e, reminder_days = :rd,
                       file_document_id = :f, notes = :notes, active = :a, updated_at = now()
                where id = :id and business_id = :b
                """).param("n", r.name().trim()).param("num", blank(r.number())).param("i", r.issuedOn()).param("e", r.expiresOn())
                .param("rd", r.reminderDays() == null ? 30 : r.reminderDays()).param("f", r.fileDocumentId())
                .param("notes", blank(r.notes())).param("a", r.active() == null || r.active()).param("id", id)
                .param("b", props.businessId()).update();
        audit.record("COMPLIANCE_DOCUMENT", id, "UPDATE", null, before, docSnapshot(id), "Licence record updated", "MANUAL");
        return doc(id);
    }

    private void validateDoc(DocRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Name is required");
        if (r.reminderDays() != null && r.reminderDays() < 0) throw ApiException.badRequest("Reminder days cannot be negative");
        if (r.issuedOn() != null && r.expiresOn() != null && r.expiresOn().isBefore(r.issuedOn())) {
            throw ApiException.badRequest("Expiry date cannot be before the issue date");
        }
    }

    private DocDto doc(long id) {
        DocRow row = jdbc.sql("""
                select id, name, number, issued_on, expires_on, reminder_days, file_document_id, notes, active
                from compliance_documents where id = :id and business_id = :b
                """).param("id", id).param("b", props.businessId()).query(DocRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Licence record"));
        return docDto(row, clock.today());
    }

    private DocDto docDto(DocRow d, LocalDate today) {
        return new DocDto(d.id(), d.name(), d.number(), d.issuedOn(), d.expiresOn(), d.reminderDays(), d.fileDocumentId(),
                d.notes(), d.active(), ComplianceCalendarGenerator.expiryStatus(d.expiresOn(), d.reminderDays(), today),
                d.expiresOn() == null ? null : ChronoUnit.DAYS.between(today, d.expiresOn()));
    }

    // ------------------------------------------------------------------ dashboard

    @Transactional
    public Dashboard dashboard() {
        CurrentUser.require("compliance.view");
        LocalDate today = clock.today();
        ensureGenerated(today.minusMonths(3), today.plusDays(60));
        List<TaskDto> open = jdbc.sql(TASK_SELECT + """
                where t.business_id = :b and t.status = 'OPEN' and t.due_date <= :lim order by t.due_date, t.id
                """).param("b", props.businessId()).param("lim", today.plusDays(30)).query(TaskRow.class).list().stream()
                .map(r -> toDto(r, today)).toList();
        List<TaskDto> overdue = open.stream().filter(TaskDto::overdue).toList();
        List<TaskDto> due7 = open.stream().filter(t -> !t.overdue() && t.daysToDue() <= 7).toList();
        List<TaskDto> due30 = open.stream().filter(t -> !t.overdue() && t.daysToDue() > 7).toList();
        List<DocDto> docs = documents(false);
        List<DocDto> expiring = docs.stream().filter(d -> !"ACTIVE".equals(d.expiryStatus())).toList();
        long noExpiry = docs.stream().filter(d -> d.expiresOn() == null).count();

        PendingTds tds = null;
        if (CurrentUser.has("tds.view")) {
            tds = jdbc.sql("""
                    select count(*) filter (where threshold_status = 'DEDUCT') as pending_count,
                           coalesce(sum(tds_amount) filter (where threshold_status = 'DEDUCT'), 0) as amount,
                           count(*) filter (where threshold_status = 'DEDUCT' and deposit_due_date < :today) as overdue_count,
                           count(*) filter (where threshold_status = 'CHECK_WITH_CA') as check_with_ca_count
                    from tds_entries where business_id = :b and void_state = 'ACTIVE' and status = 'PENDING'
                    """).param("b", props.businessId()).param("today", today).query(PendingTds.class).single();
        }
        List<DraftWorkpaper> drafts = null;
        if (CurrentUser.has("gst.view")) {
            drafts = jdbc.sql("""
                    select id, period_type, period_start, version from (
                      select distinct on (period_type, period_start) id, period_type, period_start, version, status
                      from gst_workpapers where business_id = :b order by period_type, period_start, version desc
                    ) x where status = 'DRAFT' order by period_start desc
                    """).param("b", props.businessId()).query(DraftWorkpaper.class).list();
        }
        PayrollStatus payroll = null;
        if (CurrentUser.has("payroll.view")) {
            RunStatus unfinished = jdbc.sql("""
                    select id as run_id, month, status from payroll_runs
                    where business_id = :b and void_state = 'ACTIVE' and status in ('DRAFT','APPROVED')
                    order by month limit 1
                    """).param("b", props.businessId()).query(RunStatus.class).optional().orElse(null);
            YearMonth prev = YearMonth.from(today).minusMonths(1);
            boolean missing = !prev.atEndOfMonth().isBefore(clock.dataStartDate())
                    && jdbc.sql("""
                        select exists (select 1 from employees where business_id = :b and active)
                           and not exists (select 1 from payroll_runs where business_id = :b and month = :m and void_state = 'ACTIVE')
                        """).param("b", props.businessId()).param("m", prev.atDay(1)).query(Boolean.class).single();
            payroll = new PayrollStatus(unfinished, missing, prev.atDay(1));
        }
        List<String> notes = List.of("Dates are defaults seeded for your CA to confirm. Nothing here files a return or pays tax.");
        return new Dashboard(today, overdue, due7, due30, expiring, noExpiry, tds, drafts, payroll, notes);
    }

    // ------------------------------------------------------------------ helpers

    private String ruleSnapshot(long id) {
        return jdbc.sql("select to_jsonb(r)::text from compliance_rules r where r.id = :id").param("id", id).query(String.class).single();
    }

    private String taskSnapshot(long id) {
        return jdbc.sql("select to_jsonb(t)::text from compliance_tasks t where t.id = :id").param("id", id).query(String.class).single();
    }

    private String docSnapshot(long id) {
        return jdbc.sql("select to_jsonb(d)::text from compliance_documents d where d.id = :id").param("id", id).query(String.class).single();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
