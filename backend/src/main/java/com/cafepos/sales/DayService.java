package com.cafepos.sales;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.BusinessClock;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * One trading day: what was recorded (itemised vs quick total), the difference, and the approval step.
 * The two totals are NEVER summed. When both exist an approver decides which one the reports use.
 */
@Service
public class DayService {

    public record DayRow(String basis, String reviewStatus, String decision, String chosen,
                         BigDecimal itemisedTotal, BigDecimal summaryTotal, BigDecimal difference) {}

    public record ReviewRow(String decision, String reason, BigDecimal itemisedTotal, BigDecimal summaryTotal,
                            String approvedByName, OffsetDateTime approvedAt) {}

    public record DayView(LocalDate date, String calendarType, String basis, String reviewStatus, String chosen,
                          int itemisedCount, BigDecimal itemisedTotal, BigDecimal summaryNetTotal,
                          BigDecimal difference, ReviewRow review) {}

    private static final Set<String> DECISIONS = Set.of("ACCEPT_ITEMISED", "ACCEPT_SUMMARY", "ACCEPT_EXPLAINED");

    private final JdbcClient jdbc;
    private final CafeProps props;
    private final AuditService audit;
    private final BusinessClock clock;

    public DayService(JdbcClient jdbc, CafeProps props, AuditService audit, BusinessClock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
    }

    public DayView view(LocalDate date) {
        DayRow row = jdbc.sql("""
                select basis, review_status, decision, chosen, itemised_total, summary_total, difference
                from v_daily_sales where business_id = :b and txn_date = :d
                """).param("b", props.businessId()).param("d", date).query(DayRow.class).optional().orElse(null);
        String cal = jdbc.sql("select day_type from business_calendar where business_id = :b and cal_date = :d")
                .param("b", props.businessId()).param("d", date).query(String.class).optional().orElse("OPEN");
        int count = jdbc.sql("""
                select count(*) from sales where business_id = :b and txn_date = :d and void_state = 'ACTIVE'
                """).param("b", props.businessId()).param("d", date).query(Integer.class).single();
        ReviewRow review = jdbc.sql("""
                select r.decision, r.reason, r.itemised_total, r.summary_total, u.name as approved_by_name, r.approved_at
                from day_reviews r left join users u on u.id = r.approved_by
                where r.business_id = :b and r.txn_date = :d
                """).param("b", props.businessId()).param("d", date).query(ReviewRow.class).optional().orElse(null);
        if (row == null) {
            return new DayView(date, cal, "NONE", "NOT_APPLICABLE", null, 0, BigDecimal.ZERO, BigDecimal.ZERO, null,
                    review);
        }
        return new DayView(date, cal, row.basis(), row.reviewStatus(), row.chosen(), count, row.itemisedTotal(),
                row.summaryTotal(), row.difference(), review);
    }

    /** The approval step for a day that has both an itemised total and a quick total. */
    @Transactional
    public DayView approve(LocalDate date, String decision, String reason) {
        CurrentUser.require("sales.approve_day_variance");
        if (!DECISIONS.contains(decision)) throw ApiException.badRequest("Unknown decision: " + decision);
        if ("ACCEPT_EXPLAINED".equals(decision) && (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("A reason is required when accepting an explained difference");
        }
        DayView v = view(date);
        if (!"BOTH".equals(v.basis())) {
            throw ApiException.conflict("Nothing to approve: this day does not have both itemised sales and a quick entry");
        }
        String before = v.review() == null ? null
                : "{\"decision\":\"" + v.review().decision() + "\",\"reason\":\"" + esc(v.review().reason()) + "\"}";
        jdbc.sql("""
                insert into day_reviews (business_id, txn_date, decision, reason, itemised_total, summary_total, approved_by)
                values (:b, :d, :dec, :r, :it, :sm, :u)
                on conflict (business_id, txn_date)
                do update set decision = excluded.decision, reason = excluded.reason,
                              itemised_total = excluded.itemised_total, summary_total = excluded.summary_total,
                              approved_by = excluded.approved_by, approved_at = now()
                """)
                .param("b", props.businessId()).param("d", date).param("dec", decision).param("r", reason)
                .param("it", v.itemisedTotal()).param("sm", v.summaryNetTotal()).param("u", CurrentUser.id()).update();
        audit.record("DAY_REVIEW", null, "APPROVE", date, before,
                "{\"decision\":\"" + decision + "\",\"reason\":\"" + esc(reason) + "\",\"itemised\":\""
                        + v.itemisedTotal() + "\",\"summary\":\"" + v.summaryNetTotal() + "\",\"difference\":\""
                        + v.difference() + "\"}", reason, "MANUAL");
        return view(date);
    }

    /** Mark a day OPEN / CLOSED / HOLIDAY so a closed day is not reported as "missing sales". */
    @Transactional
    public DayView setCalendar(LocalDate date, String dayType, String note) {
        CurrentUser.require("sales.edit");
        if (!Set.of("OPEN", "CLOSED", "HOLIDAY").contains(dayType)) {
            throw ApiException.badRequest("Unknown day type: " + dayType);
        }
        if (date.isAfter(clock.today())) throw ApiException.badRequest("Date cannot be in the future");
        jdbc.sql("""
                insert into business_calendar (business_id, cal_date, day_type, note) values (:b, :d, :t, :n)
                on conflict (business_id, cal_date) do update set day_type = excluded.day_type, note = excluded.note
                """).param("b", props.businessId()).param("d", date).param("t", dayType).param("n", note).update();
        audit.record("CALENDAR", null, "SET_DAY_TYPE", date, null, "{\"dayType\":\"" + dayType + "\"}", note, "MANUAL");
        return view(date);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
