package com.cafepos.costing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnitConverterTest {

    @Test
    void kgToGramsAndLitresToMillilitres() {
        assertThat(UnitConverter.convert(new BigDecimal("1.4"), "kg", "g")).isEqualByComparingTo("1400");
        assertThat(UnitConverter.convert(new BigDecimal("2"), "l", "ml")).isEqualByComparingTo("2000");
        assertThat(UnitConverter.convert(new BigDecimal("500"), "g", "kg")).isEqualByComparingTo("0.5");
        assertThat(UnitConverter.toIngredientBase(new BigDecimal("3"), "pcs", "pcs")).isEqualByComparingTo("3");
    }

    @Test
    void litreToKgIsBlockedWithClearMessage() {
        assertThatThrownBy(() -> UnitConverter.convert(BigDecimal.ONE, "l", "kg"))
                .isInstanceOf(UnitConverter.UnitConversionException.class)
                .hasMessageContaining("Cannot convert litres to kilograms automatically")
                .hasMessageContaining("pack spec");
        assertThatThrownBy(() -> UnitConverter.toIngredientBase(BigDecimal.ONE, "ml", "g"))
                .isInstanceOf(UnitConverter.UnitConversionException.class);
    }

    @Test
    void aliasesAndUnknownUnits() {
        assertThat(UnitConverter.normalise("Litre")).isEqualTo("l");
        assertThat(UnitConverter.normalise("KGS")).isEqualTo("kg");
        assertThat(UnitConverter.normalise("scoop")).isNull();
        assertThatThrownBy(() -> UnitConverter.toBase(BigDecimal.ONE, "scoop"))
                .isInstanceOf(UnitConverter.UnitConversionException.class);
    }
}
