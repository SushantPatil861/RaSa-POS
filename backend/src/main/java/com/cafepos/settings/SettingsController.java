package com.cafepos.settings;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/business")
    @PreAuthorize("isAuthenticated()")
    public SettingsService.BusinessDto business() {
        return settings.business();
    }

    @PutMapping("/business")
    @PreAuthorize("hasAuthority('settings.manage')")
    public SettingsService.BusinessDto updateBusiness(@RequestBody SettingsService.BusinessRequest r) {
        return settings.updateBusiness(r);
    }

    @GetMapping("/tax-rules")
    @PreAuthorize("isAuthenticated()")
    public List<SettingsService.TaxRuleDto> taxRules() {
        return settings.taxRules();
    }

    @PostMapping("/tax-rules")
    @PreAuthorize("hasAuthority('settings.manage')")
    public SettingsService.TaxRuleDto addTaxRule(@RequestBody SettingsService.TaxRuleRequest r) {
        return settings.addTaxRule(r);
    }
}
