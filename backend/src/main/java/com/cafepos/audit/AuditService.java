package com.cafepos.audit;

import com.cafepos.platform.CafeProps;
import com.cafepos.security.AuthUser;
import com.cafepos.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Writes the append-only audit trail. Called in the SAME transaction as the change it describes,
 * so a financial change can never be saved without its audit row.
 * before/after are JSON text (built in SQL with to_jsonb) so every column is captured.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final CafeProps props;

    public AuditService(JdbcClient jdbc, CafeProps props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public void record(String entityType, Long entityId, String action, LocalDate txnDate,
                       String beforeJson, String afterJson, String reason, String source) {
        AuthUser u = CurrentUser.get();
        jdbc.sql("""
                insert into audit_log (business_id, entity_type, entity_id, action, txn_date,
                                       before_value, after_value, reason, source, user_id, user_name)
                values (:b, :et, :eid, :act, :td, cast(:before as jsonb), cast(:after as jsonb),
                        :reason, :src, :uid, :uname)
                """)
                .param("b", props.businessId())
                .param("et", entityType)
                .param("eid", entityId)
                .param("act", action)
                .param("td", txnDate)
                .param("before", beforeJson)
                .param("after", afterJson)
                .param("reason", reason)
                .param("src", source)
                .param("uid", u.id())
                .param("uname", u.name())
                .update();
    }
}
