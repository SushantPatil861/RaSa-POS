package com.cafepos.payroll;

import com.cafepos.audit.AuditService;
import com.cafepos.expenses.ExpenseService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Payroll runs: DRAFT (editable, with reasons) -> APPROVED (locked) -> PAID (one SYSTEM expense, idempotent).
 * PF / ESI / professional tax are NOT computed: use the free "other deductions" line with a label from your CA.
 */
@Service
public class PayrollRunService {

    private static final Set<String> METHODS = Set.of("CASH", "UPI", "CARD", "BANK", "OTHER");
    private static final String[] MON = {"January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December"};

    public record RunRow(long id, LocalDate month, String status, BigDecimal totalGross, BigDecimal totalDeductions,
                         BigDecimal totalNet, OffsetDateTime approvedAt, String approvedByName, LocalDate paidOn,
                         String paymentMethod, Long expenseId, String notes, String voidState, String voidReason,
                         int version, OffsetDateTime createdAt, long payslipCount) {}

    public record PayslipRaw(long id, long runId, long employeeId, String employeeName, String designation,
                             String salaryType, BigDecimal baseRate, BigDecimal paidDays, BigDecimal hoursWorked,
                             BigDecimal overtimeHours, BigDecimal gross, BigDecimal overtimePay, BigDecimal otherAdditions,
                             String otherAdditionsLabel, BigDecimal advanceRecovery, BigDecimal otherDeductions,
                             String otherDeductionsLabel, BigDecimal netPay, String calcBreakdown, String notes,
                             String editReason, boolean edited) {}

    public record PayslipDto(long id, long runId, long employeeId, String employeeName, String designation,
                             String salaryType, BigDecimal baseRate, BigDecimal paidDays, BigDecimal hoursWorked,
                             BigDecimal overtimeHours, BigDecimal gross, BigDecimal overtimePay, BigDecimal otherAdditions,
                             String otherAdditionsLabel, BigDecimal advanceRecovery, BigDecimal otherDeductions,
                             String otherDeductionsLabel, BigDecimal netPay, JsonNode calcBreakdown, String notes,
                             String editReason, boolean edited) {}

    public record RunDetail(RunRow run, List<PayslipDto> payslips, List<String> notes) {}

    public record BusinessInfo(String name, String address, String phone) {}

    public record PayslipView(BusinessInfo business, LocalDate month, String monthLabel, String runStatus, String paidOn,
                              PayslipDto payslip, List<String> notes) {}

    public record GenerateRequest(LocalDate month) {}

    public record PayslipEditRequest(BigDecimal paidDays, BigDecimal hoursWorked, BigDecimal overtimeHours,
                                     BigDecimal otherAdditions, String otherAdditionsLabel, BigDecimal advanceRecovery,
                                     BigDecimal otherDeductions, String otherDeductionsLabel, String notes, String reason) {}

    public record PayRequest(LocalDate paidOn, String paymentMethod) {}

    public record EmpRow(long id, String name, String designation, String salaryType, BigDecimal baseRate,
                         BigDecimal overtimeMultiplier, LocalDate joinedOn, LocalDate leftOn) {}

    public record AttRow(long employeeId, LocalDate attDate, String status, BigDecimal hours, BigDecimal overtimeHours) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final BusinessClock clock;
    private final AuditService audit;
    private final PayrollService people;
    private final ExpenseService expenses;
    private final ObjectMapper json;

    public PayrollRunService(JdbcClient jdbc, CafeProps props, BusinessClock clock, AuditService audit,
                             PayrollService people, ExpenseService expenses, ObjectMapper json) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
        this.people = people;
        this.expenses = expenses;
        this.json = json;
    }

    private static final String RUN_SELECT = """
            select r.id, r.month, r.status, r.total_gross, r.total_deductions, r.total_net, r.approved_at,
                   au.name as approved_by_name, r.paid_on, r.payment_method, r.expense_id, r.notes, r.void_state,
                   r.void_reason, r.version, r.created_at,
                   (select count(*) from payslips p where p.run_id = r.id) as payslip_count
            from payroll_runs r left join users au on au.id = r.approved_by
            """;

    private static final String SLIP_SELECT = """
            select p.id, p.run_id, p.employee_id, p.employee_name, p.designation, p.salary_type, p.base_rate, p.paid_days,
                   p.hours_worked, p.overtime_hours, p.gross, p.overtime_pay, p.other_additions, p.other_additions_label,
                   p.advance_recovery, p.other_deductions, p.other_deductions_label, p.net_pay,
                   p.calc_breakdown::text as calc_breakdown, p.notes, p.edit_reason, p.edited
            from payslips p
            """;

    // ------------------------------------------------------------------ reads

    public List<RunRow> runs(boolean includeVoided) {
        CurrentUser.require("payroll.view");
        return jdbc.sql(RUN_SELECT + " where r.business_id = :b and (:inc or r.void_state = 'ACTIVE') order by r.month desc, r.id desc limit 120")
                .param("b", props.businessId()).param("inc", includeVoided).query(RunRow.class).list();
    }

    public RunDetail run(long id) {
        CurrentUser.require("payroll.view");
        return detail(id);
    }

    private RunDetail detail(long id) {
        RunRow run = runRow(id);
        List<PayslipDto> slips = jdbc.sql(SLIP_SELECT + " where p.run_id = :r order by lower(p.employee_name), p.id")
                .param("r", id).query(PayslipRaw.class).list().stream().map(this::toDto).toList();
        List<String> notes = new ArrayList<>();
        notes.add("PF, ESI and professional tax are NOT calculated here. Enter any such amount as 'other deductions' with a label, as advised by your CA.");
        notes.add("Unmarked attendance days are treated as unpaid and flagged on each payslip. Attendance is never guessed.");
        if ("DRAFT".equals(run.status())) notes.add("DRAFT: payslip lines can still be edited (a reason is required). Approving locks them.");
        return new RunDetail(run, slips, notes);
    }

    private RunRow runRow(long id) {
        return jdbc.sql(RUN_SELECT + " where r.id = :id and r.business_id = :b").param("id", id).param("b", props.businessId())
                .query(RunRow.class).optional().orElseThrow(() -> ApiException.notFound("Payroll run"));
    }

    public PayslipView payslipView(long payslipId) {
        CurrentUser.require("payroll.view");
        PayslipRaw raw = slipRow(payslipId);
        RunRow run = runRow(raw.runId());
        BusinessInfo biz = jdbc.sql("select name, address, phone from business where id = :b").param("b", props.businessId())
                .query(BusinessInfo.class).single();
        List<String> notes = new ArrayList<>();
        notes.add("Computer-generated payslip from the café's records. PF, ESI and professional tax are not calculated by this system.");
        if (!"PAID".equals(run.status())) notes.add("This payroll run is " + run.status() + " - not yet paid.");
        if ("VOIDED".equals(run.voidState())) notes.add("THIS PAYROLL RUN WAS VOIDED: " + run.voidReason());
        return new PayslipView(biz, run.month(), monthLabel(run.month()), run.status(),
                run.paidOn() == null ? null : run.paidOn().toString(), toDto(raw), notes);
    }

    // ------------------------------------------------------------------ generate / regenerate

    @Transactional
    public RunDetail generate(LocalDate month) {
        CurrentUser.require("payroll.manage");
        if (month == null) throw ApiException.badRequest("Month is required");
        LocalDate ms = month.withDayOfMonth(1);
        if (ms.isAfter(clock.today().withDayOfMonth(1))) throw ApiException.badRequest("A payroll run cannot be created for a future month");
        long id;
        try {
            id = jdbc.sql("insert into payroll_runs (business_id, month, created_by) values (:b, :m, :u) returning id")
                    .param("b", props.businessId()).param("m", ms).param("u", CurrentUser.id()).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A payroll run already exists for " + monthLabel(ms) + ". Open it, or void it first.");
        }
        int n = buildPayslips(id, ms);
        if (n == 0) {
            throw ApiException.badRequest("No employees are active in " + monthLabel(ms) + ". Add employees first.");
        }
        recalcTotals(id);
        audit.record("PAYROLL_RUN", id, "CREATE", ms, null, runSnapshot(id), null, "MANUAL");
        return detail(id);
    }

    /** Throws away DRAFT payslips (kept in the audit trail) and rebuilds them from current attendance. */
    @Transactional
    public RunDetail regenerate(long id, String reason) {
        CurrentUser.require("payroll.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to rebuild the payslips");
        RunRow run = lockRun(id);
        requireDraft(run);
        String before = runSnapshotWithSlips(id);
        jdbc.sql("delete from payslips where run_id = :r").param("r", id).update();
        int n = buildPayslips(id, run.month());
        if (n == 0) throw ApiException.badRequest("No employees are active in " + monthLabel(run.month()));
        recalcTotals(id);
        audit.record("PAYROLL_RUN", id, "REGENERATE", run.month(), before, runSnapshotWithSlips(id), reason, "MANUAL");
        return detail(id);
    }

    private int buildPayslips(long runId, LocalDate ms) {
        LocalDate me = YearMonth.from(ms).atEndOfMonth();
        int days = YearMonth.from(ms).lengthOfMonth();
        List<EmpRow> emps = jdbc.sql("""
                select id, name, designation, salary_type, base_rate, overtime_multiplier, joined_on, left_on from employees
                where business_id = :b and (joined_on is null or joined_on <= :me) and (left_on is null or left_on >= :ms)
                  and (active or left_on is not null)
                order by lower(name)
                """).param("b", props.businessId()).param("ms", ms).param("me", me).query(EmpRow.class).list();
        List<AttRow> att = jdbc.sql("""
                select employee_id, att_date, status, hours, overtime_hours from attendance
                where business_id = :b and att_date >= :ms and att_date <= :me
                """).param("b", props.businessId()).param("ms", ms).param("me", me).query(AttRow.class).list();
        Map<Long, List<PayrollCalculator.AttendanceDay>> byEmp = new HashMap<>();
        for (AttRow a : att) {
            byEmp.computeIfAbsent(a.employeeId(), k -> new ArrayList<>())
                    .add(new PayrollCalculator.AttendanceDay(a.attDate(), a.status(), a.hours(), a.overtimeHours()));
        }
        for (EmpRow e : emps) {
            LocalDate from = e.joinedOn() != null && e.joinedOn().isAfter(ms) ? e.joinedOn() : ms;
            LocalDate to = e.leftOn() != null && e.leftOn().isBefore(me) ? e.leftOn() : me;
            PayrollCalculator.Summary sum = PayrollCalculator.summarise(byEmp.getOrDefault(e.id(), List.of()), from, to);
            PayrollService.AdvancePosition pos = people.advancePosition(e.id(), me, null);
            PayrollCalculator.Result res = PayrollCalculator.calculate(new PayrollCalculator.Input(
                    e.salaryType(), e.baseRate(), days, sum.paidDays(), sum.hoursWorked(), sum.overtimeHours(),
                    e.overtimeMultiplier(), BigDecimal.ZERO, BigDecimal.ZERO, pos.outstanding(), pos.installment()));

            List<String> warnings = new ArrayList<>();
            if (sum.markedDays() == 0) {
                warnings.add("No attendance is marked for this month, so paid days are 0. Mark attendance and rebuild the payslips.");
            } else if (sum.unmarkedDays() > 0) {
                warnings.add(sum.unmarkedDays() + " day(s) have no attendance mark and are NOT paid.");
            }
            if ("HOURLY".equals(e.salaryType())) {
                if (sum.hoursWorked() == null) warnings.add("No hours recorded, so hourly pay is 0.");
                else if (sum.presentDaysWithoutHours() > 0) warnings.add(sum.presentDaysWithoutHours() + " present day(s) have no hours and are not paid.");
            }
            if (!from.equals(ms) || !to.equals(me)) warnings.add("Employed only part of the month (" + from + " to " + to + ").");
            if (res.deductionsExceededEarnings()) warnings.add("Other deductions exceed earnings; net pay is held at 0.");
            if (pos.outstanding().signum() > 0 && pos.installment().signum() == 0) {
                warnings.add("Advance of " + pos.outstanding() + " is outstanding but no monthly recovery is set; nothing recovered.");
            }
            if ("MONTHLY".equals(e.salaryType()) && sum.paidDays().compareTo(BigDecimal.valueOf(days)) > 0) {
                warnings.add("Paid days exceed the days in the month; capped.");
            }

            Map<String, Object> bd = new LinkedHashMap<>();
            bd.put("calc", res.breakdown());
            Map<String, Object> attMap = new LinkedHashMap<>();
            attMap.put("paidDays", sum.paidDays());
            attMap.put("markedDays", sum.markedDays());
            attMap.put("unmarkedDays", sum.unmarkedDays());
            attMap.put("hoursWorked", sum.hoursWorked());
            attMap.put("overtimeHours", sum.overtimeHours());
            attMap.put("daysInMonth", days);
            bd.put("attendance", attMap);
            Map<String, Object> adv = new LinkedHashMap<>();
            adv.put("outstandingBefore", pos.outstanding());
            adv.put("installmentSuggested", pos.installment());
            bd.put("advance", adv);
            bd.put("warnings", warnings);
            bd.put("edits", new ArrayList<>());

            jdbc.sql("""
                    insert into payslips (run_id, employee_id, employee_name, designation, salary_type, base_rate, paid_days,
                                          hours_worked, overtime_hours, gross, overtime_pay, other_additions, advance_recovery,
                                          other_deductions, net_pay, calc_breakdown)
                    values (:r, :e, :n, :des, :st, :rate, :pd, :h, :oh, :g, :ot, 0, :ar, 0, :net, cast(:bd as jsonb))
                    """)
                    .param("r", runId).param("e", e.id()).param("n", e.name()).param("des", e.designation())
                    .param("st", e.salaryType()).param("rate", e.baseRate()).param("pd", sum.paidDays())
                    .param("h", sum.hoursWorked()).param("oh", sum.overtimeHours()).param("g", res.gross())
                    .param("ot", res.overtimePay()).param("ar", res.advanceRecovery()).param("net", res.net())
                    .param("bd", toJson(bd)).update();
        }
        return emps.size();
    }

    // ------------------------------------------------------------------ edit a DRAFT payslip line

    @Transactional
    public RunDetail editPayslip(long payslipId, PayslipEditRequest r) {
        CurrentUser.require("payroll.manage");
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason is required to change a payslip");
        PayslipRaw cur = slipRow(payslipId);
        RunRow run = lockRun(cur.runId());
        requireDraft(run);
        BigDecimal paidDays = r.paidDays() != null ? r.paidDays() : cur.paidDays();
        BigDecimal hours = r.hoursWorked() != null ? r.hoursWorked() : cur.hoursWorked();
        BigDecimal otHours = r.overtimeHours() != null ? r.overtimeHours() : cur.overtimeHours();
        BigDecimal adds = r.otherAdditions() != null ? r.otherAdditions() : cur.otherAdditions();
        BigDecimal deds = r.otherDeductions() != null ? r.otherDeductions() : cur.otherDeductions();
        BigDecimal requested = r.advanceRecovery() != null ? r.advanceRecovery() : cur.advanceRecovery();
        if (paidDays.signum() < 0 || otHours.signum() < 0 || adds.signum() < 0 || deds.signum() < 0 || requested.signum() < 0
                || (hours != null && hours.signum() < 0)) {
            throw ApiException.badRequest("Values cannot be negative");
        }
        int days = YearMonth.from(run.month()).lengthOfMonth();
        if (paidDays.compareTo(BigDecimal.valueOf(days)) > 0) throw ApiException.badRequest("Paid days cannot be more than the days in the month");
        BigDecimal otm = jdbc.sql("select overtime_multiplier from employees where id = :id").param("id", cur.employeeId())
                .query(BigDecimal.class).single();
        PayrollService.AdvancePosition pos = people.advancePosition(cur.employeeId(), YearMonth.from(run.month()).atEndOfMonth(), run.id());
        PayrollCalculator.Result res = PayrollCalculator.calculate(new PayrollCalculator.Input(
                cur.salaryType(), cur.baseRate(), days, paidDays, hours, otHours, otm, adds, deds, pos.outstanding(), requested));

        ObjectNode root = (ObjectNode) tree(cur.calcBreakdown());
        root.set("calc", json.valueToTree(res.breakdown()));
        JsonNode existing = root.get("edits");
        ArrayNode edits = existing instanceof ArrayNode a ? a : root.putArray("edits");
        ObjectNode edit = edits.addObject();
        edit.put("by", CurrentUser.get().name());
        edit.put("reason", r.reason().trim());
        edit.put("netBefore", cur.netPay().toPlainString());
        edit.put("netAfter", res.net().toPlainString());

        String before = slipSnapshot(payslipId);
        jdbc.sql("""
                update payslips set paid_days = :pd, hours_worked = :h, overtime_hours = :oh, gross = :g, overtime_pay = :ot,
                       other_additions = :add, other_additions_label = :addl, advance_recovery = :ar, other_deductions = :ded,
                       other_deductions_label = :dedl, net_pay = :net, calc_breakdown = cast(:bd as jsonb), notes = :n,
                       edit_reason = :reason, edited = true
                where id = :id
                """)
                .param("pd", paidDays).param("h", hours).param("oh", otHours).param("g", res.gross()).param("ot", res.overtimePay())
                .param("add", res.otherAdditions()).param("addl", r.otherAdditionsLabel() != null ? blank(r.otherAdditionsLabel()) : cur.otherAdditionsLabel())
                .param("ar", res.advanceRecovery()).param("ded", res.otherDeductions())
                .param("dedl", r.otherDeductionsLabel() != null ? blank(r.otherDeductionsLabel()) : cur.otherDeductionsLabel())
                .param("net", res.net()).param("bd", toJson(root)).param("n", r.notes() != null ? blank(r.notes()) : cur.notes())
                .param("reason", r.reason().trim()).param("id", payslipId).update();
        recalcTotals(run.id());
        audit.record("PAYSLIP", payslipId, "UPDATE", run.month(), before, slipSnapshot(payslipId), r.reason(), "MANUAL");
        return detail(run.id());
    }

    // ------------------------------------------------------------------ approve / reopen / pay / void

    @Transactional
    public RunDetail approve(long id) {
        CurrentUser.require("payroll.manage");
        RunRow run = lockRun(id);
        requireActive(run);
        if (!"DRAFT".equals(run.status())) throw ApiException.conflict("Only a DRAFT payroll run can be approved");
        List<PayslipRaw> slips = jdbc.sql(SLIP_SELECT + " where p.run_id = :r").param("r", id).query(PayslipRaw.class).list();
        if (slips.isEmpty()) throw ApiException.conflict("This run has no payslips");
        LocalDate me = YearMonth.from(run.month()).atEndOfMonth();
        for (PayslipRaw s : slips) {
            if (s.advanceRecovery().signum() > 0) {
                PayrollService.AdvancePosition pos = people.advancePosition(s.employeeId(), me, id);
                if (s.advanceRecovery().compareTo(pos.outstanding()) > 0) {
                    throw ApiException.conflict("Advance recovery for " + s.employeeName() + " (" + s.advanceRecovery()
                            + ") is more than what is outstanding (" + pos.outstanding() + "). Rebuild the payslips.");
                }
            }
        }
        String before = runSnapshot(id);
        jdbc.sql("""
                update payroll_runs set status = 'APPROVED', approved_by = :u, approved_at = now(), version = version + 1,
                       updated_at = now() where id = :id and business_id = :b
                """).param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("PAYROLL_RUN", id, "APPROVE", run.month(), before, runSnapshot(id), null, "MANUAL");
        return detail(id);
    }

    /** Back to DRAFT so a mistake can be fixed. Not allowed once paid. */
    @Transactional
    public RunDetail reopen(long id, String reason) {
        CurrentUser.require("payroll.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to reopen a payroll run");
        RunRow run = lockRun(id);
        requireActive(run);
        if (!"APPROVED".equals(run.status())) throw ApiException.conflict("Only an APPROVED (not yet paid) run can be reopened");
        String before = runSnapshot(id);
        jdbc.sql("""
                update payroll_runs set status = 'DRAFT', approved_by = null, approved_at = null, version = version + 1,
                       updated_at = now() where id = :id and business_id = :b
                """).param("id", id).param("b", props.businessId()).update();
        audit.record("PAYROLL_RUN", id, "REOPEN", run.month(), before, runSnapshot(id), reason, "MANUAL");
        return detail(id);
    }

    @Transactional
    public RunDetail pay(long id, PayRequest r) {
        CurrentUser.require("payroll.manage");
        RunRow run = lockRun(id);
        requireActive(run);
        if (run.expenseId() != null && "PAID".equals(run.status())) return detail(id);   // idempotent
        if (!"APPROVED".equals(run.status())) throw ApiException.conflict("Approve the payroll run before marking it paid");
        if (r.paidOn() == null) throw ApiException.badRequest("Enter the date the salaries were paid");
        if (r.paidOn().isAfter(clock.today())) throw ApiException.badRequest("Payment date cannot be in the future");
        if (r.paidOn().isBefore(run.month())) throw ApiException.badRequest("Payment date cannot be before the salary month starts");
        String pm = r.paymentMethod() == null || r.paymentMethod().isBlank() ? null : r.paymentMethod();
        if (pm != null && !METHODS.contains(pm)) throw ApiException.badRequest("Unknown payment method: " + pm);
        String before = runSnapshot(id);
        Long categoryId = jdbc.sql("""
                select id from expense_categories where business_id = :b and lower(name) = 'staff salary' and active
                order by id limit 1
                """).param("b", props.businessId()).query(Long.class).optional().orElse(null);
        ExpenseService.ExpenseDto expense = expenses.create(new ExpenseService.ExpenseRequest(
                r.paidOn(), categoryId, run.totalNet(), pm, "Staff salary " + monthLabel(run.month()), false,
                "Payroll run #" + id + " for " + monthLabel(run.month()) + " (" + run.payslipCount() + " payslips)",
                "SYSTEM", false, false, "CONFIRMED", null, null));
        jdbc.sql("""
                update payroll_runs set status = 'PAID', paid_on = :d, paid_by = :u, payment_method = :pm, expense_id = :e,
                       version = version + 1, updated_at = now() where id = :id and business_id = :b
                """).param("d", r.paidOn()).param("u", CurrentUser.id()).param("pm", pm).param("e", expense.id())
                .param("id", id).param("b", props.businessId()).update();
        audit.record("PAYROLL_RUN", id, "PAY", r.paidOn(), before, runSnapshot(id), null, "MANUAL");
        return detail(id);
    }

    @Transactional
    public RunDetail voidRun(long id, String reason) {
        CurrentUser.require("payroll.manage");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void a payroll run");
        RunRow run = lockRun(id);
        if ("VOIDED".equals(run.voidState())) throw ApiException.conflict("Already voided");
        String before = runSnapshot(id);
        if (run.expenseId() != null) {
            String st = jdbc.sql("select void_state from expenses where id = :id").param("id", run.expenseId())
                    .query(String.class).optional().orElse("VOIDED");
            if ("ACTIVE".equals(st)) expenses.voidExpense(run.expenseId(), "Payroll run #" + id + " voided: " + reason.trim());
        }
        jdbc.sql("""
                update payroll_runs set void_state = 'VOIDED', void_reason = :r, voided_by = :u, voided_at = now(),
                       version = version + 1, updated_at = now() where id = :id and business_id = :b
                """).param("r", reason.trim()).param("u", CurrentUser.id()).param("id", id).param("b", props.businessId()).update();
        audit.record("PAYROLL_RUN", id, "VOID", run.month(), before, runSnapshot(id), reason, "MANUAL");
        return detail(id);
    }

    // ------------------------------------------------------------------ internals

    private RunRow lockRun(long id) {
        jdbc.sql("select id from payroll_runs where id = :id and business_id = :b for update").param("id", id)
                .param("b", props.businessId()).query(Long.class).optional().orElseThrow(() -> ApiException.notFound("Payroll run"));
        return runRow(id);
    }

    private static void requireDraft(RunRow run) {
        requireActive(run);
        if (!"DRAFT".equals(run.status())) throw ApiException.conflict("This payroll run is " + run.status() + " and locked. Reopen it first.");
    }

    private static void requireActive(RunRow run) {
        if ("VOIDED".equals(run.voidState())) throw ApiException.conflict("This payroll run is voided");
    }

    private void recalcTotals(long runId) {
        jdbc.sql("""
                update payroll_runs set
                  total_gross = coalesce((select sum(gross + overtime_pay + other_additions) from payslips where run_id = :r), 0),
                  total_net = coalesce((select sum(net_pay) from payslips where run_id = :r), 0),
                  total_deductions = coalesce((select sum(gross + overtime_pay + other_additions - net_pay) from payslips where run_id = :r), 0),
                  updated_at = now()
                where id = :r
                """).param("r", runId).update();
    }

    private PayslipRaw slipRow(long id) {
        return jdbc.sql(SLIP_SELECT + " join payroll_runs r on r.id = p.run_id where p.id = :id and r.business_id = :b")
                .param("id", id).param("b", props.businessId()).query(PayslipRaw.class).optional()
                .orElseThrow(() -> ApiException.notFound("Payslip"));
    }

    private PayslipDto toDto(PayslipRaw p) {
        return new PayslipDto(p.id(), p.runId(), p.employeeId(), p.employeeName(), p.designation(), p.salaryType(),
                p.baseRate(), p.paidDays(), p.hoursWorked(), p.overtimeHours(), p.gross(), p.overtimePay(),
                p.otherAdditions(), p.otherAdditionsLabel(), p.advanceRecovery(), p.otherDeductions(),
                p.otherDeductionsLabel(), p.netPay(), tree(p.calcBreakdown()), p.notes(), p.editReason(), p.edited());
    }

    private String runSnapshot(long id) {
        return jdbc.sql("select to_jsonb(r)::text from payroll_runs r where r.id = :id").param("id", id).query(String.class).single();
    }

    private String runSnapshotWithSlips(long id) {
        return jdbc.sql("""
                select (to_jsonb(r) || jsonb_build_object('payslips',
                         coalesce((select jsonb_agg(to_jsonb(p)) from payslips p where p.run_id = r.id), '[]'::jsonb)))::text
                from payroll_runs r where r.id = :id
                """).param("id", id).query(String.class).single();
    }

    private String slipSnapshot(long id) {
        return jdbc.sql("select to_jsonb(p)::text from payslips p where p.id = :id").param("id", id).query(String.class).single();
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise payslip breakdown", e);
        }
    }

    private JsonNode tree(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored payslip JSON is invalid", e);
        }
    }

    static String monthLabel(LocalDate d) {
        return MON[d.getMonthValue() - 1] + " " + d.getYear();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
