package com.cafepos.gst;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class GstController {

    public record VoidRequest(String reason) {}

    private final GstService gst;

    public GstController(GstService gst) {
        this.gst = gst;
    }

    @GetMapping("/gst/quarters")
    @PreAuthorize("hasAuthority('gst.view')")
    public List<GstService.QuarterInfo> quarters(@RequestParam(required = false) Integer fy) {
        return gst.quarters(fy);
    }

    /** Live computation, nothing is saved. */
    @GetMapping("/gst/preview")
    @PreAuthorize("hasAuthority('gst.view')")
    public GstService.Computed preview(@RequestParam String periodType, @RequestParam LocalDate periodStart,
                                       @RequestParam(required = false) BigDecimal openingCreditIgst,
                                       @RequestParam(required = false) BigDecimal openingCreditCgst,
                                       @RequestParam(required = false) BigDecimal openingCreditSgst) {
        return gst.preview(periodType, periodStart, openingCreditIgst, openingCreditCgst, openingCreditSgst);
    }

    @GetMapping("/gst/workpapers")
    @PreAuthorize("hasAuthority('gst.view')")
    public List<GstService.WorkpaperRow> workpapers(@RequestParam(required = false) String periodType,
                                                    @RequestParam(required = false) LocalDate periodStart) {
        return gst.list(periodType, periodStart);
    }

    @GetMapping("/gst/workpapers/{id}")
    @PreAuthorize("hasAuthority('gst.view')")
    public GstService.WorkpaperDto workpaper(@PathVariable long id) {
        return gst.get(id);
    }

    /** Creates a NEW snapshot version (never overwrites an earlier one). */
    @PostMapping("/gst/workpapers")
    @PreAuthorize("hasAuthority('gst.prepare')")
    public GstService.WorkpaperDto prepare(@RequestBody GstService.WorkpaperRequest r) {
        return gst.prepare(r);
    }

    @PostMapping("/gst/workpapers/{id}/review")
    @PreAuthorize("hasAuthority('gst.prepare')")
    public GstService.WorkpaperDto review(@PathVariable long id, @RequestBody GstService.ReviewRequest r) {
        return gst.markReviewed(id, r.notes());
    }

    @PostMapping("/gst/workpapers/{id}/mark-filed")
    @PreAuthorize("hasAuthority('gst.prepare')")
    public GstService.WorkpaperDto markFiled(@PathVariable long id, @RequestBody GstService.ReviewRequest r) {
        return gst.markFiled(id, r.notes());
    }

    @GetMapping("/gst/workpapers/{id}/export.csv")
    @PreAuthorize("hasAuthority('gst.view')")
    public ResponseEntity<byte[]> exportCsv(@PathVariable long id) {
        byte[] body = gst.exportCsv(id).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"gst-workpaper-" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    @GetMapping("/gst/filings")
    @PreAuthorize("hasAuthority('gst.view')")
    public List<GstService.FilingDto> filings(@RequestParam(required = false) LocalDate from,
                                              @RequestParam(required = false) LocalDate to,
                                              @RequestParam(defaultValue = "false") boolean includeVoided) {
        return gst.filings(from, to, includeVoided);
    }

    @PostMapping("/gst/filings")
    @PreAuthorize("hasAuthority('gst.prepare')")
    public GstService.FilingDto logFiling(@RequestBody GstService.FilingRequest r) {
        return gst.logFiling(r);
    }

    @PostMapping("/gst/filings/{id}/void")
    @PreAuthorize("hasAuthority('gst.prepare')")
    public GstService.FilingDto voidFiling(@PathVariable long id, @RequestBody VoidRequest r) {
        return gst.voidFiling(id, r.reason());
    }

    @GetMapping("/gst/itc/purchases")
    @PreAuthorize("hasAuthority('gst.view')")
    public List<GstService.ItcPurchaseRow> itcPurchases(@RequestParam(required = false) LocalDate from,
                                                        @RequestParam(required = false) LocalDate to,
                                                        @RequestParam(defaultValue = "ALL") String filter,
                                                        @RequestParam(defaultValue = "200") int limit) {
        return gst.itcPurchases(from, to, filter, limit);
    }

    @PutMapping("/gst/itc/purchases/{purchaseId}")
    @PreAuthorize("hasAuthority('gst.prepare')")
    public GstService.ItcPurchaseRow saveItc(@PathVariable long purchaseId, @RequestBody GstService.ItcRequest r) {
        return gst.saveItc(purchaseId, r);
    }
}
