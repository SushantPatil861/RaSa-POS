package com.cafepos.identity;

import com.cafepos.audit.AuditService;
import com.cafepos.platform.ApiException;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    public record UserDto(long id, String name, String email, String mobile, String roleCode, String roleName,
                          boolean active) {}

    public record RoleDto(long id, String code, String name) {}

    private static final String SELECT = """
            select u.id, u.name, u.email, u.mobile, r.code as role_code, r.name as role_name, u.active
            from users u join roles r on r.id = u.role_id
            """;

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final CafeProps props;

    public UserService(JdbcClient jdbc, PasswordEncoder encoder, AuditService audit, CafeProps props) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.props = props;
    }

    public List<UserDto> list() {
        return jdbc.sql(SELECT + " where u.business_id = :b order by u.name")
                .param("b", props.businessId()).query(UserDto.class).list();
    }

    public List<RoleDto> roles() {
        return jdbc.sql("select id, code, name from roles where business_id = :b order by id")
                .param("b", props.businessId()).query(RoleDto.class).list();
    }

    @Transactional
    public UserDto create(String name, String email, String mobile, String roleCode, String password) {
        validatePassword(password);
        long roleId = roleId(roleCode);
        long id = jdbc.sql("""
                insert into users (business_id, name, email, mobile, password_hash, role_id)
                values (:b, :n, :e, :m, :p, :r) returning id
                """)
                .param("b", props.businessId()).param("n", name.trim()).param("e", email.trim())
                .param("m", mobile).param("p", encoder.encode(password)).param("r", roleId)
                .query(Long.class).single();
        audit.record("USER", id, "CREATE", null, null,
                "{\"email\":\"" + escape(email) + "\",\"role\":\"" + escape(roleCode) + "\"}", null, "MANUAL");
        return get(id);
    }

    @Transactional
    public UserDto update(long id, String name, String mobile, String roleCode, Boolean active) {
        UserDto before = get(id);
        if (id == CurrentUser.id() && Boolean.FALSE.equals(active)) {
            throw ApiException.badRequest("You cannot deactivate your own account");
        }
        long roleId = roleCode == null ? roleId(before.roleCode()) : roleId(roleCode);
        jdbc.sql("""
                update users set name = coalesce(:n, name), mobile = coalesce(:m, mobile), role_id = :r,
                                 active = coalesce(:a, active), updated_at = now()
                where id = :id and business_id = :b
                """)
                .param("n", name).param("m", mobile).param("r", roleId).param("a", active)
                .param("id", id).param("b", props.businessId()).update();
        audit.record("USER", id, "UPDATE", null,
                "{\"role\":\"" + escape(before.roleCode()) + "\",\"active\":" + before.active() + "}",
                "{\"role\":\"" + escape(roleCode == null ? before.roleCode() : roleCode) + "\",\"active\":"
                        + (active == null ? before.active() : active) + "}", null, "MANUAL");
        return get(id);
    }

    @Transactional
    public void resetPassword(long id, String newPassword) {
        validatePassword(newPassword);
        get(id);
        jdbc.sql("update users set password_hash = :p, updated_at = now() where id = :id and business_id = :b")
                .param("p", encoder.encode(newPassword)).param("id", id).param("b", props.businessId()).update();
        jdbc.sql("update refresh_tokens set revoked = true where user_id = :id").param("id", id).update();
        audit.record("USER", id, "PASSWORD_RESET", null, null, null, null, "MANUAL");
    }

    @Transactional
    public void changeOwnPassword(String current, String newPassword) {
        validatePassword(newPassword);
        long id = CurrentUser.id();
        String hash = jdbc.sql("select password_hash from users where id = :id").param("id", id)
                .query(String.class).single();
        if (!encoder.matches(current, hash)) {
            throw ApiException.badRequest("Current password is incorrect");
        }
        jdbc.sql("update users set password_hash = :p, updated_at = now() where id = :id")
                .param("p", encoder.encode(newPassword)).param("id", id).update();
        audit.record("USER", id, "PASSWORD_CHANGE", null, null, null, null, "MANUAL");
    }

    private UserDto get(long id) {
        return jdbc.sql(SELECT + " where u.id = :id and u.business_id = :b")
                .param("id", id).param("b", props.businessId()).query(UserDto.class).optional()
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    private long roleId(String code) {
        return jdbc.sql("select id from roles where business_id = :b and code = :c")
                .param("b", props.businessId()).param("c", code).query(Long.class).optional()
                .orElseThrow(() -> ApiException.badRequest("Unknown role: " + code));
    }

    private static void validatePassword(String p) {
        if (p == null || p.length() < 8) {
            throw ApiException.badRequest("Password must be at least 8 characters");
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
