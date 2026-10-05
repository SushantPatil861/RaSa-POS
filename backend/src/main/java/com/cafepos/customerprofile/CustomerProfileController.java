package com.cafepos.customerprofile;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Separate from the customers module on purpose: only adds read-only profile endpoints. */
@RestController
@RequestMapping("/api/v1")
public class CustomerProfileController {

    private final CustomerProfileService profiles;

    public CustomerProfileController(CustomerProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/customers/{id}/profile")
    @PreAuthorize("hasAuthority('customers.view')")
    public CustomerProfileService.Profile profile(@PathVariable long id) {
        return profiles.profile(id);
    }

    @GetMapping("/customers/{id}/orders")
    @PreAuthorize("hasAuthority('customers.view')")
    public List<CustomerProfileService.OrderBrief> orders(@PathVariable long id,
                                                          @RequestParam(defaultValue = "100") int limit) {
        return profiles.orders(id, limit);
    }
}
