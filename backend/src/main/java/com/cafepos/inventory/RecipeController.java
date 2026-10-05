package com.cafepos.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class RecipeController {

    private final RecipeService recipes;

    public RecipeController(RecipeService recipes) {
        this.recipes = recipes;
    }

    /** Variants with recipe status (cost columns appear only for users with costing.view + finance.view_profit). */
    @GetMapping("/recipes/variants")
    @PreAuthorize("hasAuthority('recipes.view')")
    public List<RecipeService.VariantCostDto> variants(@RequestParam(required = false) LocalDate asOf) {
        return recipes.variants(asOf);
    }

    @GetMapping("/recipes/detail")
    @PreAuthorize("hasAuthority('recipes.view')")
    public RecipeService.RecipeDetail detail(@RequestParam(required = false) Long variantId,
                                             @RequestParam(required = false) Long ingredientId) {
        return recipes.detail(variantId, ingredientId);
    }

    @PostMapping("/recipes/versions")
    @PreAuthorize("hasAuthority('recipes.edit')")
    public RecipeService.RecipeDetail addVersion(@RequestBody RecipeService.VersionRequest r) {
        return recipes.addVersion(r);
    }

    @GetMapping("/recipes/cost")
    @PreAuthorize("hasAuthority('recipes.view') and hasAuthority('costing.view') and hasAuthority('finance.view_profit')")
    public RecipeService.RecipeCost cost(@RequestParam(required = false) Long variantId,
                                         @RequestParam(required = false) Long ingredientId,
                                         @RequestParam(required = false) LocalDate asOf) {
        return recipes.cost(variantId, ingredientId, asOf);
    }
}
