package com.cafepos.hygiene;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/hygiene")
public class HygieneController {

    public record ReasonRequest(String reason) {}

    private final HygieneService hygiene;

    public HygieneController(HygieneService hygiene) {
        this.hygiene = hygiene;
    }

    @GetMapping("/checklists")
    @PreAuthorize("hasAuthority('hygiene.view')")
    public List<HygieneService.ChecklistDto> checklists(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return hygiene.checklists(includeInactive);
    }

    @PostMapping("/checklists")
    @PreAuthorize("hasAuthority('hygiene.manage')")
    public HygieneService.ChecklistDto createChecklist(@RequestBody HygieneService.ChecklistRequest r) {
        return hygiene.createChecklist(r);
    }

    @PutMapping("/checklists/{id}")
    @PreAuthorize("hasAuthority('hygiene.manage')")
    public HygieneService.ChecklistDto updateChecklist(@PathVariable long id, @RequestBody HygieneService.ChecklistRequest r) {
        return hygiene.updateChecklist(id, r);
    }

    @GetMapping("/today")
    @PreAuthorize("hasAuthority('hygiene.view')")
    public List<HygieneService.TodayRow> today(@RequestParam(required = false) LocalDate date) {
        return hygiene.today(date);
    }

    @GetMapping("/logs")
    @PreAuthorize("hasAuthority('hygiene.view')")
    public List<HygieneService.LogDto> logs(@RequestParam(required = false) LocalDate from,
                                            @RequestParam(required = false) LocalDate to,
                                            @RequestParam(required = false) Long checklistId,
                                            @RequestParam(defaultValue = "false") boolean includeVoided) {
        return hygiene.logs(from, to, checklistId, includeVoided);
    }

    @PostMapping("/logs")
    @PreAuthorize("hasAuthority('hygiene.log')")
    public HygieneService.LogDto log(@RequestBody HygieneService.LogRequest r) {
        return hygiene.logChecklist(r);
    }

    @PostMapping("/logs/{id}/sign-off")
    @PreAuthorize("hasAuthority('hygiene.manage')")
    public HygieneService.LogDto signOff(@PathVariable long id) {
        return hygiene.signOff(id);
    }

    @PostMapping("/logs/{id}/void")
    @PreAuthorize("hasAuthority('hygiene.manage')")
    public HygieneService.LogDto voidLog(@PathVariable long id, @RequestBody ReasonRequest r) {
        return hygiene.voidLog(id, r.reason());
    }

    @GetMapping("/temperature-points")
    @PreAuthorize("hasAuthority('hygiene.view')")
    public List<HygieneService.PointRow> points(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return hygiene.points(includeInactive);
    }

    @PostMapping("/temperature-points")
    @PreAuthorize("hasAuthority('hygiene.manage')")
    public HygieneService.PointRow createPoint(@RequestBody HygieneService.PointRequest r) {
        return hygiene.createPoint(r);
    }

    @PutMapping("/temperature-points/{id}")
    @PreAuthorize("hasAuthority('hygiene.manage')")
    public HygieneService.PointRow updatePoint(@PathVariable long id, @RequestBody HygieneService.PointRequest r) {
        return hygiene.updatePoint(id, r);
    }

    @GetMapping("/temperature-logs")
    @PreAuthorize("hasAuthority('hygiene.view')")
    public List<HygieneService.TempLogRow> temperatureLogs(@RequestParam(required = false) LocalDate from,
                                                           @RequestParam(required = false) LocalDate to,
                                                           @RequestParam(required = false) Long pointId,
                                                           @RequestParam(defaultValue = "false") boolean outOfRangeOnly,
                                                           @RequestParam(defaultValue = "false") boolean includeVoided) {
        return hygiene.temperatureLogs(from, to, pointId, outOfRangeOnly, includeVoided);
    }

    @PostMapping("/temperature-logs")
    @PreAuthorize("hasAuthority('hygiene.log')")
    public HygieneService.TempLogRow logTemperature(@RequestBody HygieneService.TempLogRequest r) {
        return hygiene.logTemperature(r);
    }

    @PostMapping("/temperature-logs/{id}/void")
    @PreAuthorize("hasAuthority('hygiene.manage')")
    public HygieneService.TempLogRow voidTemperature(@PathVariable long id, @RequestBody ReasonRequest r) {
        return hygiene.voidTemperature(id, r.reason());
    }

    @GetMapping("/reports/missed")
    @PreAuthorize("hasAuthority('hygiene.view')")
    public HygieneService.MissedReport missed(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return hygiene.missedReport(from, to);
    }
}
