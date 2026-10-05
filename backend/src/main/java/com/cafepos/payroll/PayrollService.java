package com.cafepos.payroll;

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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Employees, attendance and salary advances. No Aadhaar or other identity numbers are stored.
 * Salary figures are visible only with payroll.view; attendance clerks (attendance.manage) see names only.
 */
@Service
public class PayrollService {

    private static final Set<String> SALARY_TYPES = Set.of("MONTHLY", "DAILY", "HOURLY");
    private static final Set<String> ATT_STATUS = Set.of("PRESENT", "ABSENT", "HALF_DAY", "PAID_LEAVE", "WEEKLY_OFF", "HOLIDAY");

    public record EmployeeDto(long id, String name, String designation, String mobile, LocalDate joinedOn, LocalDate leftOn,
                              String salaryType, BigDecimal baseRate, BigDecimal overtimeMultiplier, Long userId,
                              String upiOrBankNote, String notes, boolean active, int version) {}

    public record EmployeeRequest(String name, String designation, String mobile, LocalDate joinedOn, LocalDate leftOn,
                                  String salaryType, BigDecimal baseRate, BigDecimal overtimeMultiplier, Long userId,
                                  String upiOrBankNote, String notes, Boolean active, String reason, Integer version) {}

    /** Names only: safe to show to people who mark attendance but must not see salaries. */
    public record RosterRow(long id, String name, String designation, boolean active, LocalDate joinedOn, LocalDate leftOn) {}

    public record AttendanceCell(long employeeId, LocalDate attDate, String status, BigDecimal hours,
                                 BigDecimal overtimeHours, String notes) {}

    public record AttendanceGrid(LocalDate month, int daysInMonth, List<RosterRow> employees, List<AttendanceCell> cells,
                                 boolean locked, String lockedReason) {}

    public record MarkEntry(Long employeeId, String status, BigDecimal hours, BigDecimal overtimeHours, String notes) {}

    public record MarkDayRequest(LocalDate date, List<MarkEntry> entries) {}

    public record AdvanceRow(long id, long employeeId, String employeeName, LocalDate txnDate, BigDecimal amount,
                             BigDecimal recoverPerMonth, String notes, String voidState, String voidReason) {}

    public record AdvanceDto(long id, long employeeId, String employeeName, LocalDate txnDate, BigDecimal amount,
                             BigDecimal recoverPerMonth, BigDecimal outstanding, BigDecimal recovered, String notes,
                             String voidState, String voidReason) {}

    public record AdvanceSummary(long employeeId, String employeeName, BigDecimal totalAdvanced, BigDecimal recovered,
                                 BigDecimal outstanding, BigDecimal monthlyInstallment, List<AdvanceDto> advances) {}

    public record AdvanceRequest(Long employeeId, LocalDate txnDate, BigDecimal amount, BigDecimal recoverPerMonth,
                                 String notes) {}

    /** What an employee still owes and the instalment that would normally be recovered next. */
    public record AdvancePosition(BigDecimal outstanding, BigDecimal installment) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;

    public PayrollService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ employees

    private static final String EMP_SELECT = """
            select id, name, designation, mobile, joined_on, left_on, salary_type, base_rate, overtime_multiplier,
                   user_id, upi_or_bank_note, notes, active, version
            from employees
            """;

    public List<EmployeeDto> employees(boolean includeInactive) {
        CurrentUser.require("payroll.view");
        return jdbc.sql(EMP_SELECT + " where business_id = :b and (:inc or active) order by active desc, lower(name)")
                .param("b", props.businessId()).param("inc", includeInactive).query(EmployeeDto.class).list();
    }

    public EmployeeDto employee(long id) {
        CurrentUser.require("payroll.view");
        return employeeRow(id);
    }

    private EmployeeDto employeeRow(long id) {
        return jdbc.sql(EMP_SELECT + " where id = :id and business_id = :b").param("id", id).param("b", props.businessId())
                .query(EmployeeDto.class).optional().orElseThrow(() -> ApiException.notFound("Employee"));
    }

    @Transactional
    public EmployeeDto createEmployee(EmployeeRequest r) {
        CurrentUser.require("payroll.manage");
        validateEmployee(r);
        long id = jdbc.sql("""
                insert into employees (business_id, name, designation, mobile, joined_on, left_on, salary_type, base_rate,
                                       overtime_multiplier, user_id, upi_or_bank_note, notes, active, created_by, updated_by)
                values (:b, :n, :des, :mob, :j, :l, :st, :rate, :otm, :uid, :upi, :notes, :act, :u, :u)
                returning id
                """)
                .param("b", props.businessId()).param("n", r.name().trim()).param("des", blank(r.designation()))
                .param("mob", blank(r.mobile())).param("j", r.joinedOn()).param("l", r.leftOn()).param("st", r.salaryType())
                .param("rate", r.baseRate()).param("otm", r.overtimeMultiplier() == null ? PayrollCalculator.DEFAULT_OT_MULTIPLIER : r.overtimeMultiplier())
                .param("uid", r.userId()).param("upi", blank(r.upiOrBankNote())).param("notes", blank(r.notes()))
                .param("act", r.active() == null || r.active()).param("u", CurrentUser.id()).query(Long.class).single();
        audit.record("EMPLOYEE", id, "CREATE", null, null, empSnapshot(id), null, "MANUAL");
        return employeeRow(id);
    }

    @Transactional
    public EmployeeDto updateEmployee(long id, EmployeeRequest r) {
        CurrentUser.require("payroll.manage");
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason is required to change an employee record");
        EmployeeDto cur = employeeRow(id);
        if (r.version() != null && r.version() != cur.version()) {
            throw ApiException.conflict("This employee record was changed by someone else. Reload and try again.");
        }
        validateEmployee(r);
        String before = empSnapshot(id);
        jdbc.sql("""
                update employees set name = :n, designation = :des, mobile = :mob, joined_on = :j, left_on = :l,
                       salary_type = :st, base_rate = :rate, overtime_multiplier = :otm, user_id = :uid,
                       upi_or_bank_note = :upi, notes = :notes, active = :act, version = version + 1,
                       updated_by = :u, updated_at = now()
                where id = :id and business_id = :b
                """)
                .param("n", r.name().trim()).param("des", blank(r.designation())).param("mob", blank(r.mobile()))
                .param("j", r.joinedOn()).param("l", r.leftOn()).param("st", r.salaryType()).param("rate", r.baseRate())
                .param("otm", r.overtimeMultiplier() == null ? PayrollCalculator.DEFAULT_OT_MULTIPLIER : r.overtimeMultiplier())
                .param("uid", r.userId()).param("upi", blank(r.upiOrBankNote())).param("notes", blank(r.notes()))
                .param("act", r.active() == null ? cur.active() : r.active()).param("u", CurrentUser.id())
                .param("id", id).param("b", props.businessId()).update();
        audit.record("EMPLOYEE", id, "UPDATE", null, before, empSnapshot(id), r.reason(), "MANUAL");
        return employeeRow(id);
    }

    private void validateEmployee(EmployeeRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Employee name is required");
        if (r.salaryType() == null || !SALARY_TYPES.contains(r.salaryType())) {
            throw ApiException.badRequest("Salary type must be MONTHLY, DAILY or HOURLY");
        }
        if (r.baseRate() == null || r.baseRate().signum() < 0) throw ApiException.badRequest("Base rate must be zero or more");
        if (r.overtimeMultiplier() != null && r.overtimeMultiplier().signum() < 0) throw ApiException.badRequest("Overtime multiplier cannot be negative");
        if (r.joinedOn() != null && r.leftOn() != null && r.leftOn().isBefore(r.joinedOn())) {
            throw ApiException.badRequest("Leaving date cannot be before the joining date");
        }
        if (r.userId() != null) {
            long n = jdbc.sql("select count(*) from users where id = :id and business_id = :b")
                    .param("id", r.userId()).param("b", props.businessId()).query(Long.class).single();
            if (n == 0) throw ApiException.badRequest("The linked user does not exist");
        }
    }

    // ------------------------------------------------------------------ roster + attendance

    private void requireAttendanceView() {
        if (!CurrentUser.has("attendance.manage") && !CurrentUser.has("payroll.view")) {
            throw ApiException.forbidden("You do not have permission: attendance.manage");
        }
    }

    private List<RosterRow> roster(LocalDate monthStart) {
        LocalDate monthEnd = YearMonth.from(monthStart).atEndOfMonth();
        return jdbc.sql("""
                select id, name, designation, active, joined_on, left_on from employees
                where business_id = :b and (joined_on is null or joined_on <= :me)
                  and (left_on is null or left_on >= :ms) and (active or left_on is not null)
                order by lower(name)
                """).param("b", props.businessId()).param("ms", monthStart).param("me", monthEnd)
                .query(RosterRow.class).list();
    }

    public List<RosterRow> rosterFor(LocalDate month) {
        requireAttendanceView();
        return roster((month != null ? month : clock.today()).withDayOfMonth(1));
    }

    public AttendanceGrid grid(LocalDate month) {
        requireAttendanceView();
        LocalDate ms = (month != null ? month : clock.today()).withDayOfMonth(1);
        LocalDate me = YearMonth.from(ms).atEndOfMonth();
        List<AttendanceCell> cells = jdbc.sql("""
                select employee_id, att_date, status, hours, overtime_hours, notes from attendance
                where business_id = :b and att_date >= :ms and att_date <= :me order by att_date
                """).param("b", props.businessId()).param("ms", ms).param("me", me).query(AttendanceCell.class).list();
        String lock = lockReason(ms);
        return new AttendanceGrid(ms, YearMonth.from(ms).lengthOfMonth(), roster(ms), cells, lock != null, lock);
    }

    /** A month whose payroll run is approved or paid is closed for attendance changes. */
    private String lockReason(LocalDate monthStart) {
        return jdbc.sql("""
                select status from payroll_runs
                where business_id = :b and month = :m and void_state = 'ACTIVE' and status in ('APPROVED','PAID')
                """).param("b", props.businessId()).param("m", monthStart).query(String.class).optional()
                .map(s -> "Payroll for this month is " + s + ". Reopen or void the payroll run before changing attendance.")
                .orElse(null);
    }

    /** Bulk "mark day": one status per employee for one date. Existing marks are corrected in place (audited). */
    @Transactional
    public List<AttendanceCell> markDay(MarkDayRequest r) {
        CurrentUser.require("attendance.manage");
        if (r.date() == null) throw ApiException.badRequest("Date is required");
        if (r.date().isAfter(clock.today())) throw ApiException.badRequest("Attendance cannot be marked for a future date");
        if (r.entries() == null || r.entries().isEmpty()) throw ApiException.badRequest("Choose at least one employee");
        String lock = lockReason(r.date().withDayOfMonth(1));
        if (lock != null) throw ApiException.conflict(lock);
        for (MarkEntry e : r.entries()) {
            if (e.employeeId() == null) throw ApiException.badRequest("Employee is required");
            if (e.status() == null || !ATT_STATUS.contains(e.status())) {
                throw ApiException.badRequest("Status must be one of " + String.join(", ", new java.util.TreeSet<>(ATT_STATUS)));
            }
            if (e.hours() != null && (e.hours().signum() < 0 || e.hours().compareTo(new BigDecimal("24")) > 0)) {
                throw ApiException.badRequest("Hours must be between 0 and 24");
            }
            if (e.overtimeHours() != null && (e.overtimeHours().signum() < 0 || e.overtimeHours().compareTo(new BigDecimal("24")) > 0)) {
                throw ApiException.badRequest("Overtime hours must be between 0 and 24");
            }
            EmployeeDto emp = employeeRow(e.employeeId());
            if ((emp.joinedOn() != null && r.date().isBefore(emp.joinedOn())) || (emp.leftOn() != null && r.date().isAfter(emp.leftOn()))) {
                throw ApiException.badRequest(emp.name() + " was not employed on " + r.date());
            }
            if (!emp.active() && emp.leftOn() == null) throw ApiException.badRequest(emp.name() + " is inactive");
            BigDecimal ot = e.overtimeHours() == null ? BigDecimal.ZERO : e.overtimeHours();
            String note = blank(e.notes());
            Long existing = jdbc.sql("select id from attendance where employee_id = :e and att_date = :d")
                    .param("e", e.employeeId()).param("d", r.date()).query(Long.class).optional().orElse(null);
            if (existing == null) {
                long id = jdbc.sql("""
                        insert into attendance (business_id, employee_id, att_date, status, hours, overtime_hours, notes, marked_by)
                        values (:b, :e, :d, :s, :h, :ot, :n, :u) returning id
                        """).param("b", props.businessId()).param("e", e.employeeId()).param("d", r.date())
                        .param("s", e.status()).param("h", e.hours()).param("ot", ot).param("n", note)
                        .param("u", CurrentUser.id()).query(Long.class).single();
                audit.record("ATTENDANCE", id, "CREATE", r.date(), null, attSnapshot(id), null, "MANUAL");
            } else {
                AttendanceCell cur = jdbc.sql("""
                        select employee_id, att_date, status, hours, overtime_hours, notes from attendance where id = :id
                        """).param("id", existing).query(AttendanceCell.class).single();
                boolean same = cur.status().equals(e.status()) && sameNumber(cur.hours(), e.hours())
                        && sameNumber(cur.overtimeHours(), ot) && java.util.Objects.equals(cur.notes(), note);
                if (!same) {
                    String before = attSnapshot(existing);
                    jdbc.sql("""
                            update attendance set status = :s, hours = :h, overtime_hours = :ot, notes = :n,
                                   marked_by = :u, marked_at = now() where id = :id
                            """).param("s", e.status()).param("h", e.hours()).param("ot", ot).param("n", note)
                            .param("u", CurrentUser.id()).param("id", existing).update();
                    audit.record("ATTENDANCE", existing, "UPDATE", r.date(), before, attSnapshot(existing),
                            "Attendance corrected", "MANUAL");
                }
            }
        }
        return jdbc.sql("""
                select employee_id, att_date, status, hours, overtime_hours, notes from attendance
                where business_id = :b and att_date = :d
                """).param("b", props.businessId()).param("d", r.date()).query(AttendanceCell.class).list();
    }

    // ------------------------------------------------------------------ advances

    public List<AdvanceSummary> advances(Long employeeId) {
        CurrentUser.require("payroll.view");
        List<AdvanceRow> rows = jdbc.sql("""
                select a.id, a.employee_id, e.name as employee_name, a.txn_date, a.amount, a.recover_per_month, a.notes,
                       a.void_state, a.void_reason
                from employee_advances a join employees e on e.id = a.employee_id
                where a.business_id = :b and (cast(:e as bigint) is null or a.employee_id = :e)
                order by e.name, a.txn_date, a.id
                """).param("b", props.businessId()).param("e", employeeId).query(AdvanceRow.class).list();
        List<AdvanceSummary> out = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            long emp = rows.get(i).employeeId();
            int j = i;
            List<AdvanceRow> group = new ArrayList<>();
            while (j < rows.size() && rows.get(j).employeeId() == emp) group.add(rows.get(j++));
            i = j;
            List<AdvanceRow> active = group.stream().filter(a -> "ACTIVE".equals(a.voidState())).toList();
            BigDecimal recovered = recoveredFor(emp, null);
            List<BigDecimal> outs = PayrollCalculator.allocateFifo(active.stream().map(AdvanceRow::amount).toList(), recovered);
            List<AdvanceDto> dtos = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO.setScale(2), outstanding = BigDecimal.ZERO.setScale(2),
                    installment = BigDecimal.ZERO.setScale(2);
            int k = 0;
            for (AdvanceRow a : group) {
                if ("ACTIVE".equals(a.voidState())) {
                    BigDecimal o = outs.get(k++);
                    total = total.add(a.amount());
                    outstanding = outstanding.add(o);
                    if (o.signum() > 0) installment = installment.add(a.recoverPerMonth().min(o));
                    dtos.add(new AdvanceDto(a.id(), a.employeeId(), a.employeeName(), a.txnDate(), a.amount(),
                            a.recoverPerMonth(), o, a.amount().subtract(o), a.notes(), a.voidState(), a.voidReason()));
                } else {
                    dtos.add(new AdvanceDto(a.id(), a.employeeId(), a.employeeName(), a.txnDate(), a.amount(),
                            a.recoverPerMonth(), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), a.notes(),
                            a.voidState(), a.voidReason()));
                }
            }
            out.add(new AdvanceSummary(emp, group.get(0).employeeName(), total, total.subtract(outstanding), outstanding,
                    installment, dtos));
        }
        return out;
    }

    @Transactional
    public AdvanceSummary createAdvance(AdvanceRequest r) {
        CurrentUser.require("payroll.manage");
        if (r.employeeId() == null) throw ApiException.badRequest("Employee is required");
        EmployeeDto emp = employeeRow(r.employeeId());
        if (!emp.active()) throw ApiException.badRequest(emp.name() + " is inactive");
        if (r.txnDate() == null) throw ApiException.badRequest("Date is required");
        if (r.txnDate().isAfter(clock.today())) throw ApiException.badRequest("Date cannot be in the future");
        if (r.amount() == null || r.amount().signum() <= 0) throw ApiException.badRequest("Advance amount must be more than zero");
        BigDecimal per = r.recoverPerMonth() == null ? BigDecimal.ZERO : r.recoverPerMonth();
        if (per.signum() < 0 || per.compareTo(r.amount()) > 0) throw ApiException.badRequest("Monthly recovery must be between 0 and the advance amount");
        long id = jdbc.sql("""
                insert into employee_advances (business_id, employee_id, txn_date, amount, recover_per_month, notes, created_by)
                values (:b, :e, :d, :a, :r, :n, :u) returning id
                """).param("b", props.businessId()).param("e", r.employeeId()).param("d", r.txnDate())
                .param("a", r.amount()).param("r", per).param("n", blank(r.notes())).param("u", CurrentUser.id())
                .query(Long.class).single();
        audit.record("ADVANCE", id, "CREATE", r.txnDate(), null, advSnapshot(id), null, "MANUAL");
        return advances(r.employeeId()).get(0);
    }

    @Transactional
    public AdvanceSummary voidAdvance(long id, String reason) {
        CurrentUser.require("payroll.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void an advance");
        AdvanceRow row = jdbc.sql("""
                select a.id, a.employee_id, e.name as employee_name, a.txn_date, a.amount, a.recover_per_month, a.notes,
                       a.void_state, a.void_reason
                from employee_advances a join employees e on e.id = a.employee_id
                where a.id = :id and a.business_id = :b
                """).param("id", id).param("b", props.businessId()).query(AdvanceRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Advance"));
        if ("VOIDED".equals(row.voidState())) throw ApiException.conflict("Already voided");
        AdvanceSummary s = advances(row.employeeId()).get(0);
        AdvanceDto me = s.advances().stream().filter(a -> a.id() == id).findFirst().orElseThrow();
        if (me.recovered().signum() > 0) {
            throw ApiException.conflict("Part of this advance has already been recovered through payroll, so it cannot be voided");
        }
        String before = advSnapshot(id);
        jdbc.sql("""
                update employee_advances set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now()
                where id = :id and business_id = :b
                """).param("r", reason).param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("ADVANCE", id, "VOID", row.txnDate(), before, advSnapshot(id), reason, "MANUAL");
        return advances(row.employeeId()).stream().findFirst().orElse(s);
    }

    /** Money already recovered through APPROVED / PAID payroll runs (optionally ignoring one run). */
    BigDecimal recoveredFor(long employeeId, Long excludeRunId) {
        return jdbc.sql("""
                select coalesce(sum(p.advance_recovery), 0) from payslips p join payroll_runs r on r.id = p.run_id
                where p.employee_id = :e and r.void_state = 'ACTIVE' and r.status in ('APPROVED','PAID')
                  and (cast(:x as bigint) is null or r.id <> :x)
                """).param("e", employeeId).param("x", excludeRunId).query(BigDecimal.class).single();
    }

    /** Outstanding advance and the normal instalment for an employee, for advances given up to asOf. */
    AdvancePosition advancePosition(long employeeId, LocalDate asOf, Long excludeRunId) {
        List<AdvanceRow> rows = jdbc.sql("""
                select a.id, a.employee_id, e.name as employee_name, a.txn_date, a.amount, a.recover_per_month, a.notes,
                       a.void_state, a.void_reason
                from employee_advances a join employees e on e.id = a.employee_id
                where a.employee_id = :e and a.void_state = 'ACTIVE' and a.txn_date <= :d
                order by a.txn_date, a.id
                """).param("e", employeeId).param("d", asOf).query(AdvanceRow.class).list();
        BigDecimal recovered = recoveredFor(employeeId, excludeRunId);
        List<BigDecimal> outs = PayrollCalculator.allocateFifo(rows.stream().map(AdvanceRow::amount).toList(), recovered);
        BigDecimal outstanding = BigDecimal.ZERO.setScale(2), installment = BigDecimal.ZERO.setScale(2);
        for (int i = 0; i < rows.size(); i++) {
            BigDecimal o = outs.get(i);
            outstanding = outstanding.add(o);
            if (o.signum() > 0) installment = installment.add(rows.get(i).recoverPerMonth().min(o));
        }
        return new AdvancePosition(outstanding, installment);
    }

    // ------------------------------------------------------------------ snapshots

    private String empSnapshot(long id) {
        return jdbc.sql("select to_jsonb(e)::text from employees e where e.id = :id").param("id", id).query(String.class).single();
    }

    private String attSnapshot(long id) {
        return jdbc.sql("select to_jsonb(a)::text from attendance a where a.id = :id").param("id", id).query(String.class).single();
    }

    private String advSnapshot(long id) {
        return jdbc.sql("select to_jsonb(a)::text from employee_advances a where a.id = :id").param("id", id).query(String.class).single();
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == null && b == null;
        return a.compareTo(b) == 0;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
