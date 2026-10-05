package com.cafepos.audit;

import com.cafepos.platform.CafeProps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    public record AuditEntry(long id, String entityType, Long entityId, String action, LocalDate txnDate,
                             String beforeValue, String afterValue, String reason, String source,
                             Long userId, String userName, OffsetDateTime at) {}

    private final JdbcClient jdbc;
    private final CafeProps props;

    public AuditController(JdbcClient jdbc, CafeProps props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** History of one record (the "History" drawer), or the latest entries across the business. */
    @GetMapping
    @PreAuthorize("hasAuthority('audit.view')")
    public List<AuditEntry> list(@RequestParam(required = false) String entityType,
                                 @RequestParam(required = false) Long entityId,
                                 @RequestParam(defaultValue = "100") int limit) {
        return jdbc.sql("""
                select id, entity_type, entity_id, action, txn_date, before_value::text as before_value,
                       after_value::text as after_value, reason, source, user_id, user_name, at
                from audit_log
                where business_id = :b
                  and (cast(:et as text) is null or entity_type = :et)
                  and (cast(:eid as bigint) is null or entity_id = :eid)
                order by at desc, id desc
                limit :lim
                """)
                .param("b", props.businessId()).param("et", entityType).param("eid", entityId)
                .param("lim", Math.min(Math.max(limit, 1), 500))
                .query(AuditEntry.class).list();
    }
}
