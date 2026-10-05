package com.cafepos.platform;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
public class JacksonConfig {

    /** Money and quantities travel as JSON strings so JavaScript never loses precision. */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalAsString() {
        return builder -> builder.serializerByType(BigDecimal.class, ToStringSerializer.instance);
    }
}
