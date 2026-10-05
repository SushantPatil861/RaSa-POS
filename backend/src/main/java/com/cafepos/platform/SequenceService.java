package com.cafepos.platform;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Human-readable document numbers (SAL-000001, PUR-000001, ORD-000001). Row-locked, so gap-free per type. */
@Service
public class SequenceService {

    private final JdbcClient jdbc;
    private final CafeProps props;

    public SequenceService(JdbcClient jdbc, CafeProps props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Transactional
    public String next(String prefix, String period) {
        long n = jdbc.sql("""
                insert into document_sequences (business_id, seq_type, period, last_value)
                values (:b, :t, :p, 1)
                on conflict (business_id, seq_type, period)
                do update set last_value = document_sequences.last_value + 1
                returning last_value
                """)
                .param("b", props.businessId()).param("t", prefix).param("p", period == null ? "" : period)
                .query(Long.class).single();
        return prefix + "-" + String.format("%06d", n);
    }
}
