package com.cafepos.costing;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Cost and margin figures: need finance.view_profit AND costing.view. */
@RestController
@RequestMapping("/api/v1")
public class CostingController {

    private final CostingService costing;

    public CostingController(CostingService costing) {
        this.costing = costing;
    }

    @GetMapping("/costing/ingredient-costs")
    @PreAuthorize("hasAuthority('costing.view') and hasAuthority('finance.view_profit')")
    public List<CostingService.IngredientCost> ingredientCosts(@RequestParam(required = false) LocalDate asOf) {
        return costing.ingredientCosts(asOf);
    }

    @GetMapping("/reports/costing/products")
    @PreAuthorize("hasAuthority('costing.view') and hasAuthority('finance.view_profit')")
    public CostingService.ProductMarginReport products(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return costing.productMargins(from, to);
    }

    @GetMapping("/reports/costing/gross-profit")
    @PreAuthorize("hasAuthority('costing.view') and hasAuthority('finance.view_profit')")
    public CostingService.GrossProfit grossProfit(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return costing.grossProfit(from, to);
    }

    @GetMapping("/reports/costing/pnl")
    @PreAuthorize("hasAuthority('costing.view') and hasAuthority('finance.view_profit') and hasAuthority('reports.view')")
    public CostingService.RealPnl pnl(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return costing.realPnl(from, to);
    }
}
