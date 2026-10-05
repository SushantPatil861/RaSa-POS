package com.cafepos.platform;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class BusinessClock {

    private final JdbcClient jdbc;
    private final CafeProps props;

    public BusinessClock(JdbcClient jdbc, CafeProps props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public ZoneId zone() {
        return ZoneId.of(props.timezone());
    }

    /** The current trading day (respects the configured day-rollover hour). */
    public LocalDate today() {
        int rollover = jdbc.sql("select day_rollover_hour from business where id = :b")
                .param("b", props.businessId()).query(Integer.class).single();
        return BusinessDay.of(Instant.now(), zone(), rollover);
    }

    public LocalDate dataStartDate() {
        return jdbc.sql("select data_start_date from business where id = :b")
                .param("b", props.businessId()).query(LocalDate.class).single();
    }
}
