package com.cafepos.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class WastageController {

    public record ReasonBody(String reason) {}

    private final WastageService wastage;

    public WastageController(WastageService wastage) {
        this.wastage = wastage;
    }

    @GetMapping("/wastage")
    @PreAuthorize("hasAnyAuthority('inventory.view','wastage.create')")
    public List<WastageService.WastageDto> list(@RequestParam(required = false) LocalDate from,
                                                @RequestParam(required = false) LocalDate to,
                                                @RequestParam(required = false) String reason,
                                                @RequestParam(defaultValue = "false") boolean includeVoided,
                                                @RequestParam(defaultValue = "200") int limit) {
        return wastage.list(from, to, reason, includeVoided, limit);
    }

    @GetMapping("/wastage/summary")
    @PreAuthorize("hasAnyAuthority('inventory.view','wastage.create')")
    public WastageService.Summary summary(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return wastage.summary(from, to);
    }

    @PostMapping("/wastage")
    @PreAuthorize("hasAuthority('wastage.create')")
    public WastageService.WastageDto create(@RequestBody WastageService.WastageRequest r) {
        return wastage.create(r);
    }

    @PostMapping("/wastage/{id}/void")
    @PreAuthorize("hasAuthority('wastage.void')")
    public WastageService.WastageDto voidEntry(@PathVariable long id, @RequestBody ReasonBody b) {
        return wastage.voidEntry(id, b.reason());
    }
}
