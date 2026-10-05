package com.cafepos.costing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CostResolverTest {

    static final LocalDate D = LocalDate.of(2026, 6, 15);

    /** Simple in-memory source. Costs are keyed by ingredient id; one price for all dates unless 'from' is given. */
    static class MapSource implements CostResolver.Source {
        final Map<Long, CostResolver.Version> variantRecipes = new HashMap<>();
        final Map<Long, CostResolver.Version> prepRecipes = new HashMap<>();
        final Map<Long, CostResolver.UnitCost> costs = new HashMap<>();
        final Map<Long, LocalDate> costFrom = new HashMap<>();
        final Map<Long, String> names = new HashMap<>();
        final Set<Long> prepared = new java.util.HashSet<>();

        public CostResolver.Version recipeForVariant(long id, LocalDate d) { return variantRecipes.get(id); }
        public CostResolver.Version recipeForIngredient(long id, LocalDate d) { return prepRecipes.get(id); }
        public CostResolver.UnitCost directCost(long id, LocalDate d) {
            LocalDate from = costFrom.get(id);
            if (from != null && d.isBefore(from)) return null;
            return costs.get(id);
        }
        public String ingredientName(long id) { return names.getOrDefault(id, "ingredient#" + id); }
        public boolean isPrepared(long id) { return prepared.contains(id); }
    }

    static BigDecimal bd(String s) { return new BigDecimal(s); }

    static CostResolver.Line line(long ing, String qty, String waste) {
        return new CostResolver.Line(ing, bd(qty), bd(waste));
    }

    @Test
    void ownerGoldenExample_tub1400RupeesFor2800Grams_scoop70gCosts35() {
        BigDecimal perGram = CostResolver.costPerBase(bd("1400"), bd("2800"));
        assertThat(perGram).isEqualByComparingTo("0.5");
        MapSource s = new MapSource();
        s.names.put(1L, "Pista ice cream");
        s.costs.put(1L, new CostResolver.UnitCost(perGram, false));
        s.variantRecipes.put(10L, new CostResolver.Version(BigDecimal.ONE, List.of(line(1, "70", "0"))));
        CostResolver.Result r = new CostResolver(s).portionCost(10L, D);
        assertThat(r.status()).isEqualTo(CostResolver.COMPLETE);
        assertThat(r.cost()).isEqualByComparingTo("35");
        assertThat(r.missing()).isEmpty();
    }

    @Test
    void tub1400gCosting1400rupeesIsOneRupeePerGram_70gScoopCosts70() {
        BigDecimal perGram = CostResolver.costPerBase(bd("1400"), bd("1400"));
        assertThat(perGram).isEqualByComparingTo("1");
        MapSource s = new MapSource();
        s.costs.put(1L, new CostResolver.UnitCost(perGram, false));
        s.variantRecipes.put(10L, new CostResolver.Version(BigDecimal.ONE, List.of(line(1, "70", "0"))));
        assertThat(new CostResolver(s).portionCost(10L, D).cost()).isEqualByComparingTo("70");
    }

    @Test
    void missingIngredientCostIsPartialNeverZeroFilled() {
        MapSource s = new MapSource();
        s.names.put(1L, "Milk");
        s.names.put(2L, "Cocoa");
        s.costs.put(1L, new CostResolver.UnitCost(bd("0.06"), false));
        s.variantRecipes.put(10L, new CostResolver.Version(BigDecimal.ONE, List.of(line(1, "200", "0"), line(2, "20", "0"))));
        CostResolver.Result r = new CostResolver(s).portionCost(10L, D);
        assertThat(r.status()).isEqualTo(CostResolver.PARTIAL);
        assertThat(r.cost()).isEqualByComparingTo("12");
        assertThat(r.missing()).containsExactly("Cocoa");
    }

    @Test
    void nothingCostedIsUnknownWithNullCost() {
        MapSource s = new MapSource();
        s.names.put(2L, "Cocoa");
        s.variantRecipes.put(10L, new CostResolver.Version(BigDecimal.ONE, List.of(line(2, "20", "0"))));
        CostResolver.Result r = new CostResolver(s).portionCost(10L, D);
        assertThat(r.status()).isEqualTo(CostResolver.UNKNOWN);
        assertThat(r.cost()).isNull();
        assertThat(r.missing()).containsExactly("Cocoa");
        assertThat(r.noRecipe()).isFalse();
    }

    @Test
    void noRecipeIsFlagged() {
        CostResolver.Result r = new CostResolver(new MapSource()).portionCost(99L, D);
        assertThat(r.noRecipe()).isTrue();
        assertThat(r.cost()).isNull();
    }

    @Test
    void preparedIngredientResolvedRecursivelyWithYield() {
        // Sauce: 500 g sugar @0.04 + 500 ml cream @0.2 => 120 for a 900 g batch => 0.13333/g. Waffle uses 30 g sauce.
        MapSource s = new MapSource();
        s.names.put(1L, "Sugar"); s.names.put(2L, "Cream"); s.names.put(3L, "Sauce"); s.names.put(4L, "Batter");
        s.prepared.add(3L);
        s.costs.put(1L, new CostResolver.UnitCost(bd("0.04"), false));
        s.costs.put(2L, new CostResolver.UnitCost(bd("0.2"), true));
        s.costs.put(4L, new CostResolver.UnitCost(bd("0.1"), false));
        s.prepRecipes.put(3L, new CostResolver.Version(bd("900"), List.of(line(1, "500", "0"), line(2, "500", "0"))));
        s.variantRecipes.put(10L, new CostResolver.Version(BigDecimal.ONE, List.of(line(3, "30", "0"), line(4, "100", "0"))));
        CostResolver.Result r = new CostResolver(s).portionCost(10L, D);
        assertThat(r.status()).isEqualTo(CostResolver.COMPLETE);
        assertThat(r.estimated()).isTrue();   // cream cost was an estimate
        assertThat(r.cost().doubleValue()).isCloseTo(14.0, within(0.001)); // 30 * 0.133333 = 4.0 (rounded) + 10
    }

    @Test
    void missingNestedCostPropagatesAsPartial() {
        MapSource s = new MapSource();
        s.names.put(1L, "Sugar"); s.names.put(2L, "Cream"); s.names.put(3L, "Sauce");
        s.prepared.add(3L);
        s.costs.put(1L, new CostResolver.UnitCost(bd("0.04"), false));
        s.prepRecipes.put(3L, new CostResolver.Version(bd("900"), List.of(line(1, "500", "0"), line(2, "500", "0"))));
        s.variantRecipes.put(10L, new CostResolver.Version(BigDecimal.ONE, List.of(line(3, "30", "0"))));
        CostResolver.Result r = new CostResolver(s).portionCost(10L, D);
        assertThat(r.status()).isEqualTo(CostResolver.PARTIAL);
        assertThat(r.missing()).containsExactly("Cream");
        assertThat(r.cost()).isNotNull();
    }

    @Test
    void preparedIngredientWithoutRecipeFallsBackToItsOwnCost() {
        MapSource s = new MapSource();
        s.prepared.add(3L);
        s.costs.put(3L, new CostResolver.UnitCost(bd("0.5"), false));
        s.variantRecipes.put(10L, new CostResolver.Version(BigDecimal.ONE, List.of(line(3, "10", "0"))));
        assertThat(new CostResolver(s).portionCost(10L, D).cost()).isEqualByComparingTo("5");
    }

    @Test
    void wastePercentIncreasesQuantity() {
        MapSource s = new MapSource();
        s.costs.put(1L, new CostResolver.UnitCost(bd("1"), false));
        s.variantRecipes.put(10L, new CostResolver.Version(BigDecimal.ONE, List.of(line(1, "90", "10"))));
        assertThat(new CostResolver(s).portionCost(10L, D).cost()).isEqualByComparingTo("100");
    }

    @Test
    void outputQuantityDividesCost() {
        MapSource s = new MapSource();
        s.costs.put(1L, new CostResolver.UnitCost(bd("2"), false));
        s.variantRecipes.put(10L, new CostResolver.Version(bd("4"), List.of(line(1, "100", "0"))));
        assertThat(new CostResolver(s).portionCost(10L, D).cost()).isEqualByComparingTo("50");
    }

    @Test
    void costIsResolvedAsOfDate() {
        MapSource s = new MapSource();
        s.costs.put(1L, new CostResolver.UnitCost(bd("1"), false));
        s.costFrom.put(1L, LocalDate.of(2026, 7, 1));
        s.variantRecipes.put(10L, new CostResolver.Version(BigDecimal.ONE, List.of(line(1, "10", "0"))));
        assertThat(new CostResolver(s).portionCost(10L, LocalDate.of(2026, 6, 30)).status()).isEqualTo(CostResolver.UNKNOWN);
        assertThat(new CostResolver(s).portionCost(10L, LocalDate.of(2026, 7, 1)).status()).isEqualTo(CostResolver.COMPLETE);
    }

    @Test
    void cycleIsDetectedWithNames() {
        MapSource s = new MapSource();
        s.names.put(3L, "Sauce"); s.names.put(4L, "Base");
        s.prepared.add(3L); s.prepared.add(4L);
        s.prepRecipes.put(3L, new CostResolver.Version(BigDecimal.ONE, List.of(line(4, "1", "0"))));
        s.prepRecipes.put(4L, new CostResolver.Version(BigDecimal.ONE, List.of(line(3, "1", "0"))));
        s.variantRecipes.put(10L, new CostResolver.Version(BigDecimal.ONE, List.of(line(3, "1", "0"))));
        assertThatThrownBy(() -> new CostResolver(s).portionCost(10L, D))
                .isInstanceOf(CostResolver.CycleException.class)
                .hasMessageContaining("Sauce -> Base -> Sauce");
    }

    @Test
    void findCycleBeforeSaving() {
        Map<Long, Set<Long>> children = Map.of(4L, Set.of(5L), 5L, Set.of(3L));
        List<Long> loop = CostResolver.findCycle(3L, Set.of(4L), id -> children.getOrDefault(id, Set.of()));
        assertThat(loop).containsExactly(3L, 4L, 5L, 3L);
        assertThat(CostResolver.findCycle(3L, Set.of(6L), id -> children.getOrDefault(id, Set.of()))).isEmpty();
    }
}
