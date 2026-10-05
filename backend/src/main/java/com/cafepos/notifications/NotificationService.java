package com.cafepos.notifications;

import com.cafepos.platform.CafeProps;
import com.cafepos.security.AuthUser;
import com.cafepos.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * In-app notifications. A notification with user_id null goes to everybody who holds perm_code (or to every user when
 * perm_code is null too). Read state is kept per user in notification_reads, so one person reading never hides it
 * from the others.
 */
@Service
public class NotificationService {

    public record NotificationDto(long id, String type, String title, String body, String link,
                                  OffsetDateTime createdAt, boolean read) {}

    private final JdbcClient jdbc;
    private final CafeProps props;

    public NotificationService(JdbcClient jdbc, CafeProps props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Publishes to everybody holding permCode (null = every user). Needs no logged-in user (webhooks call it). */
    @Transactional
    public long publish(String type, String title, String body, String link, String permCode) {
        return jdbc.sql("""
                insert into notifications (business_id, user_id, perm_code, type, title, body, link)
                values (:b, null, :p, :t, :ti, :bo, :l) returning id
                """).param("b", props.businessId()).param("p", permCode).param("t", type).param("ti", title)
                .param("bo", body).param("l", link).query(Long.class).single();
    }

    /** Publishes to one user only. */
    @Transactional
    public long publishToUser(long userId, String type, String title, String body, String link) {
        return jdbc.sql("""
                insert into notifications (business_id, user_id, perm_code, type, title, body, link)
                values (:b, :u, null, :t, :ti, :bo, :l) returning id
                """).param("b", props.businessId()).param("u", userId).param("t", type).param("ti", title)
                .param("bo", body).param("l", link).query(Long.class).single();
    }

    public List<NotificationDto> mine(boolean unreadOnly, int limit) {
        AuthUser u = CurrentUser.get();
        return jdbc.sql("""
                select n.id, n.type, n.title, n.body, n.link, n.created_at, (r.user_id is not null) as read
                from notifications n
                left join notification_reads r on r.notification_id = n.id and r.user_id = :u
                where n.business_id = :b
                  and (n.user_id = :u or (n.user_id is null and (n.perm_code is null or n.perm_code in (:perms))))
                  and (not :unread or r.user_id is null)
                order by n.id desc limit :lim
                """).param("b", props.businessId()).param("u", u.id()).param("perms", perms(u))
                .param("unread", unreadOnly).param("lim", Math.min(Math.max(limit, 1), 200))
                .query(NotificationDto.class).list();
    }

    public long unreadCount() {
        AuthUser u = CurrentUser.get();
        return jdbc.sql("""
                select count(*) from notifications n
                where n.business_id = :b
                  and (n.user_id = :u or (n.user_id is null and (n.perm_code is null or n.perm_code in (:perms))))
                  and not exists (select 1 from notification_reads r where r.notification_id = n.id and r.user_id = :u)
                """).param("b", props.businessId()).param("u", u.id()).param("perms", perms(u))
                .query(Long.class).single();
    }

    @Transactional
    public void markRead(long id) {
        long u = CurrentUser.id();
        jdbc.sql("""
                insert into notification_reads (notification_id, user_id)
                select n.id, :u from notifications n where n.id = :id and n.business_id = :b
                on conflict do nothing
                """).param("u", u).param("id", id).param("b", props.businessId()).update();
    }

    @Transactional
    public void markAllRead() {
        AuthUser u = CurrentUser.get();
        jdbc.sql("""
                insert into notification_reads (notification_id, user_id)
                select n.id, :u from notifications n
                where n.business_id = :b
                  and (n.user_id = :u or (n.user_id is null and (n.perm_code is null or n.perm_code in (:perms))))
                on conflict do nothing
                """).param("u", u.id()).param("b", props.businessId()).param("perms", perms(u)).update();
    }

    /** Never empty: "in ()" is invalid SQL. */
    private static List<String> perms(AuthUser u) {
        List<String> p = new ArrayList<>(u.permissions());
        if (p.isEmpty()) p.add("-");
        return p;
    }
}
