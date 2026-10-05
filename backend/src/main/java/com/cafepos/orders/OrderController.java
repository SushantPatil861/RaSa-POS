package com.cafepos.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    public record StatusRequest(@NotBlank String status) {}

    public record CancelRequest(String reason) {}

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('orders.view')")
    public List<OrderService.OrderRow> list(@RequestParam(required = false) String status,
                                            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return orders.list(status, from, to);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('orders.view')")
    public OrderService.OrderDetail get(@PathVariable long id) {
        return orders.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('orders.manage')")
    public OrderService.OrderDetail create(@Valid @RequestBody OrderService.CreateOrder r) {
        return orders.create(r);
    }

    @PostMapping("/{id}/status")
    public OrderService.OrderDetail status(@PathVariable long id, @Valid @RequestBody StatusRequest r) {
        return orders.setStatus(id, r.status());
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('orders.manage')")
    public OrderService.OrderDetail complete(@PathVariable long id, @Valid @RequestBody OrderService.CompleteOrder r) {
        return orders.complete(id, r);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('orders.manage')")
    public OrderService.OrderDetail cancel(@PathVariable long id, @RequestBody(required = false) CancelRequest r) {
        return orders.cancel(id, r == null ? null : r.reason());
    }
}
