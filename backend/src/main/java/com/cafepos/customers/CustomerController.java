package com.cafepos.customers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    public record CustomerCreate(@NotBlank String name, String mobile, String notes) {}

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customers.view')")
    public List<CustomerService.CustomerDto> list(@RequestParam(required = false) String q) {
        return customers.list(q);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('customers.manage')")
    public CustomerService.CustomerDto create(@Valid @RequestBody CustomerCreate r) {
        return customers.create(r.name(), r.mobile(), r.notes());
    }
}
