package com.cafepos.costing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Framework-free recipe cost maths.
 *  - A recipe version has an output quantity and lines (ingredient, quantity in base units, waste %).
 *  - PREPARED ingredients have their own recipe; their cost per base unit is resolved recursively.
 *  - A missing ingredient cost is NEVER treated as zero: it is listed in {@code missing} and the result becomes
 *    PARTIAL (some lines costed) or UNKNOWN (nothing could be costed).
 *  - Cycles (A needs B needs A) are detected and reported with the names involved.
 * All costs are per BASE unit (g, ml, pcs); money rounding to 2 decimals is the caller's job.
 */
public final class CostResolver {

    public static final String COMPLETE = "COMPLETE";
    public static final String PARTIAL = "PARTIAL";
    public static final String UNKNOWN = "UNKNOWN";

    private static final int SCALE = 6;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record Line(long ingredientId, BigDecimal quantityBase, BigDecimal wastePct) {}

    public record Version(BigDecimal outputQty, List<Line> lines) {}

    public record UnitCost(BigDecimal perBase, boolean estimated) {}

    /** Result: cost is null when UNKNOWN. noRecipe=true when there is no recipe version in force. */
    public record Result(BigDecimal cost, String status, boolean estimated, boolean noRecipe, List<String> missing) {}

    /** Where recipes and costs come from (database in production, maps in tests). Return null when absent. */
    public interface Source {
        Version recipeForVariant(long variantId, LocalDate date);

        Version recipeForIngredient(long ingredientId, LocalDate date);

        UnitCost directCost(long ingredientId, LocalDate date);

        String ingredientName(long ingredientId);

        boolean isPrepared(long ingredientId);
    }

    public static final class CycleException extends RuntimeException {
        public CycleException(String message) {
            super(message);
        }
    }

    private static final class Acc {
        BigDecimal total = BigDecimal.ZERO;
        int costed = 0;
        boolean estimated = false;
        final LinkedHashSet<String> missing = new LinkedHashSet<>();
    }

    private record Sub(BigDecimal perUnit, boolean estimated, List<String> missing) {}

    private final Source src;
    private final Map<String, Sub> cache = new HashMap<>();

    public CostResolver(Source src) {
        this.src = src;
    }

    /** Cost of ONE portion of a product variant on the given date. */
    public Result portionCost(long variantId, LocalDate date) {
        Version v = src.recipeForVariant(variantId, date);
        if (v == null) return new Result(null, UNKNOWN, false, true, List.of());
        Acc acc = new Acc();
        addLines(v, date, new LinkedHashSet<>(), acc);
        return finish(acc, v.outputQty(), false);
    }

    /** Cost of ONE base unit (g/ml/pcs) of an ingredient on the given date (recursive for prepared ones). */
    public Result unitCost(long ingredientId, LocalDate date) {
        Sub s = unitCostOf(ingredientId, date, new LinkedHashSet<>());
        if (s.perUnit() == null) {
            boolean noRecipe = src.isPrepared(ingredientId) && src.recipeForIngredient(ingredientId, date) == null
                    && src.directCost(ingredientId, date) == null;
            List<String> missing = s.missing().isEmpty() ? List.of(src.ingredientName(ingredientId)) : s.missing();
            return new Result(null, UNKNOWN, false, noRecipe, missing);
        }
        String status = s.missing().isEmpty() ? COMPLETE : PARTIAL;
        return new Result(s.perUnit(), status, s.estimated(), false, s.missing());
    }

    private Result finish(Acc acc, BigDecimal outputQty, boolean noRecipe) {
        if (acc.costed == 0) {
            return new Result(null, UNKNOWN, false, noRecipe, new ArrayList<>(acc.missing));
        }
        BigDecimal perOutput = acc.total.divide(outputQty, SCALE, RoundingMode.HALF_UP);
        String status = acc.missing.isEmpty() ? COMPLETE : PARTIAL;
        return new Result(perOutput, status, acc.estimated, false, new ArrayList<>(acc.missing));
    }

    private void addLines(Version v, LocalDate date, LinkedHashSet<Long> path, Acc acc) {
        for (Line line : v.lines()) {
            BigDecimal gross = grossQty(line);
            Sub s = unitCostOf(line.ingredientId(), date, path);
            if (s.perUnit() == null) {
                if (s.missing().isEmpty()) acc.missing.add(src.ingredientName(line.ingredientId()));
                else acc.missing.addAll(s.missing());
                continue;
            }
            acc.total = acc.total.add(gross.multiply(s.perUnit()));
            acc.costed++;
            if (s.estimated()) acc.estimated = true;
            acc.missing.addAll(s.missing());
        }
    }

    private Sub unitCostOf(long ingredientId, LocalDate date, LinkedHashSet<Long> path) {
        if (path.contains(ingredientId)) {
            List<String> names = new ArrayList<>();
            for (Long id : path) names.add(src.ingredientName(id));
            names.add(src.ingredientName(ingredientId));
            throw new CycleException("Recipe cycle: " + String.join(" -> ", names));
        }
        String key = ingredientId + "@" + date;
        Sub cached = cache.get(key);
        if (cached != null) return cached;

        Sub result;
        Version rv = src.isPrepared(ingredientId) ? src.recipeForIngredient(ingredientId, date) : null;
        if (rv != null) {
            path.add(ingredientId);
            Acc sub = new Acc();
            addLines(rv, date, path, sub);
            path.remove(ingredientId);
            if (sub.costed == 0) {
                result = new Sub(null, false, new ArrayList<>(sub.missing));
            } else {
                BigDecimal per = sub.total.divide(rv.outputQty(), SCALE, RoundingMode.HALF_UP);
                result = new Sub(per, sub.estimated, new ArrayList<>(sub.missing));
            }
        } else {
            UnitCost c = src.directCost(ingredientId, date);
            result = c == null ? new Sub(null, false, List.of())
                    : new Sub(c.perBase(), c.estimated(), List.of());
        }
        cache.put(key, result);
        return result;
    }

    /** Quantity actually needed including process loss: qty / (1 - waste%). */
    public static BigDecimal grossQty(Line line) {
        BigDecimal w = line.wastePct() == null ? BigDecimal.ZERO : line.wastePct();
        if (w.signum() == 0) return line.quantityBase();
        return line.quantityBase().multiply(HUNDRED).divide(HUNDRED.subtract(w), 8, RoundingMode.HALF_UP);
    }

    /** Cost per base unit from a purchase line: line total / base quantity (6 decimals). */
    public static BigDecimal costPerBase(BigDecimal lineTotal, BigDecimal baseQuantity) {
        if (baseQuantity == null || baseQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Quantity must be more than zero");
        }
        return lineTotal.divide(baseQuantity, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Would giving {@code prepared} a recipe that uses {@code lineIngredients} create a loop?
     * {@code childrenOf} returns the ingredient ids used by the current recipe of a PREPARED ingredient (empty otherwise).
     * Returns the loop as a list of ids starting and ending with {@code prepared}, or an empty list when safe.
     */
    public static List<Long> findCycle(long prepared, Set<Long> lineIngredients,
                                       java.util.function.Function<Long, Set<Long>> childrenOf) {
        for (Long start : lineIngredients) {
            List<Long> path = new ArrayList<>();
            path.add(prepared);
            if (walk(start, prepared, childrenOf, path, new LinkedHashSet<>())) return path;
        }
        return List.of();
    }

    private static boolean walk(long node, long target, java.util.function.Function<Long, Set<Long>> childrenOf,
                                List<Long> path, Set<Long> seen) {
        path.add(node);
        if (node == target) return true;
        if (seen.add(node)) {
            for (Long c : childrenOf.apply(node)) {
                if (walk(c, target, childrenOf, path, seen)) return true;
            }
        }
        path.remove(path.size() - 1);
        return false;
    }
}
