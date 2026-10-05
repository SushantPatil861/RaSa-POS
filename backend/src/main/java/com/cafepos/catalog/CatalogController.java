package com.cafepos.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    public record CategoryCreate(@NotBlank String name, Long parentId) {}

    public record CategoryUpdate(String name, Boolean active, Integer sortOrder) {}

    public record ProductCreate(@NotBlank String name, @NotNull Long categoryId, String subcategory, String unit,
                                String description, String lifecycleStatus,
                                List<CatalogService.VariantInput> variants) {}

    public record ProductUpdate(String name, Long categoryId, String subcategory, String description, String imageUrl) {}

    public record LifecycleRequest(@NotBlank String status, LocalDate effectiveDate, String reason) {}

    public record VariantCreate(@NotBlank String name, String size) {}

    public record VariantUpdate(String name, String size, String status) {}

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('menu.view')")
    public List<CatalogService.CategoryDto> categories() {
        return catalog.categories();
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAuthority('products.manage')")
    public CatalogService.CategoryDto createCategory(@Valid @RequestBody CategoryCreate r) {
        return catalog.createCategory(r.name(), r.parentId());
    }

    @PatchMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('products.manage')")
    public CatalogService.CategoryDto updateCategory(@PathVariable long id, @RequestBody CategoryUpdate r) {
        return catalog.updateCategory(id, r.name(), r.active(), r.sortOrder());
    }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('menu.view')")
    public List<CatalogService.ProductDto> products(@RequestParam(required = false) String q,
                                                    @RequestParam(required = false) Long categoryId,
                                                    @RequestParam(required = false) String status) {
        return catalog.products(q, categoryId, status);
    }

    @GetMapping("/products/{id}")
    @PreAuthorize("hasAuthority('menu.view')")
    public CatalogService.ProductDetail product(@PathVariable long id) {
        return catalog.product(id);
    }

    @PostMapping("/products")
    @PreAuthorize("hasAuthority('products.manage')")
    public CatalogService.ProductDetail create(@Valid @RequestBody ProductCreate r) {
        return catalog.createProduct(r.name(), r.categoryId(), r.subcategory(), r.unit(), r.description(),
                r.lifecycleStatus(), r.variants());
    }

    @PatchMapping("/products/{id}")
    @PreAuthorize("hasAuthority('products.manage')")
    public CatalogService.ProductDetail update(@PathVariable long id, @RequestBody ProductUpdate r) {
        return catalog.updateProduct(id, r.name(), r.categoryId(), r.subcategory(), r.description(), r.imageUrl());
    }

    @PostMapping("/products/{id}/lifecycle")
    @PreAuthorize("hasAuthority('products.manage')")
    public CatalogService.ProductDetail lifecycle(@PathVariable long id, @Valid @RequestBody LifecycleRequest r) {
        return catalog.setLifecycle(id, r.status(), r.effectiveDate(), r.reason());
    }

    @PostMapping("/products/{id}/variants")
    @PreAuthorize("hasAuthority('products.manage')")
    public CatalogService.VariantDto addVariant(@PathVariable long id, @Valid @RequestBody VariantCreate r) {
        return catalog.addVariant(id, r.name(), r.size());
    }

    @PatchMapping("/products/{id}/variants/{variantId}")
    @PreAuthorize("hasAuthority('products.manage')")
    public CatalogService.VariantDto updateVariant(@PathVariable long id, @PathVariable long variantId,
                                                   @RequestBody VariantUpdate r) {
        return catalog.updateVariant(id, variantId, r.name(), r.size(), r.status());
    }
}
