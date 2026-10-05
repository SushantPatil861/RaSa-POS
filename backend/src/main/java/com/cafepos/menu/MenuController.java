package com.cafepos.menu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class MenuController {

    public record CreateMenu(@NotBlank String name, String reason, Long copyFromId, Boolean blank) {}

    public record RenameMenu(String name, String reason) {}

    public record ScheduleMenu(@NotNull LocalDate effectiveFrom, String reason) {}

    private final MenuService menus;

    public MenuController(MenuService menus) {
        this.menus = menus;
    }

    @GetMapping("/menus")
    @PreAuthorize("hasAuthority('menu.view')")
    public List<MenuService.MenuVersionDto> list() {
        return menus.versions();
    }

    @GetMapping("/menus/active")
    @PreAuthorize("hasAuthority('menu.view')")
    public MenuService.MenuDetail active(@RequestParam(required = false) LocalDate date) {
        return menus.activeOn(date);
    }

    @GetMapping("/menus/compare")
    @PreAuthorize("hasAuthority('menu.view')")
    public MenuService.Comparison compare(@RequestParam long a, @RequestParam long b) {
        return menus.compare(a, b);
    }

    @GetMapping("/menus/{id}")
    @PreAuthorize("hasAuthority('menu.view')")
    public MenuService.MenuDetail get(@PathVariable long id) {
        return menus.detail(id);
    }

    @PostMapping("/menus")
    @PreAuthorize("hasAuthority('menu.edit')")
    public MenuService.MenuDetail create(@Valid @RequestBody CreateMenu r) {
        return menus.create(r.name(), r.reason(), r.copyFromId(), Boolean.TRUE.equals(r.blank()));
    }

    @PatchMapping("/menus/{id}")
    @PreAuthorize("hasAuthority('menu.edit')")
    public MenuService.MenuVersionDto rename(@PathVariable long id, @RequestBody RenameMenu r) {
        return menus.rename(id, r.name(), r.reason());
    }

    @PostMapping("/menus/{id}/items")
    @PreAuthorize("hasAuthority('menu.edit')")
    public MenuService.MenuItemDto addItem(@PathVariable long id, @RequestBody MenuService.ItemInput r) {
        return menus.addItem(id, r);
    }

    @PatchMapping("/menus/{id}/items/{itemId}")
    @PreAuthorize("hasAuthority('menu.edit')")
    public MenuService.MenuItemDto updateItem(@PathVariable long id, @PathVariable long itemId,
                                              @RequestBody MenuService.ItemInput r) {
        return menus.updateItem(id, itemId, r);
    }

    @DeleteMapping("/menus/{id}/items/{itemId}")
    @PreAuthorize("hasAuthority('menu.edit')")
    public void removeItem(@PathVariable long id, @PathVariable long itemId) {
        menus.removeItem(id, itemId);
    }

    @PostMapping("/menus/{id}/schedule")
    @PreAuthorize("hasAuthority('menu.publish')")
    public MenuService.MenuDetail schedule(@PathVariable long id, @Valid @RequestBody ScheduleMenu r) {
        return menus.schedule(id, r.effectiveFrom(), r.reason());
    }

    @PostMapping("/menus/{id}/unschedule")
    @PreAuthorize("hasAuthority('menu.publish')")
    public MenuService.MenuDetail unschedule(@PathVariable long id) {
        return menus.unschedule(id);
    }

    @GetMapping("/price-history")
    @PreAuthorize("hasAuthority('menu.view')")
    public List<MenuService.PriceHistoryDto> priceHistory(@RequestParam(required = false) Long productId,
                                                          @RequestParam(required = false) Long variantId) {
        return menus.priceHistory(productId, variantId);
    }
}
