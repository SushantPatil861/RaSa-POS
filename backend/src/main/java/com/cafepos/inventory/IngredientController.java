package com.cafepos.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class IngredientController {

    public record ReasonBody(String reason) {}

    public record ActiveBody(boolean active) {}

    private final IngredientService ingredients;

    public IngredientController(IngredientService ingredients) {
        this.ingredients = ingredients;
    }

    @GetMapping("/units")
    @PreAuthorize("hasAnyAuthority('inventory.view','recipes.view')")
    public List<IngredientService.UnitDto> units() {
        return ingredients.units();
    }

    @GetMapping("/ingredients")
    @PreAuthorize("hasAnyAuthority('inventory.view','recipes.view')")
    public List<IngredientService.IngredientDto> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return ingredients.list(includeInactive);
    }

    @GetMapping("/ingredients/{id}")
    @PreAuthorize("hasAnyAuthority('inventory.view','recipes.view')")
    public IngredientService.IngredientDto get(@PathVariable long id) {
        return ingredients.get(id);
    }

    @PostMapping("/ingredients")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public IngredientService.IngredientDto create(@RequestBody IngredientService.IngredientRequest r) {
        return ingredients.create(r);
    }

    @PutMapping("/ingredients/{id}")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public IngredientService.IngredientDto update(@PathVariable long id, @RequestBody IngredientService.IngredientRequest r) {
        return ingredients.update(id, r);
    }

    // ---- pack specs

    @GetMapping("/ingredients/{id}/pack-specs")
    @PreAuthorize("hasAnyAuthority('inventory.view','containers.manage')")
    public List<IngredientService.PackSpecDto> packSpecs(@PathVariable long id) {
        return ingredients.packSpecs(id);
    }

    @PostMapping("/ingredients/{id}/pack-specs")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public IngredientService.PackSpecDto createPackSpec(@PathVariable long id, @RequestBody IngredientService.PackSpecRequest r) {
        return ingredients.createPackSpec(id, r);
    }

    @PostMapping("/ingredient-pack-specs/{id}/active")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public IngredientService.PackSpecDto setPackSpecActive(@PathVariable long id, @RequestBody ActiveBody b) {
        return ingredients.setPackSpecActive(id, b.active());
    }

    // ---- cost history (cost figures: costing.view AND finance.view_profit)

    @GetMapping("/ingredients/{id}/cost-history")
    @PreAuthorize("hasAuthority('costing.view') and hasAuthority('finance.view_profit')")
    public List<IngredientService.CostHistoryDto> costHistory(@PathVariable long id) {
        return ingredients.costHistory(id);
    }

    @PostMapping("/ingredients/{id}/costs")
    @PreAuthorize("hasAuthority('inventory.manage') and hasAuthority('costing.view') and hasAuthority('finance.view_profit')")
    public List<IngredientService.CostHistoryDto> addCost(@PathVariable long id, @RequestBody IngredientService.CostRequest r) {
        return ingredients.addCost(id, r);
    }

    @PostMapping("/ingredient-costs/{id}/void")
    @PreAuthorize("hasAuthority('inventory.manage') and hasAuthority('costing.view') and hasAuthority('finance.view_profit')")
    public List<IngredientService.CostHistoryDto> voidCost(@PathVariable long id, @RequestBody ReasonBody b) {
        return ingredients.voidCost(id, b.reason());
    }

    // ---- purchase links

    @GetMapping("/purchase-items/unlinked")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public List<IngredientService.UnlinkedItem> unlinked(@RequestParam(required = false) LocalDate from,
                                                         @RequestParam(required = false) LocalDate to) {
        return ingredients.unlinkedItems(from, to);
    }

    @GetMapping("/purchase-links")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public List<IngredientService.LinkDto> links(@RequestParam(required = false) Long ingredientId,
                                                 @RequestParam(required = false) String status) {
        return ingredients.links(ingredientId, status);
    }

    @PostMapping("/purchase-items/{id}/link")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public IngredientService.LinkDto link(@PathVariable long id, @RequestBody IngredientService.LinkRequest r) {
        return ingredients.link(id, r);
    }

    @PostMapping("/purchase-links/{id}/unlink")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public IngredientService.LinkDto unlink(@PathVariable long id, @RequestBody ReasonBody b) {
        return ingredients.unlink(id, b.reason());
    }

    @PostMapping("/purchase-links/reconcile")
    @PreAuthorize("hasAuthority('inventory.manage')")
    public IngredientService.ReconcileResult reconcile() {
        return ingredients.reconcile();
    }
}
