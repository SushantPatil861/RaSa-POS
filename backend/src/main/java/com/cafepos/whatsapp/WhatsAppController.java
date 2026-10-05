package com.cafepos.whatsapp;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/whatsapp")
public class WhatsAppController {

    public record NotifyRequest(String template) {}
    public record ReasonRequest(String reason) {}

    private final WhatsAppService whatsapp;

    public WhatsAppController(WhatsAppService whatsapp) {
        this.whatsapp = whatsapp;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('whatsapp.view')")
    public WhatsAppService.StatusDto status() {
        return whatsapp.status();
    }

    @GetMapping("/inbox")
    @PreAuthorize("hasAuthority('whatsapp.view')")
    public List<WhatsAppService.InboxDto> inbox(@RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "100") int limit) {
        return whatsapp.inbox(status, limit);
    }

    @GetMapping("/thread")
    @PreAuthorize("hasAuthority('whatsapp.view')")
    public List<WhatsAppService.ThreadRow> thread(@RequestParam String number) {
        return whatsapp.thread(number);
    }

    @GetMapping("/menu")
    @PreAuthorize("hasAuthority('whatsapp.view')")
    public List<WhatsAppService.MenuRow> menu(@RequestParam(required = false) String q) {
        return whatsapp.menu(q);
    }

    @PutMapping("/drafts/{id}/items")
    @PreAuthorize("hasAuthority('whatsapp.manage')")
    public WhatsAppService.InboxDto updateItems(@PathVariable long id, @RequestBody WhatsAppService.ItemsRequest r) {
        return whatsapp.updateItems(id, r);
    }

    @PostMapping("/drafts/{id}/confirm")
    @PreAuthorize("hasAuthority('whatsapp.manage')")
    public WhatsAppService.ConfirmResult confirm(@PathVariable long id, @RequestBody WhatsAppService.ConfirmRequest r) {
        return whatsapp.confirm(id, r);
    }

    @PostMapping("/drafts/{id}/reject")
    @PreAuthorize("hasAuthority('whatsapp.manage')")
    public WhatsAppService.InboxDto reject(@PathVariable long id, @RequestBody ReasonRequest r) {
        return whatsapp.reject(id, r.reason());
    }

    @PostMapping("/send")
    @PreAuthorize("hasAuthority('whatsapp.manage')")
    public WhatsAppService.SentResult send(@RequestBody WhatsAppService.SendRequest r) {
        return whatsapp.send(r.to(), r.text());
    }

    @PostMapping("/orders/{orderId}/notify")
    @PreAuthorize("hasAuthority('whatsapp.manage')")
    public WhatsAppService.SentResult notifyOrder(@PathVariable long orderId, @RequestBody NotifyRequest r) {
        return whatsapp.notifyOrder(orderId, r.template());
    }

    @PostMapping("/orders/{orderId}/payment-request")
    @PreAuthorize("hasAuthority('whatsapp.manage')")
    public WhatsAppService.SentResult paymentRequest(@PathVariable long orderId) {
        return whatsapp.paymentRequest(orderId);
    }
}
