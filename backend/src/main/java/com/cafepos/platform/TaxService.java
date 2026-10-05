package com.cafepos.platform;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** Looks up the effective-dated GST rate. Rates are data (tax_rules), never constants in code. */
@Service
public class TaxService {

    private final JdbcClient jdbc;
    private final CafeProps props;

    public TaxService(JdbcClient jdbc, CafeProps props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Rate (percent) in force on a date for a category. A category-specific rule beats the 'ALL' rule. */
    public Optional<BigDecimal> rateOn(LocalDate date, String category) {
        return jdbc.sql("""
                select rate from tax_rules
                where business_id = :b and tax_type = 'GST' and effective_from <= :d
                  and (scope = 'ALL' or (cast(:cat as text) is not null and lower(scope) = lower(:cat)))
                order by case when scope = 'ALL' then 1 else 0 end, effective_from desc
                limit 1
                """).param("b", props.businessId()).param("d", date).param("cat", category)
                .query(BigDecimal.class).optional();
    }
}
