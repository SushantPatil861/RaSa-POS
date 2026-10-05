package com.cafepos.hygiene;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hygiene checklists and temperature logs. Acceptable temperature ranges are the OWNER's settings (seed values are
 * only starting points): this module never claims a regulatory limit.
 */
@Service
public class HygieneService {

    private static final Set<String> FREQUENCIES = Set.of("DAILY", "WEEKLY", "MONTHLY");
    private static final Set<String> SHIFTS = Set.of("OPENING", "CLOSING", "OTHER");
    private static final Set<String> KINDS = Set.of("FREEZER", "CHILLER", "HOT_HOLD", "DISPLAY", "OTHER");
    private static final int MAX_REPORT_DAYS = 92;

    public record ItemDto(long id, long checklistId, String text, int sortOrder, boolean requiresPhoto, boolean active) {}

    public record ChecklistRow(long id, String name, String frequency, String expectedShift, boolean active,
                               LocalDate startDate, String source, String notes) {}

    public record ChecklistDto(long id, String name, String frequency, String expectedShift, boolean active,
                               LocalDate startDate, String source, String notes, List<ItemDto> items) {}

    public record ItemRequest(Long id, String text, Boolean requiresPhoto, Boolean active) {}

    public record ChecklistRequest(String name, String frequency, String expectedShift, Boolean active, String notes,
                                   List<ItemRequest> items) {}

    public record ResultEntry(Long itemId, Boolean ok, String note) {}

    public record LogRequest(Long checklistId, LocalDate logDate, String shift, List<ResultEntry> results, String notes) {}

    public record LogRow(long id, long checklistId, String checklistName, LocalDate logDate, String shift, Long completedBy,
                         String completedByName, OffsetDateTime completedAt, String results, boolean allOk, String notes,
                         String voidState, String voidReason, Long signedOffBy, String signedOffByName,
                         OffsetDateTime signedOffAt) {}

    public record LogDto(long id, long checklistId, String checklistName, LocalDate logDate, String shift, Long completedBy,
                         String completedByName, OffsetDateTime completedAt, JsonNode results, boolean allOk, String notes,
                         String voidState, String voidReason, Long signedOffBy, String signedOffByName,
                         OffsetDateTime signedOffAt) {}

    public record TodayRow(long checklistId, String name, String frequency, String expectedShift, boolean done,
                           Long logId, Boolean allOk, String completedByName, OffsetDateTime completedAt, String period,
                           boolean needsSignOff) {}

    public record PointRow(long id, String name, String kind, BigDecimal minC, BigDecimal maxC, boolean active,
                           LocalDate startDate, String source, String notes, BigDecimal lastTempC,
                           OffsetDateTime lastLoggedAt, Boolean lastInRange) {}

    public record PointRequest(String name, String kind, BigDecimal minC, BigDecimal maxC, Boolean active, String notes) {}

    public record TempLogRow(long id, long pointId, String pointName, OffsetDateTime loggedAt, LocalDate logDate,
                             BigDecimal tempC, BigDecimal minC, BigDecimal maxC, boolean inRange, String correctiveAction,
                             String notes, String loggedByName, String voidState, String voidReason) {}

    public record TempLogRequest(Long pointId, BigDecimal tempC, String correctiveAction, String notes) {}

    public record ChecklistCoverage(long checklistId, String name, String frequency, int expected, int completed,
                                    BigDecimal compliancePct, List<LocalDate> missed) {}

    public record PointCoverage(long pointId, String name, int expectedDays, int loggedDays, BigDecimal compliancePct,
                                List<LocalDate> missedDays) {}

    public record MissedReport(LocalDate from, LocalDate to, List<ChecklistCoverage> checklists, List<PointCoverage> points,
                               List<TempLogRow> outOfRange, BigDecimal checklistCompliancePct,
                               BigDecimal temperatureCompliancePct, BigDecimal overallCompliancePct, List<String> notes) {}

    public record LogKey(long checklistId, LocalDate logDate, String shift) {}

    public record PointDay(long pointId, LocalDate logDate) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;
    private final ObjectMapper json;

    public HygieneService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ checklists

    public List<ChecklistDto> checklists(boolean includeInactive) {
        CurrentUser.require("hygiene.view");
        List<ChecklistRow> rows = jdbc.sql("""
                select id, name, frequency, expected_shift, active, start_date, source, notes from hygiene_checklists
                where business_id = :b and (:inc or active) order by frequency, id
                """).param("b", props.businessId()).param("inc", includeInactive).query(ChecklistRow.class).list();
        List<ItemDto> items = jdbc.sql("""
                select i.id, i.checklist_id, i.text, i.sort_order, i.requires_photo, i.active
                from hygiene_checklist_items i join hygiene_checklists c on c.id = i.checklist_id
                where c.business_id = :b and (:inc or i.active) order by i.checklist_id, i.sort_order, i.id
                """).param("b", props.businessId()).param("inc", includeInactive).query(ItemDto.class).list();
        List<ChecklistDto> out = new ArrayList<>();
        for (ChecklistRow c : rows) {
            out.add(new ChecklistDto(c.id(), c.name(), c.frequency(), c.expectedShift(), c.active(), c.startDate(),
                    c.source(), c.notes(), items.stream().filter(i -> i.checklistId() == c.id()).toList()));
        }
        return out;
    }

    private ChecklistDto checklist(long id) {
        return checklists(true).stream().filter(c -> c.id() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("Checklist"));
    }

    @Transactional
    public ChecklistDto createChecklist(ChecklistRequest r) {
        CurrentUser.require("hygiene.manage");
        validateChecklist(r);
        long id;
        try {
            id = jdbc.sql("""
                    insert into hygiene_checklists (business_id, name, frequency, expected_shift, active, notes, source)
                    values (:b, :n, :f, :s, :a, :notes, 'USER') returning id
                    """).param("b", props.businessId()).param("n", r.name().trim()).param("f", r.frequency())
                    .param("s", blank(r.expectedShift())).param("a", r.active() == null || r.active())
                    .param("notes", blank(r.notes())).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A checklist named '" + r.name().trim() + "' already exists");
        }
        saveItems(id, r.items());
        audit.record("HYGIENE_CHECKLIST", id, "CREATE", null, null, checklistSnapshot(id), null, "MANUAL");
        return checklist(id);
    }

    @Transactional
    public ChecklistDto updateChecklist(long id, ChecklistRequest r) {
        CurrentUser.require("hygiene.manage");
        validateChecklist(r);
        checklist(id);
        String before = checklistSnapshot(id);
        try {
            jdbc.sql("""
                    update hygiene_checklists set name = :n, frequency = :f, expected_shift = :s, active = :a, notes = :notes
                    where id = :id and business_id = :b
                    """).param("n", r.name().trim()).param("f", r.frequency()).param("s", blank(r.expectedShift()))
                    .param("a", r.active() == null || r.active()).param("notes", blank(r.notes()))
                    .param("id", id).param("b", props.businessId()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A checklist named '" + r.name().trim() + "' already exists");
        }
        // items not mentioned are switched off, never deleted (old logs keep their meaning)
        jdbc.sql("update hygiene_checklist_items set active = false where checklist_id = :c").param("c", id).update();
        saveItems(id, r.items());
        audit.record("HYGIENE_CHECKLIST", id, "UPDATE", null, before, checklistSnapshot(id), "Checklist edited", "MANUAL");
        return checklist(id);
    }

    private void saveItems(long checklistId, List<ItemRequest> items) {
        if (items == null) return;
        int order = 0;
        for (ItemRequest i : items) {
            if (i.text() == null || i.text().isBlank()) continue;
            order++;
            boolean active = i.active() == null || i.active();
            if (i.id() != null) {
                int n = jdbc.sql("""
                        update hygiene_checklist_items set text = :t, sort_order = :o, requires_photo = :p, active = :a
                        where id = :id and checklist_id = :c
                        """).param("t", i.text().trim()).param("o", order).param("p", Boolean.TRUE.equals(i.requiresPhoto()))
                        .param("a", active).param("id", i.id()).param("c", checklistId).update();
                if (n == 0) throw ApiException.badRequest("Item " + i.id() + " does not belong to this checklist");
            } else {
                jdbc.sql("""
                        insert into hygiene_checklist_items (checklist_id, text, sort_order, requires_photo, active)
                        values (:c, :t, :o, :p, :a)
                        """).param("c", checklistId).param("t", i.text().trim()).param("o", order)
                        .param("p", Boolean.TRUE.equals(i.requiresPhoto())).param("a", active).update();
            }
        }
    }

    private void validateChecklist(ChecklistRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Checklist name is required");
        if (r.frequency() == null || !FREQUENCIES.contains(r.frequency())) throw ApiException.badRequest("Frequency must be DAILY, WEEKLY or MONTHLY");
        if (r.expectedShift() != null && !r.expectedShift().isBlank() && !SHIFTS.contains(r.expectedShift())) {
            throw ApiException.badRequest("Shift must be OPENING, CLOSING or OTHER");
        }
    }

    // ------------------------------------------------------------------ logs

    private static final String LOG_SELECT = """
            select l.id, l.checklist_id, c.name as checklist_name, l.log_date, l.shift, l.completed_by,
                   cu.name as completed_by_name, l.completed_at, l.results::text as results, l.all_ok, l.notes,
                   l.void_state, l.void_reason, l.signed_off_by, su.name as signed_off_by_name, l.signed_off_at
            from hygiene_logs l
            join hygiene_checklists c on c.id = l.checklist_id
            left join users cu on cu.id = l.completed_by
            left join users su on su.id = l.signed_off_by
            """;

    public List<LogDto> logs(LocalDate from, LocalDate to, Long checklistId, boolean includeVoided) {
        CurrentUser.require("hygiene.view");
        return jdbc.sql(LOG_SELECT + """
                where l.business_id = :b
                  and (cast(:f as date) is null or l.log_date >= :f)
                  and (cast(:t as date) is null or l.log_date <= :t)
                  and (cast(:c as bigint) is null or l.checklist_id = :c)
                  and (:inc or l.void_state = 'ACTIVE')
                order by l.log_date desc, l.completed_at desc limit 300
                """).param("b", props.businessId()).param("f", from).param("t", to).param("c", checklistId)
                .param("inc", includeVoided).query(LogRow.class).list().stream().map(this::toDto).toList();
    }

    /** One row per active checklist: has it been done for the given day (DAILY), week (WEEKLY) or month (MONTHLY)? */
    public List<TodayRow> today(LocalDate date) {
        CurrentUser.require("hygiene.view");
        LocalDate d = date != null ? date : clock.today();
        List<TodayRow> out = new ArrayList<>();
        for (ChecklistRow c : jdbc.sql("""
                select id, name, frequency, expected_shift, active, start_date, source, notes from hygiene_checklists
                where business_id = :b and active order by frequency, id
                """).param("b", props.businessId()).query(ChecklistRow.class).list()) {
            LocalDate from, to;
            String period;
            switch (c.frequency()) {
                case "WEEKLY" -> {
                    from = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    to = from.plusDays(6);
                    period = "this week";
                }
                case "MONTHLY" -> {
                    from = d.withDayOfMonth(1);
                    to = YearMonth.from(d).atEndOfMonth();
                    period = "this month";
                }
                default -> {
                    from = d;
                    to = d;
                    period = "today";
                }
            }
            List<LogRow> found = jdbc.sql(LOG_SELECT + """
                    where l.checklist_id = :c and l.void_state = 'ACTIVE' and l.log_date >= :f and l.log_date <= :t
                      and (cast(:s as text) is null or l.shift = :s)
                    order by l.log_date desc, l.completed_at desc limit 1
                    """).param("c", c.id()).param("f", from).param("t", to).param("s", c.expectedShift())
                    .query(LogRow.class).list();
            if (found.isEmpty()) {
                out.add(new TodayRow(c.id(), c.name(), c.frequency(), c.expectedShift(), false, null, null, null, null, period, false));
            } else {
                LogRow l = found.get(0);
                out.add(new TodayRow(c.id(), c.name(), c.frequency(), c.expectedShift(), true, l.id(), l.allOk(),
                        l.completedByName(), l.completedAt(), period, l.signedOffBy() == null));
            }
        }
        return out;
    }

    @Transactional
    public LogDto logChecklist(LogRequest r) {
        CurrentUser.require("hygiene.log");
        if (r.checklistId() == null) throw ApiException.badRequest("Checklist is required");
        ChecklistDto c = checklist(r.checklistId());
        if (!c.active()) throw ApiException.badRequest("This checklist is switched off");
        LocalDate today = clock.today();
        LocalDate date = r.logDate() != null ? r.logDate() : today;
        if (date.isAfter(today)) throw ApiException.badRequest("A checklist cannot be logged for a future date");
        if (date.isBefore(today.minusDays(1)) && !CurrentUser.has("hygiene.manage")) {
            throw ApiException.forbidden("Only a manager can log a checklist for a date more than 1 day ago");
        }
        String shift = r.shift() == null || r.shift().isBlank() ? (c.expectedShift() != null ? c.expectedShift() : "OTHER") : r.shift();
        if (!SHIFTS.contains(shift)) throw ApiException.badRequest("Shift must be OPENING, CLOSING or OTHER");
        if (c.expectedShift() != null && !c.expectedShift().equals(shift)) {
            throw ApiException.badRequest("This checklist is for the " + c.expectedShift() + " shift");
        }
        if (r.results() == null) throw ApiException.badRequest("Tick every item");
        Map<Long, ItemDto> items = new LinkedHashMap<>();
        for (ItemDto i : c.items()) if (i.active()) items.put(i.id(), i);
        Map<Long, ResultEntry> given = new HashMap<>();
        for (ResultEntry e : r.results()) {
            if (e.itemId() == null || !items.containsKey(e.itemId())) throw ApiException.badRequest("Unknown checklist item " + e.itemId());
            if (e.ok() == null) throw ApiException.badRequest("Mark every item OK or not OK");
            if (!e.ok() && (e.note() == null || e.note().isBlank())) {
                throw ApiException.badRequest("Add a note for '" + items.get(e.itemId()).text() + "' because it is not OK");
            }
            given.put(e.itemId(), e);
        }
        List<Map<String, Object>> stored = new ArrayList<>();
        boolean allOk = true;
        for (ItemDto i : items.values()) {
            ResultEntry e = given.get(i.id());
            if (e == null) throw ApiException.badRequest("Item not answered: " + i.text());
            allOk &= e.ok();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", i.id());
            m.put("text", i.text());
            m.put("ok", e.ok());
            m.put("note", blank(e.note()));
            stored.add(m);
        }
        long id;
        try {
            id = jdbc.sql("""
                    insert into hygiene_logs (business_id, checklist_id, log_date, shift, completed_by, results, all_ok, notes)
                    values (:b, :c, :d, :s, :u, cast(:res as jsonb), :ok, :n) returning id
                    """).param("b", props.businessId()).param("c", c.id()).param("d", date).param("s", shift)
                    .param("u", CurrentUser.id()).param("res", toJson(stored)).param("ok", allOk).param("n", blank(r.notes()))
                    .query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("'" + c.name() + "' is already logged for " + date + " (" + shift + "). Ask a manager to void it if it must be redone.");
        }
        audit.record("HYGIENE_LOG", id, "CREATE", date, null, logSnapshot(id), null, "MANUAL");
        return toDto(logRow(id));
    }

    @Transactional
    public LogDto signOff(long id) {
        CurrentUser.require("hygiene.manage");
        LogRow cur = logRow(id);
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("This log is voided");
        if (cur.signedOffBy() != null) throw ApiException.conflict("Already signed off");
        String before = logSnapshot(id);
        jdbc.sql("update hygiene_logs set signed_off_by = :u, signed_off_at = now() where id = :id and business_id = :b")
                .param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("HYGIENE_LOG", id, "SIGN_OFF", cur.logDate(), before, logSnapshot(id), null, "MANUAL");
        return toDto(logRow(id));
    }

    @Transactional
    public LogDto voidLog(long id, String reason) {
        CurrentUser.require("hygiene.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a log");
        LogRow cur = logRow(id);
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("Already voided");
        String before = logSnapshot(id);
        jdbc.sql("""
                update hygiene_logs set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now()
                where id = :id and business_id = :b
                """).param("r", reason.trim()).param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("HYGIENE_LOG", id, "VOID", cur.logDate(), before, logSnapshot(id), reason, "MANUAL");
        return toDto(logRow(id));
    }

    private LogRow logRow(long id) {
        return jdbc.sql(LOG_SELECT + " where l.id = :id and l.business_id = :b").param("id", id).param("b", props.businessId())
                .query(LogRow.class).optional().orElseThrow(() -> ApiException.notFound("Hygiene log"));
    }

    private LogDto toDto(LogRow l) {
        return new LogDto(l.id(), l.checklistId(), l.checklistName(), l.logDate(), l.shift(), l.completedBy(),
                l.completedByName(), l.completedAt(), tree(l.results()), l.allOk(), l.notes(), l.voidState(),
                l.voidReason(), l.signedOffBy(), l.signedOffByName(), l.signedOffAt());
    }

    // ------------------------------------------------------------------ temperature points + logs

    private static final String POINT_SELECT = """
            select p.id, p.name, p.kind, p.min_c, p.max_c, p.active, p.start_date, p.source, p.notes,
                   lt.temp_c as last_temp_c, lt.logged_at as last_logged_at, lt.in_range as last_in_range
            from temperature_points p
            left join lateral (
              select t.temp_c, t.logged_at, t.in_range from temperature_logs t
              where t.point_id = p.id and t.void_state = 'ACTIVE' order by t.logged_at desc limit 1
            ) lt on true
            """;

    public List<PointRow> points(boolean includeInactive) {
        CurrentUser.require("hygiene.view");
        return jdbc.sql(POINT_SELECT + " where p.business_id = :b and (:inc or p.active) order by p.id")
                .param("b", props.businessId()).param("inc", includeInactive).query(PointRow.class).list();
    }

    private PointRow point(long id) {
        return jdbc.sql(POINT_SELECT + " where p.id = :id and p.business_id = :b").param("id", id).param("b", props.businessId())
                .query(PointRow.class).optional().orElseThrow(() -> ApiException.notFound("Temperature point"));
    }

    @Transactional
    public PointRow createPoint(PointRequest r) {
        CurrentUser.require("hygiene.manage");
        validatePoint(r);
        long id;
        try {
            id = jdbc.sql("""
                    insert into temperature_points (business_id, name, kind, min_c, max_c, active, notes, source)
                    values (:b, :n, :k, :min, :max, :a, :notes, 'USER') returning id
                    """).param("b", props.businessId()).param("n", r.name().trim()).param("k", r.kind())
                    .param("min", r.minC()).param("max", r.maxC()).param("a", r.active() == null || r.active())
                    .param("notes", blank(r.notes())).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A temperature point named '" + r.name().trim() + "' already exists");
        }
        audit.record("TEMPERATURE_POINT", id, "CREATE", null, null, pointSnapshot(id), null, "MANUAL");
        return point(id);
    }

    @Transactional
    public PointRow updatePoint(long id, PointRequest r) {
        CurrentUser.require("hygiene.manage");
        validatePoint(r);
        point(id);
        String before = pointSnapshot(id);
        try {
            jdbc.sql("""
                    update temperature_points set name = :n, kind = :k, min_c = :min, max_c = :max, active = :a, notes = :notes
                    where id = :id and business_id = :b
                    """).param("n", r.name().trim()).param("k", r.kind()).param("min", r.minC()).param("max", r.maxC())
                    .param("a", r.active() == null || r.active()).param("notes", blank(r.notes()))
                    .param("id", id).param("b", props.businessId()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A temperature point named '" + r.name().trim() + "' already exists");
        }
        audit.record("TEMPERATURE_POINT", id, "UPDATE", null, before, pointSnapshot(id), "Range or details edited", "MANUAL");
        return point(id);
    }

    private void validatePoint(PointRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Name is required");
        if (r.kind() == null || !KINDS.contains(r.kind())) throw ApiException.badRequest("Kind must be FREEZER, CHILLER, HOT_HOLD, DISPLAY or OTHER");
        if (r.minC() == null || r.maxC() == null) throw ApiException.badRequest("Enter the lowest and highest acceptable temperature");
        if (r.maxC().compareTo(r.minC()) < 0) throw ApiException.badRequest("The highest acceptable temperature cannot be below the lowest");
    }

    private static final String TEMP_SELECT = """
            select t.id, t.point_id, p.name as point_name, t.logged_at, t.log_date, t.temp_c, t.min_c, t.max_c, t.in_range,
                   t.corrective_action, t.notes, u.name as logged_by_name, t.void_state, t.void_reason
            from temperature_logs t
            join temperature_points p on p.id = t.point_id
            left join users u on u.id = t.logged_by
            """;

    public List<TempLogRow> temperatureLogs(LocalDate from, LocalDate to, Long pointId, boolean outOfRangeOnly, boolean includeVoided) {
        CurrentUser.require("hygiene.view");
        return jdbc.sql(TEMP_SELECT + """
                where t.business_id = :b
                  and (cast(:f as date) is null or t.log_date >= :f)
                  and (cast(:t as date) is null or t.log_date <= :t)
                  and (cast(:p as bigint) is null or t.point_id = :p)
                  and (not :oor or not t.in_range)
                  and (:inc or t.void_state = 'ACTIVE')
                order by t.logged_at desc limit 500
                """).param("b", props.businessId()).param("f", from).param("t", to).param("p", pointId)
                .param("oor", outOfRangeOnly).param("inc", includeVoided).query(TempLogRow.class).list();
    }

    /** in_range is computed here AND stored; an out-of-range reading cannot be saved without a corrective action. */
    @Transactional
    public TempLogRow logTemperature(TempLogRequest r) {
        CurrentUser.require("hygiene.log");
        if (r.pointId() == null) throw ApiException.badRequest("Choose the freezer / chiller");
        if (r.tempC() == null) throw ApiException.badRequest("Enter the temperature");
        if (r.tempC().compareTo(new BigDecimal("-80")) < 0 || r.tempC().compareTo(new BigDecimal("200")) > 0) {
            throw ApiException.badRequest("That temperature does not look right (allowed -80 to 200 C)");
        }
        PointRow p = point(r.pointId());
        if (!p.active()) throw ApiException.badRequest("This temperature point is switched off");
        boolean inRange = HygieneMath.inRange(r.tempC(), p.minC(), p.maxC());
        String action = blank(r.correctiveAction());
        if (!inRange && action == null) {
            throw ApiException.badRequest("Corrective action is required: " + r.tempC().stripTrailingZeros().toPlainString()
                    + " C is outside the acceptable range " + p.minC() + " to " + p.maxC() + " C for " + p.name());
        }
        long id = jdbc.sql("""
                insert into temperature_logs (business_id, point_id, log_date, temp_c, min_c, max_c, in_range, corrective_action,
                                              notes, logged_by)
                values (:b, :p, :d, :t, :min, :max, :ir, :ca, :n, :u) returning id
                """).param("b", props.businessId()).param("p", p.id()).param("d", clock.today()).param("t", r.tempC())
                .param("min", p.minC()).param("max", p.maxC()).param("ir", inRange).param("ca", action)
                .param("n", blank(r.notes())).param("u", CurrentUser.id()).query(Long.class).single();
        audit.record("TEMPERATURE_LOG", id, "CREATE", clock.today(), null, tempSnapshot(id), null, "MANUAL");
        return jdbc.sql(TEMP_SELECT + " where t.id = :id").param("id", id).query(TempLogRow.class).single();
    }

    @Transactional
    public TempLogRow voidTemperature(long id, String reason) {
        CurrentUser.require("hygiene.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a reading");
        TempLogRow cur = jdbc.sql(TEMP_SELECT + " where t.id = :id and t.business_id = :b").param("id", id)
                .param("b", props.businessId()).query(TempLogRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Temperature reading"));
        if ("VOIDED".equals(cur.voidState())) throw ApiException.conflict("Already voided");
        String before = tempSnapshot(id);
        jdbc.sql("""
                update temperature_logs set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now()
                where id = :id and business_id = :b
                """).param("r", reason.trim()).param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("TEMPERATURE_LOG", id, "VOID", cur.logDate(), before, tempSnapshot(id), reason, "MANUAL");
        return jdbc.sql(TEMP_SELECT + " where t.id = :id").param("id", id).query(TempLogRow.class).single();
    }

    // ------------------------------------------------------------------ report: missed checks, out-of-range, compliance %

    public MissedReport missedReport(LocalDate from, LocalDate to) {
        CurrentUser.require("hygiene.view");
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("Choose a date range");
        if (to.toEpochDay() - from.toEpochDay() + 1 > MAX_REPORT_DAYS) {
            throw ApiException.badRequest("Choose a range of at most " + MAX_REPORT_DAYS + " days");
        }
        LocalDate today = clock.today();
        LocalDate judgeTo = to.isBefore(today) ? to : today.minusDays(1);   // today is still in progress
        Set<LocalDate> closed = new HashSet<>(jdbc.sql("""
                select cal_date from business_calendar where business_id = :b and cal_date >= :f and cal_date <= :t
                  and day_type in ('CLOSED','HOLIDAY')
                """).param("b", props.businessId()).param("f", from).param("t", to).query(LocalDate.class).list());

        List<ChecklistRow> lists = jdbc.sql("""
                select id, name, frequency, expected_shift, active, start_date, source, notes from hygiene_checklists
                where business_id = :b and active order by frequency, id
                """).param("b", props.businessId()).query(ChecklistRow.class).list();
        List<LogKey> logs = jdbc.sql("""
                select checklist_id, log_date, shift from hygiene_logs
                where business_id = :b and void_state = 'ACTIVE' and log_date >= :f and log_date <= :t
                """).param("b", props.businessId()).param("f", from).param("t", to).query(LogKey.class).list();

        List<ChecklistCoverage> cov = new ArrayList<>();
        int chkExpected = 0, chkDone = 0;
        for (ChecklistRow c : lists) {
            LocalDate effFrom = from.isBefore(c.startDate()) ? c.startDate() : from;
            List<LocalDate> done = new ArrayList<>();
            for (LogKey k : logs) {
                if (k.checklistId() == c.id() && (c.expectedShift() == null || c.expectedShift().equals(k.shift()))) done.add(k.logDate());
            }
            HygieneMath.Coverage cv = HygieneMath.coverage(c.frequency(), effFrom, judgeTo, closed, done);
            chkExpected += cv.expected();
            chkDone += cv.completed();
            cov.add(new ChecklistCoverage(c.id(), c.name(), c.frequency(), cv.expected(), cv.completed(),
                    HygieneMath.percent(cv.completed(), cv.expected()), cv.missed()));
        }

        List<PointRow> pts = points(false);
        List<PointDay> pdays = jdbc.sql("""
                select distinct point_id, log_date from temperature_logs
                where business_id = :b and void_state = 'ACTIVE' and log_date >= :f and log_date <= :t
                """).param("b", props.businessId()).param("f", from).param("t", to).query(PointDay.class).list();
        List<PointCoverage> pcov = new ArrayList<>();
        int tExpected = 0, tDone = 0;
        for (PointRow p : pts) {
            LocalDate effFrom = from.isBefore(p.startDate()) ? p.startDate() : from;
            Set<LocalDate> logged = new HashSet<>();
            for (PointDay pd : pdays) if (pd.pointId() == p.id()) logged.add(pd.logDate());
            List<LocalDate> missed = new ArrayList<>();
            int expected = 0, got = 0;
            for (LocalDate d = effFrom; !d.isAfter(judgeTo); d = d.plusDays(1)) {
                if (closed.contains(d)) continue;
                expected++;
                if (logged.contains(d)) got++; else missed.add(d);
            }
            tExpected += expected;
            tDone += got;
            pcov.add(new PointCoverage(p.id(), p.name(), expected, got, HygieneMath.percent(got, expected), missed));
        }
        List<TempLogRow> oor = temperatureLogs(from, to, null, true, false);
        List<String> notes = new ArrayList<>();
        notes.add("Today is not judged until it has ended. Weekly and monthly checklists are judged only for complete weeks / months inside the range.");
        notes.add("Closed days and holidays (Settings > calendar) are not expected. Percentages are blank, not 100%, when nothing was expected.");
        notes.add("Temperature ranges are your own settings; seed values were only starting points to review for your equipment.");
        return new MissedReport(from, to, cov, pcov, oor, HygieneMath.percent(chkDone, chkExpected),
                HygieneMath.percent(tDone, tExpected), HygieneMath.percent(chkDone + tDone, chkExpected + tExpected), notes);
    }

    // ------------------------------------------------------------------ helpers

    private String checklistSnapshot(long id) {
        return jdbc.sql("""
                select (to_jsonb(c) || jsonb_build_object('items', coalesce((select jsonb_agg(to_jsonb(i) order by i.sort_order, i.id)
                       from hygiene_checklist_items i where i.checklist_id = c.id), '[]'::jsonb)))::text
                from hygiene_checklists c where c.id = :id
                """).param("id", id).query(String.class).single();
    }

    private String logSnapshot(long id) {
        return jdbc.sql("select to_jsonb(l)::text from hygiene_logs l where l.id = :id").param("id", id).query(String.class).single();
    }

    private String pointSnapshot(long id) {
        return jdbc.sql("select to_jsonb(p)::text from temperature_points p where p.id = :id").param("id", id).query(String.class).single();
    }

    private String tempSnapshot(long id) {
        return jdbc.sql("select to_jsonb(t)::text from temperature_logs t where t.id = :id").param("id", id).query(String.class).single();
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise hygiene results", e);
        }
    }

    private JsonNode tree(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored hygiene JSON is invalid", e);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
