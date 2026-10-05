package com.cafepos.costing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Framework-free unit conversion. Base units are g, ml and pcs. Conversion works only INSIDE a dimension
 * (g/kg, ml/l, pcs). Weight <-> volume (litre <-> kg) is never automatic: it needs a pack spec with the real weight.
 */
public final class UnitConverter {

    public enum Dimension { MASS, VOLUME, COUNT }

    public static final class UnitConversionException extends RuntimeException {
        public UnitConversionException(String message) {
            super(message);
        }
    }

    private record U(String code, String label, Dimension dimension, String base, BigDecimal toBase) {}

    private static final Map<String, U> UNITS = Map.of(
            "g", new U("g", "grams", Dimension.MASS, "g", BigDecimal.ONE),
            "kg", new U("kg", "kilograms", Dimension.MASS, "g", new BigDecimal("1000")),
            "ml", new U("ml", "millilitres", Dimension.VOLUME, "ml", BigDecimal.ONE),
            "l", new U("l", "litres", Dimension.VOLUME, "ml", new BigDecimal("1000")),
            "pcs", new U("pcs", "pieces", Dimension.COUNT, "pcs", BigDecimal.ONE));

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("gram", "g"), Map.entry("grams", "g"), Map.entry("gm", "g"), Map.entry("gms", "g"),
            Map.entry("kgs", "kg"), Map.entry("kilo", "kg"), Map.entry("kilogram", "kg"),
            Map.entry("litre", "l"), Map.entry("liter", "l"), Map.entry("ltr", "l"), Map.entry("lt", "l"),
            Map.entry("millilitre", "ml"), Map.entry("milliliter", "ml"),
            Map.entry("pc", "pcs"), Map.entry("piece", "pcs"), Map.entry("pieces", "pcs"), Map.entry("nos", "pcs"),
            Map.entry("unit", "pcs"), Map.entry("units", "pcs"));

    private UnitConverter() {}

    /** Canonical code (g, kg, ml, l, pcs) or null when the text is not a known unit. */
    public static String normalise(String unit) {
        if (unit == null) return null;
        String u = unit.trim().toLowerCase();
        if (UNITS.containsKey(u)) return u;
        return ALIASES.get(u);
    }

    public static boolean isKnown(String unit) {
        return normalise(unit) != null;
    }

    public static Dimension dimensionOf(String unit) {
        String n = normalise(unit);
        if (n == null) throw new UnitConversionException("Unknown unit: " + unit);
        return UNITS.get(n).dimension();
    }

    public static String baseUnitOf(String unit) {
        String n = normalise(unit);
        if (n == null) throw new UnitConversionException("Unknown unit: " + unit);
        return UNITS.get(n).base();
    }

    /** Converts a quantity to the base unit of its own dimension (kg -> g, l -> ml). */
    public static BigDecimal toBase(BigDecimal qty, String unit) {
        String n = normalise(unit);
        if (n == null) throw new UnitConversionException("Unknown unit: " + unit);
        return qty.multiply(UNITS.get(n).toBase());
    }

    /** Converts between two units of the SAME dimension; throws a plain-English error otherwise. */
    public static BigDecimal convert(BigDecimal qty, String from, String to) {
        String f = normalise(from);
        String t = normalise(to);
        if (f == null) throw new UnitConversionException("Unknown unit: " + from);
        if (t == null) throw new UnitConversionException("Unknown unit: " + to);
        U uf = UNITS.get(f);
        U ut = UNITS.get(t);
        if (uf.dimension() != ut.dimension()) {
            throw new UnitConversionException("Cannot convert " + uf.label() + " to " + ut.label()
                    + " automatically (" + describe(uf.dimension()) + " vs " + describe(ut.dimension())
                    + "). Add a pack spec with the real quantity per pack instead.");
        }
        return qty.multiply(uf.toBase()).divide(ut.toBase(), 6, RoundingMode.HALF_UP);
    }

    /** Quantity typed in any unit -> the ingredient's base unit (g, ml or pcs). */
    public static BigDecimal toIngredientBase(BigDecimal qty, String unit, String ingredientBaseUnit) {
        if (unit == null || unit.isBlank()) return qty;
        return convert(qty, unit, ingredientBaseUnit);
    }

    private static String describe(Dimension d) {
        return switch (d) {
            case MASS -> "weight";
            case VOLUME -> "volume";
            case COUNT -> "count";
        };
    }
}
