package com.cafepos.settings;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Business profile and effective-dated tax rules.
 * Tax rules are never edited: a rate change is a NEW rule with an effective date, so old sales keep the rate
 * that applied when they happened. Data-start date and day-rollover hour are fixed here on purpose
 * (changing them would silently move history).
 */
@Service
public class SettingsService {

    public record BusinessDto(String name, String address, String phone, String whatsappNumber, String gstin,
                              LocalTime openingTime, LocalTime closingTime, int dayRolloverHour,
                              LocalDate dataStartDate, String currency, String timezone) {}

    public record BusinessRequest(String name, String address, String phone, String whatsappNumber, String gstin,
                                  LocalTime openingTime, LocalTime closingTime) {}

    public record TaxRuleDto(long id, String taxType, String scope, BigDecimal rate, Boolean itcEligible,
                             LocalDate effectiveFrom, String notes) {}

    public record TaxRuleRequest(String scope, BigDecimal rate, Boolean itcEligible, LocalDate effectiveFrom,
                                 String notes, String reason) {}

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final AuditService audit;

    public SettingsService(JdbcClient jdbc, CafeProps props, AuditService audit) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
    }

    public BusinessDto business() {
        return jdbc.sql("""
                select name, address, phone, whatsapp_number, gstin, opening_time, closing_time,
                       day_rollover_hour, data_start_date, currency, timezone
                from business where id = :b
                """).param("b", props.businessId()).query(BusinessDto.class).optional()
                .orElseThrow(() -> ApiException.notFound("Business"));
    }

    @Transactional
    public BusinessDto updateBusiness(BusinessRequest r) {
        CurrentUser.require("settings.manage");
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Business name is required");
        String before = jdbc.sql("select to_jsonb(b)::text from business b where id = :b")
                .param("b", props.businessId()).query(String.class).single();
        jdbc.sql("""
                update business set name = :n, address = :a, phone = :p, whatsapp_number = :w, gstin = :g,
                       opening_time = :o, closing_time = :c where id = :b
                """).param("n", r.name().trim()).param("a", r.address()).param("p", r.phone())
                .param("w", r.whatsappNumber()).param("g", r.gstin()).param("o", r.openingTime())
                .param("c", r.closingTime()).param("b", props.businessId()).update();
        String after = jdbc.sql("select to_jsonb(b)::text from business b where id = :b")
                .param("b", props.businessId()).query(String.class).single();
        audit.record("BUSINESS", props.businessId(), "UPDATE", null, before, after, "Profile updated", "MANUAL");
        return business();
    }

    public List<TaxRuleDto> taxRules() {
        return jdbc.sql("""
                select id, tax_type, scope, rate, itc_eligible, effective_from, notes
                from tax_rules where business_id = :b order by effective_from desc, id desc
                """).param("b", props.businessId()).query(TaxRuleDto.class).list();
    }

    @Transactional
    public TaxRuleDto addTaxRule(TaxRuleRequest r) {
        CurrentUser.require("settings.manage");
        if (r.rate() == null || r.rate().signum() < 0 || r.rate().compareTo(new BigDecimal("100")) > 0) {
            throw ApiException.badRequest("Rate must be between 0 and 100");
        }
        if (r.effectiveFrom() == null) throw ApiException.badRequest("An effective date is required");
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason is required");
        long id;
        try {
            id = jdbc.sql("""
                    insert into tax_rules (business_id, tax_type, scope, rate, itc_eligible, effective_from, notes)
                    values (:b, 'GST', :s, :r, :itc, :e, :n) returning id
                    """).param("b", props.businessId())
                    .param("s", r.scope() == null || r.scope().isBlank() ? "ALL" : r.scope().trim())
                    .param("r", r.rate()).param("itc", r.itcEligible()).param("e", r.effectiveFrom())
                    .param("n", r.notes()).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A tax rule for this scope already starts on " + r.effectiveFrom());
        }
        String after = jdbc.sql("select to_jsonb(t)::text from tax_rules t where id = :id").param("id", id)
                .query(String.class).single();
        audit.record("TAX_RULE", id, "CREATE", r.effectiveFrom(), null, after, r.reason(), "MANUAL");
        return jdbc.sql("select id, tax_type, scope, rate, itc_eligible, effective_from, notes from tax_rules where id = :id")
                .param("id", id).query(TaxRuleDto.class).single();
    }
}
