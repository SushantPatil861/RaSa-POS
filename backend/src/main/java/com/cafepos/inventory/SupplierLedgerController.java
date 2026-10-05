package com.cafepos.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SupplierLedgerController {

    public record ReasonBody(String reason) {}

    private final SupplierLedgerService ledger;

    public SupplierLedgerController(SupplierLedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/supplier-ledger")
    @PreAuthorize("hasAuthority('supplier_ledger.view')")
    public List<SupplierLedgerService.SupplierBalance> balances(@RequestParam(required = false) LocalDate asOf) {
        return ledger.balances(asOf);
    }

    @GetMapping("/suppliers/{id}/ledger")
    @PreAuthorize("hasAuthority('supplier_ledger.view')")
    public SupplierLedgerService.Ledger supplier(@PathVariable long id, @RequestParam(required = false) LocalDate asOf) {
        return ledger.ledger(id, asOf);
    }

    @GetMapping("/supplier-payments")
    @PreAuthorize("hasAuthority('supplier_ledger.view')")
    public List<SupplierLedgerService.PaymentRow> payments(@RequestParam(required = false) Long supplierId,
                                                           @RequestParam(required = false) LocalDate from,
                                                           @RequestParam(required = false) LocalDate to,
                                                           @RequestParam(defaultValue = "false") boolean includeVoided) {
        return ledger.payments(supplierId, from, to, includeVoided);
    }

    @PostMapping("/supplier-payments")
    @PreAuthorize("hasAuthority('supplier_ledger.manage')")
    public SupplierLedgerService.PaymentRow create(@RequestBody SupplierLedgerService.PaymentRequest r) {
        return ledger.createPayment(r);
    }

    @PostMapping("/supplier-payments/{id}/void")
    @PreAuthorize("hasAuthority('supplier_ledger.manage')")
    public SupplierLedgerService.PaymentRow voidPayment(@PathVariable long id, @RequestBody ReasonBody b) {
        return ledger.voidPayment(id, b.reason());
    }
}
