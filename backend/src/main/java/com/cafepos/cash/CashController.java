package com.cafepos.cash;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CashController {

    public record ReasonBody(String reason) {}

    private final CashService cash;

    public CashController(CashService cash) {
        this.cash = cash;
    }

    @GetMapping("/cash-days")
    @PreAuthorize("hasAuthority('cash.view')")
    public List<CashService.DaySummary> days(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return cash.days(from, to);
    }

    @GetMapping("/cash-days/{date}")
    @PreAuthorize("hasAuthority('cash.view')")
    public CashService.CashDayDto day(@PathVariable LocalDate date) {
        return cash.day(date);
    }

    @PutMapping("/cash-days/{date}")
    @PreAuthorize("hasAuthority('cash.close')")
    public CashService.CashDayDto save(@PathVariable LocalDate date, @RequestBody CashService.DayRequest r) {
        return cash.save(date, r);
    }

    @PostMapping("/cash-days/{date}/explanations")
    @PreAuthorize("hasAuthority('cash.close')")
    public CashService.CashDayDto addExplanation(@PathVariable LocalDate date, @RequestBody CashService.ExplanationRequest r) {
        return cash.addExplanation(date, r);
    }

    @DeleteMapping("/cash-days/{date}/explanations/{id}")
    @PreAuthorize("hasAuthority('cash.close')")
    public CashService.CashDayDto removeExplanation(@PathVariable LocalDate date, @PathVariable long id) {
        return cash.removeExplanation(date, id);
    }

    @PostMapping("/cash-days/{date}/close")
    @PreAuthorize("hasAuthority('cash.close')")
    public CashService.CashDayDto close(@PathVariable LocalDate date) {
        return cash.close(date);
    }

    @PostMapping("/cash-days/{date}/reopen")
    @PreAuthorize("hasAuthority('cash.close')")
    public CashService.CashDayDto reopen(@PathVariable LocalDate date, @RequestBody ReasonBody b) {
        return cash.reopen(date, b.reason());
    }

    // ---- payment settlements

    @GetMapping("/payment-settlements")
    @PreAuthorize("hasAuthority('cash.view')")
    public List<CashService.SettlementRow> settlements(@RequestParam(required = false) LocalDate from,
                                                       @RequestParam(required = false) LocalDate to,
                                                       @RequestParam(required = false) String method,
                                                       @RequestParam(defaultValue = "false") boolean includeVoided) {
        return cash.settlements(from, to, method, includeVoided);
    }

    @GetMapping("/payment-settlements/summary")
    @PreAuthorize("hasAuthority('cash.view')")
    public CashService.SettlementSummary summary(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return cash.settlementSummary(from, to);
    }

    @PostMapping("/payment-settlements")
    @PreAuthorize("hasAuthority('cash.close')")
    public CashService.SettlementRow create(@RequestBody CashService.SettlementRequest r) {
        return cash.createSettlement(r);
    }

    @PostMapping("/payment-settlements/{id}/void")
    @PreAuthorize("hasAuthority('cash.close')")
    public CashService.SettlementRow voidSettlement(@PathVariable long id, @RequestBody ReasonBody b) {
        return cash.voidSettlement(id, b.reason());
    }
}
