package com.cafepos.expenses;

import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ExpenseController {

    public record CategoryCreate(@NotBlank String name, Long parentId) {}
    public record VoidRequest(String reason) {}

    private final ExpenseService expenses;

    public ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    @GetMapping("/expense-categories")
    @PreAuthorize("hasAuthority('expenses.view')")
    public List<ExpenseService.CategoryDto> categories() {
        return expenses.categories();
    }

    @PostMapping("/expense-categories")
    @PreAuthorize("hasAuthority('expenses.create')")
    public ExpenseService.CategoryDto createCategory(@RequestBody CategoryCreate r) {
        return expenses.createCategory(r.name(), r.parentId());
    }

    @GetMapping("/expenses")
    @PreAuthorize("hasAuthority('expenses.view')")
    public List<ExpenseService.ExpenseDto> list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "false") boolean includeVoided,
            @RequestParam(defaultValue = "200") int limit) {
        return expenses.list(from, to, categoryId, includeVoided, limit);
    }

    @GetMapping("/expenses/{id}")
    @PreAuthorize("hasAuthority('expenses.view')")
    public ExpenseService.ExpenseDto get(@PathVariable long id) {
        return expenses.get(id);
    }

    @PostMapping("/expenses")
    @PreAuthorize("hasAuthority('expenses.create')")
    public ExpenseService.ExpenseDto create(@RequestBody ExpenseService.ExpenseRequest r) {
        return expenses.create(r);
    }

    @PutMapping("/expenses/{id}")
    @PreAuthorize("hasAuthority('expenses.edit')")
    public ExpenseService.ExpenseDto update(@PathVariable long id, @RequestBody ExpenseService.ExpenseRequest r) {
        return expenses.update(id, r);
    }

    @PostMapping("/expenses/{id}/void")
    @PreAuthorize("hasAuthority('expenses.edit')")
    public ExpenseService.ExpenseDto voidExpense(@PathVariable long id, @RequestBody VoidRequest r) {
        return expenses.voidExpense(id, r.reason());
    }
}
