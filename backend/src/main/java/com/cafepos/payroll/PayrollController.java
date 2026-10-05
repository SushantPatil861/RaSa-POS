package com.cafepos.payroll;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/hr")
public class PayrollController {

    public record ReasonRequest(String reason) {}

    private final PayrollService people;
    private final PayrollRunService runs;

    public PayrollController(PayrollService people, PayrollRunService runs) {
        this.people = people;
        this.runs = runs;
    }

    // ---- employees (salary figures: payroll.view)

    @GetMapping("/employees")
    @PreAuthorize("hasAuthority('payroll.view')")
    public List<PayrollService.EmployeeDto> employees(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return people.employees(includeInactive);
    }

    @GetMapping("/employees/{id}")
    @PreAuthorize("hasAuthority('payroll.view')")
    public PayrollService.EmployeeDto employee(@PathVariable long id) {
        return people.employee(id);
    }

    @PostMapping("/employees")
    @PreAuthorize("hasAuthority('payroll.manage')")
    public PayrollService.EmployeeDto createEmployee(@RequestBody PayrollService.EmployeeRequest r) {
        return people.createEmployee(r);
    }

    @PutMapping("/employees/{id}")
    @PreAuthorize("hasAuthority('payroll.manage')")
    public PayrollService.EmployeeDto updateEmployee(@PathVariable long id, @RequestBody PayrollService.EmployeeRequest r) {
        return people.updateEmployee(id, r);
    }

    // ---- roster + attendance (names only for attendance clerks)

    @GetMapping("/roster")
    @PreAuthorize("hasAnyAuthority('attendance.manage','payroll.view')")
    public List<PayrollService.RosterRow> roster(@RequestParam(required = false) LocalDate month) {
        return people.rosterFor(month);
    }

    @GetMapping("/attendance")
    @PreAuthorize("hasAnyAuthority('attendance.manage','payroll.view')")
    public PayrollService.AttendanceGrid attendance(@RequestParam(required = false) LocalDate month) {
        return people.grid(month);
    }

    @PostMapping("/attendance/mark-day")
    @PreAuthorize("hasAuthority('attendance.manage')")
    public List<PayrollService.AttendanceCell> markDay(@RequestBody PayrollService.MarkDayRequest r) {
        return people.markDay(r);
    }

    // ---- advances

    @GetMapping("/advances")
    @PreAuthorize("hasAuthority('payroll.view')")
    public List<PayrollService.AdvanceSummary> advances(@RequestParam(required = false) Long employeeId) {
        return people.advances(employeeId);
    }

    @PostMapping("/advances")
    @PreAuthorize("hasAuthority('payroll.manage')")
    public PayrollService.AdvanceSummary createAdvance(@RequestBody PayrollService.AdvanceRequest r) {
        return people.createAdvance(r);
    }

    @PostMapping("/advances/{id}/void")
    @PreAuthorize("hasAuthority('payroll.manage')")
    public PayrollService.AdvanceSummary voidAdvance(@PathVariable long id, @RequestBody ReasonRequest r) {
        return people.voidAdvance(id, r.reason());
    }

    // ---- payroll runs

    @GetMapping("/payroll-runs")
    @PreAuthorize("hasAuthority('payroll.view')")
    public List<PayrollRunService.RunRow> runs(@RequestParam(defaultValue = "false") boolean includeVoided) {
        return runs.runs(includeVoided);
    }

    @GetMapping("/payroll-runs/{id}")
    @PreAuthorize("hasAuthority('payroll.view')")
    public PayrollRunService.RunDetail run(@PathVariable long id) {
        return runs.run(id);
    }

    @PostMapping("/payroll-runs")
    @PreAuthorize("hasAuthority('payroll.manage')")
    public PayrollRunService.RunDetail generate(@RequestBody PayrollRunService.GenerateRequest r) {
        return runs.generate(r.month());
    }

    @PostMapping("/payroll-runs/{id}/regenerate")
    @PreAuthorize("hasAuthority('payroll.manage')")
    public PayrollRunService.RunDetail regenerate(@PathVariable long id, @RequestBody ReasonRequest r) {
        return runs.regenerate(id, r.reason());
    }

    @PostMapping("/payroll-runs/{id}/approve")
    @PreAuthorize("hasAuthority('payroll.manage')")
    public PayrollRunService.RunDetail approve(@PathVariable long id) {
        return runs.approve(id);
    }

    @PostMapping("/payroll-runs/{id}/reopen")
    @PreAuthorize("hasAuthority('payroll.manage')")
    public PayrollRunService.RunDetail reopen(@PathVariable long id, @RequestBody ReasonRequest r) {
        return runs.reopen(id, r.reason());
    }

    @PostMapping("/payroll-runs/{id}/pay")
    @PreAuthorize("hasAuthority('payroll.manage')")
    public PayrollRunService.RunDetail pay(@PathVariable long id, @RequestBody PayrollRunService.PayRequest r) {
        return runs.pay(id, r);
    }

    @PostMapping("/payroll-runs/{id}/void")
    @PreAuthorize("hasAuthority('payroll.manage')")
    public PayrollRunService.RunDetail voidRun(@PathVariable long id, @RequestBody ReasonRequest r) {
        return runs.voidRun(id, r.reason());
    }

    @PutMapping("/payslips/{id}")
    @PreAuthorize("hasAuthority('payroll.manage')")
    public PayrollRunService.RunDetail editPayslip(@PathVariable long id, @RequestBody PayrollRunService.PayslipEditRequest r) {
        return runs.editPayslip(id, r);
    }

    @GetMapping("/payslips/{id}")
    @PreAuthorize("hasAuthority('payroll.view')")
    public PayrollRunService.PayslipView payslip(@PathVariable long id) {
        return runs.payslipView(id);
    }
}
