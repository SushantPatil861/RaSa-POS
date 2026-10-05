package com.cafepos;

import com.cafepos.platform.CafeProps;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.TimeZone;

@SpringBootApplication
@EnableConfigurationProperties(CafeProps.class)
public class CafeApplication {
    public static void main(String[] args) {
        // All instants are handled and stored as UTC (timestamptz). Calendar dates ("today", business day) are derived in the
        // business time zone (cafe.timezone) or, in the browser, in the user's own zone. Pinning the JVM to UTC also avoids the
        // legacy "Asia/Calcutta" id that newer PostgreSQL images reject as a session TimeZone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(CafeApplication.class, args);
    }
}
