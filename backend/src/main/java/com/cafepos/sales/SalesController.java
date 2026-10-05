package com.cafepos.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SalesController {

    public record VoidRequest(@NotBlank String reason) {}

    public record ApproveRequest(@NotBlank String decision, String reason) {}

    public record CalendarRequest(@NotBlank String dayType, String note) {}

    private final SalesService sales;
    private final DailySummaryService summaries;
    private final DayService days;

    public SalesController(SalesService sales, DailySummaryService summaries, DayService days) {
        this.sales = sales;
        this.summaries = summaries;
        this.days = days;
    }

    // ---- itemised sales
    @GetMapping("/sales")
    @PreAuthorize("hasAuthority('sales.view')")
    public List<SalesService.SaleSummary> list(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                               @RequestParam(defaultValue = "false") boolean includeVoided,
                                               @RequestParam(defaultValue = "500") int limit) {
        return sales.list(from, to, includeVoided, limit);
    }

    @GetMapping("/sales/{id}")
    @PreAuthorize("hasAuthority('sales.view')")
    public SalesService.SaleDetail get(@PathVariable long id) {
        return sales.get(id);
    }

    @PostMapping("/sales")
    @PreAuthorize("hasAuthority('sales.create')")
    public SalesService.SaleDetail create(@Valid @RequestBody SalesService.SaleRequest r) {
        return sales.create(r);
    }

    @PutMapping("/sales/{id}")
    @PreAuthorize("hasAuthority('sales.edit')")
    public SalesService.SaleDetail update(@PathVariable long id, @Valid @RequestBody SalesService.SaleRequest r) {
        return sales.update(id, r);
    }

    @PostMapping("/sales/{id}/void")
    @PreAuthorize("hasAuthority('sales.void')")
    public SalesService.SaleDetail voidSale(@PathVariable long id, @Valid @RequestBody VoidRequest r) {
        return sales.voidSale(id, r.reason());
    }

    // ---- quick daily entry
    @GetMapping("/daily-summaries")
    @PreAuthorize("hasAuthority('sales.view')")
    public List<DailySummaryService.SummaryDto> listSummaries(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                                              @RequestParam(defaultValue = "false") boolean includeVoided) {
        return summaries.list(from, to, includeVoided);
    }

    @GetMapping("/daily-summaries/{id}")
    @PreAuthorize("hasAuthority('sales.view')")
    public DailySummaryService.SummaryDto getSummary(@PathVariable long id) {
        return summaries.get(id);
    }

    @PostMapping("/daily-summaries")
    @PreAuthorize("hasAuthority('sales.create')")
    public DailySummaryService.SummaryDto createSummary(@Valid @RequestBody DailySummaryService.SummaryRequest r) {
        return summaries.create(r);
    }

    @PutMapping("/daily-summaries/{id}")
    @PreAuthorize("hasAuthority('sales.edit')")
    public DailySummaryService.SummaryDto updateSummary(@PathVariable long id,
                                                        @Valid @RequestBody DailySummaryService.SummaryRequest r) {
        return summaries.update(id, r);
    }

    @PostMapping("/daily-summaries/{id}/void")
    @PreAuthorize("hasAuthority('sales.void')")
    public DailySummaryService.SummaryDto voidSummary(@PathVariable long id, @Valid @RequestBody VoidRequest r) {
        return summaries.voidEntry(id, r.reason());
    }

    // ---- a trading day
    @GetMapping("/days/{date}")
    @PreAuthorize("hasAuthority('sales.view')")
    public DayService.DayView day(@PathVariable LocalDate date) {
        return days.view(date);
    }

    @PostMapping("/days/{date}/approve")
    @PreAuthorize("hasAuthority('sales.approve_day_variance')")
    public DayService.DayView approve(@PathVariable LocalDate date, @Valid @RequestBody ApproveRequest r) {
        return days.approve(date, r.decision(), r.reason());
    }

    @PostMapping("/days/{date}/calendar")
    @PreAuthorize("hasAuthority('sales.edit')")
    public DayService.DayView calendar(@PathVariable LocalDate date, @Valid @RequestBody CalendarRequest r) {
        return days.setCalendar(date, r.dayType(), r.note());
    }
}
