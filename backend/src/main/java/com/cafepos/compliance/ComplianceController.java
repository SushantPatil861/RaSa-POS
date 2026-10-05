package com.cafepos.compliance;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceController {

    public record ReasonRequest(String reason) {}

    private final ComplianceService compliance;

    public ComplianceController(ComplianceService compliance) {
        this.compliance = compliance;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('compliance.view')")
    public ComplianceService.Dashboard dashboard() {
        return compliance.dashboard();
    }

    /** Generates any missing due-date tasks for the window (idempotent) and returns the calendar. */
    @GetMapping("/calendar")
    @PreAuthorize("hasAuthority('compliance.view')")
    public List<ComplianceService.TaskDto> calendar(@RequestParam(required = false) LocalDate from,
                                                    @RequestParam(required = false) LocalDate to) {
        return compliance.calendar(from, to);
    }

    @PostMapping("/tasks")
    @PreAuthorize("hasAuthority('compliance.manage')")
    public ComplianceService.TaskDto addTask(@RequestBody ComplianceService.TaskRequest r) {
        return compliance.addTask(r);
    }

    @PostMapping("/tasks/{id}/complete")
    @PreAuthorize("hasAuthority('compliance.manage')")
    public ComplianceService.TaskDto complete(@PathVariable long id, @RequestBody ComplianceService.CompleteRequest r) {
        return compliance.complete(id, r);
    }

    @PostMapping("/tasks/{id}/skip")
    @PreAuthorize("hasAuthority('compliance.manage')")
    public ComplianceService.TaskDto skip(@PathVariable long id, @RequestBody ReasonRequest r) {
        return compliance.skip(id, r.reason());
    }

    @PostMapping("/tasks/{id}/reopen")
    @PreAuthorize("hasAuthority('compliance.manage')")
    public ComplianceService.TaskDto reopen(@PathVariable long id, @RequestBody ReasonRequest r) {
        return compliance.reopen(id, r.reason());
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('compliance.view')")
    public List<ComplianceService.RuleDto> rules() {
        return compliance.rules();
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAuthority('compliance.configure')")
    public ComplianceService.RuleDto createRule(@RequestBody ComplianceService.RuleRequest r) {
        return compliance.createRule(r);
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('compliance.configure')")
    public ComplianceService.RuleDto updateRule(@PathVariable long id, @RequestBody ComplianceService.RuleRequest r) {
        return compliance.updateRule(id, r);
    }

    @GetMapping("/documents")
    @PreAuthorize("hasAuthority('compliance.view')")
    public List<ComplianceService.DocDto> documents(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return compliance.documents(includeInactive);
    }

    @PostMapping("/documents")
    @PreAuthorize("hasAuthority('compliance.manage')")
    public ComplianceService.DocDto addDocument(@RequestBody ComplianceService.DocRequest r) {
        return compliance.addDocument(r);
    }

    @PutMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('compliance.manage')")
    public ComplianceService.DocDto updateDocument(@PathVariable long id, @RequestBody ComplianceService.DocRequest r) {
        return compliance.updateDocument(id, r);
    }
}
