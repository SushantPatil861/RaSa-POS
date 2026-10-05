package com.cafepos.purchases;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class PurchaseController {

    public record VoidRequest(String reason) {}

    private final PurchaseService purchases;

    public PurchaseController(PurchaseService purchases) {
        this.purchases = purchases;
    }

    @GetMapping("/suppliers")
    @PreAuthorize("hasAuthority('purchases.view')")
    public List<PurchaseService.SupplierDto> suppliers() {
        return purchases.suppliers();
    }

    @PostMapping("/suppliers")
    @PreAuthorize("hasAuthority('purchases.create')")
    public PurchaseService.SupplierDto createSupplier(@RequestBody PurchaseService.SupplierRequest r) {
        return purchases.createSupplier(r);
    }

    @GetMapping("/purchases")
    @PreAuthorize("hasAuthority('purchases.view')")
    public List<PurchaseService.PurchaseDto> list(@RequestParam(required = false) LocalDate from,
                                                  @RequestParam(required = false) LocalDate to,
                                                  @RequestParam(required = false) Long supplierId,
                                                  @RequestParam(defaultValue = "false") boolean includeVoided,
                                                  @RequestParam(defaultValue = "200") int limit) {
        return purchases.list(from, to, supplierId, includeVoided, limit);
    }

    @GetMapping("/purchases/{id}")
    @PreAuthorize("hasAuthority('purchases.view')")
    public PurchaseService.PurchaseDto get(@PathVariable long id) {
        return purchases.get(id);
    }

    @PostMapping("/purchases")
    @PreAuthorize("hasAuthority('purchases.create')")
    public PurchaseService.PurchaseDto create(@RequestBody PurchaseService.PurchaseRequest r) {
        return purchases.create(r);
    }

    @PutMapping("/purchases/{id}")
    @PreAuthorize("hasAuthority('purchases.edit')")
    public PurchaseService.PurchaseDto update(@PathVariable long id, @RequestBody PurchaseService.PurchaseRequest r) {
        return purchases.update(id, r);
    }

    @PostMapping("/purchases/{id}/void")
    @PreAuthorize("hasAuthority('purchases.edit')")
    public PurchaseService.PurchaseDto voidPurchase(@PathVariable long id, @RequestBody VoidRequest r) {
        return purchases.voidPurchase(id, r.reason());
    }
}
