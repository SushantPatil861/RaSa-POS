package com.cafepos.reporting;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('reports.view')")
    public ReportService.Dashboard dashboard(@RequestParam(required = false) LocalDate date) {
        return reports.dashboard(date);
    }

    @GetMapping("/sales-by-day")
    @PreAuthorize("hasAuthority('reports.view')")
    public List<ReportService.DayRow> salesByDay(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.salesByDay(from, to);
    }

    /** Profit & loss view. Needs the profit permission as well, because it shows margins/costs. */
    @GetMapping("/pnl")
    @PreAuthorize("hasAuthority('reports.view') and hasAuthority('finance.view_profit')")
    public ReportService.Pnl pnl(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.pnl(from, to);
    }

    @GetMapping("/months")
    @PreAuthorize("hasAuthority('reports.view') and hasAuthority('finance.view_profit')")
    public List<ReportService.MonthRow> months(@RequestParam(defaultValue = "3") int count) {
        return reports.months(count);
    }

    @GetMapping("/channels")
    @PreAuthorize("hasAuthority('reports.view')")
    public List<ReportService.ChannelRow> channels(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.channels(from, to);
    }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('reports.view')")
    public List<ReportService.ProductRow> products(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                                   @RequestParam(defaultValue = "50") int limit) {
        return reports.products(from, to, limit);
    }

    @GetMapping("/data-health")
    @PreAuthorize("hasAuthority('reports.view')")
    public ReportService.DataHealth dataHealth(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.dataHealth(from, to);
    }
}
