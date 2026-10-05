package com.cafepos.tds;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class TdsController {

    public record ReasonRequest(String reason) {}

    private final TdsService tds;

    public TdsController(TdsService tds) {
        this.tds = tds;
    }

    @GetMapping("/tds/sections")
    @PreAuthorize("hasAuthority('tds.view')")
    public List<TdsService.SectionDto> sections(@RequestParam(required = false) LocalDate asOf,
                                                @RequestParam(defaultValue = "false") boolean history) {
        return tds.sections(asOf, history);
    }

    /** Adds a new effective-dated row (owner only); existing rows are never edited. */
    @PostMapping("/tds/sections")
    @PreAuthorize("hasAuthority('tds.configure')")
    public TdsService.SectionDto addSection(@RequestBody TdsService.SectionRequest r) {
        return tds.addSection(r);
    }

    @GetMapping("/tds/entries")
    @PreAuthorize("hasAuthority('tds.view')")
    public List<TdsService.EntryDto> entries(@RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String section,
                                             @RequestParam(defaultValue = "false") boolean includeVoided,
                                             @RequestParam(defaultValue = "200") int limit) {
        return tds.list(from, to, status, section, includeVoided, limit);
    }

    @GetMapping("/tds/entries/{id}")
    @PreAuthorize("hasAuthority('tds.view')")
    public TdsService.EntryDto entry(@PathVariable long id) {
        return tds.get(id);
    }

    @PostMapping("/tds/entries")
    @PreAuthorize("hasAuthority('tds.manage')")
    public TdsService.EntryDto create(@RequestBody TdsService.EntryRequest r) {
        return tds.create(r);
    }

    @PutMapping("/tds/entries/{id}")
    @PreAuthorize("hasAuthority('tds.manage')")
    public TdsService.EntryDto update(@PathVariable long id, @RequestBody TdsService.EntryRequest r) {
        return tds.update(id, r);
    }

    @PostMapping("/tds/entries/{id}/deposit")
    @PreAuthorize("hasAuthority('tds.manage')")
    public TdsService.EntryDto deposit(@PathVariable long id, @RequestBody TdsService.DepositRequest r) {
        return tds.markDeposited(id, r);
    }

    @PostMapping("/tds/entries/{id}/unmark-deposit")
    @PreAuthorize("hasAuthority('tds.manage')")
    public TdsService.EntryDto unmarkDeposit(@PathVariable long id, @RequestBody ReasonRequest r) {
        return tds.unmarkDeposit(id, r.reason());
    }

    @PostMapping("/tds/entries/{id}/void")
    @PreAuthorize("hasAuthority('tds.manage')")
    public TdsService.EntryDto voidEntry(@PathVariable long id, @RequestBody ReasonRequest r) {
        return tds.voidEntry(id, r.reason());
    }

    @GetMapping("/tds/thresholds")
    @PreAuthorize("hasAuthority('tds.view')")
    public TdsService.ThresholdDto thresholds(@RequestParam(required = false) Integer fy) {
        return tds.thresholds(fy);
    }

    @GetMapping("/tds/pending")
    @PreAuthorize("hasAuthority('tds.view')")
    public TdsService.PendingDto pending() {
        return tds.pending();
    }

    @GetMapping("/tds/tcs")
    @PreAuthorize("hasAuthority('tds.view')")
    public TdsService.TcsSummary tcs(@RequestParam(required = false) LocalDate from,
                                     @RequestParam(required = false) LocalDate to,
                                     @RequestParam(defaultValue = "false") boolean includeVoided) {
        return tds.tcs(from, to, includeVoided);
    }

    @PostMapping("/tds/tcs")
    @PreAuthorize("hasAuthority('tds.manage')")
    public TdsService.TcsRow addTcs(@RequestBody TdsService.TcsRequest r) {
        return tds.addTcs(r);
    }

    @PostMapping("/tds/tcs/{id}/void")
    @PreAuthorize("hasAuthority('tds.manage')")
    public TdsService.TcsRow voidTcs(@PathVariable long id, @RequestBody ReasonRequest r) {
        return tds.voidTcs(id, r.reason());
    }
}
