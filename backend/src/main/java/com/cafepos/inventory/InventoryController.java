package com.cafepos.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class InventoryController {

    public record ReasonBody(String reason) {}

    private final InventoryService inventory;
    private final LedgerService ledger;

    public InventoryController(InventoryService inventory, LedgerService ledger) {
        this.inventory = inventory;
        this.ledger = ledger;
    }

    // ---- stock reports

    @GetMapping("/inventory/stock")
    @PreAuthorize("hasAuthority('inventory.view')")
    public InventoryService.StockReport stock(@RequestParam(required = false) LocalDate asOf,
                                              @RequestParam(defaultValue = "false") boolean includeInactive) {
        return inventory.stock(asOf, includeInactive);
    }

    @GetMapping("/inventory/low-stock")
    @PreAuthorize("hasAuthority('inventory.view')")
    public List<InventoryService.StockDto> lowStock(@RequestParam(required = false) LocalDate asOf) {
        return inventory.lowStock(asOf);
    }

    @GetMapping("/inventory/usage")
    @PreAuthorize("hasAuthority('inventory.view')")
    public InventoryService.UsageReport usage(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return inventory.usage(from, to);
    }

    @GetMapping("/inventory/variance")
    @PreAuthorize("hasAuthority('inventory.view')")
    public InventoryService.VarianceReport variance(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return inventory.variance(from, to);
    }

    // ---- ledger

    @GetMapping("/inventory/movements")
    @PreAuthorize("hasAuthority('inventory.view')")
    public List<LedgerService.MovementDto> movements(@RequestParam(required = false) Long ingredientId,
                                                     @RequestParam(required = false) LocalDate from,
                                                     @RequestParam(required = false) LocalDate to,
                                                     @RequestParam(defaultValue = "200") int limit) {
        return ledger.list(ingredientId, from, to, limit);
    }

    @PostMapping("/inventory/movements")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public LedgerService.MovementDto createMovement(@RequestBody LedgerService.MovementRequest r) {
        return ledger.createManual(r);
    }

    @PostMapping("/inventory/movements/{id}/reverse")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public LedgerService.MovementDto reverse(@PathVariable long id, @RequestBody ReasonBody b) {
        return ledger.reverseManual(id, b.reason());
    }

    // ---- stock counts

    @GetMapping("/stock-counts")
    @PreAuthorize("hasAuthority('inventory.view')")
    public List<InventoryService.CountHeader> counts(@RequestParam(defaultValue = "50") int limit) {
        return inventory.counts(limit);
    }

    @GetMapping("/stock-counts/{id}")
    @PreAuthorize("hasAuthority('inventory.view')")
    public InventoryService.CountDetail count(@PathVariable long id) {
        return inventory.count(id);
    }

    @PostMapping("/stock-counts")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public InventoryService.CountDetail createCount(@RequestBody InventoryService.CountRequest r) {
        return inventory.createCount(r);
    }

    @PutMapping("/stock-counts/{id}")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public InventoryService.CountDetail updateCount(@PathVariable long id, @RequestBody InventoryService.CountRequest r) {
        return inventory.updateCount(id, r);
    }

    @PostMapping("/stock-counts/{id}/post")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public InventoryService.CountDetail post(@PathVariable long id) {
        return inventory.postCount(id);
    }

    @PostMapping("/stock-counts/{id}/void")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public InventoryService.CountDetail voidCount(@PathVariable long id, @RequestBody ReasonBody b) {
        return inventory.voidCount(id, b.reason());
    }

    // ---- prep batches

    @GetMapping("/prep-batches")
    @PreAuthorize("hasAuthority('inventory.view')")
    public List<InventoryService.PrepDto> prep(@RequestParam(required = false) LocalDate from,
                                               @RequestParam(required = false) LocalDate to,
                                               @RequestParam(defaultValue = "false") boolean includeVoided) {
        return inventory.prepBatches(from, to, includeVoided);
    }

    @PostMapping("/prep-batches")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public InventoryService.PrepDto createPrep(@RequestBody InventoryService.PrepRequest r) {
        return inventory.createPrep(r);
    }

    @PostMapping("/prep-batches/{id}/void")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public InventoryService.PrepDto voidPrep(@PathVariable long id, @RequestBody ReasonBody b) {
        return inventory.voidPrep(id, b.reason());
    }
}
