package com.cafepos.identity;

import com.cafepos.platform.ApiException;
import com.cafepos.platform.CafeProps;
import com.cafepos.security.AuthUser;
import com.cafepos.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.HexFormat;

@Service
public class AuthService {

    public record LoginResult(String accessToken, int expiresInSeconds, AuthUser user, String refreshToken) {}

    public record UserRow(long id, String name, String email, String passwordHash, boolean active,
                           long roleId, String roleCode) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final CafeProps props;

    public AuthService(JdbcClient jdbc, PasswordEncoder encoder, JwtService jwt, CafeProps props) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
    }

    @Transactional
    public LoginResult login(String email, String password) {
        UserRow u = jdbc.sql("""
                select u.id, u.name, u.email, u.password_hash, u.active, u.role_id, r.code as role_code
                from users u join roles r on r.id = u.role_id
                where u.business_id = :b and lower(u.email) = lower(:e)
                """)
                .param("b", props.businessId()).param("e", email)
                .query(UserRow.class).optional().orElse(null);
        // Same error for unknown user / wrong password / inactive: do not leak which one.
        if (u == null || !u.active() || !encoder.matches(password, u.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        jdbc.sql("update users set last_login_at = now() where id = :id").param("id", u.id()).update();
        AuthUser au = toAuthUser(u);
        return new LoginResult(jwt.issue(au), jwt.accessTtlSeconds(), au, newRefreshToken(u.id()));
    }

    @Transactional
    public LoginResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
        String hash = sha256(refreshToken);
        Long userId = jdbc.sql("""
                select user_id from refresh_tokens
                where token_hash = :h and revoked = false and expires_at > now()
                """).param("h", hash).query(Long.class).optional().orElse(null);
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again");
        }
        jdbc.sql("update refresh_tokens set revoked = true where token_hash = :h").param("h", hash).update();
        UserRow u = jdbc.sql("""
                select u.id, u.name, u.email, u.password_hash, u.active, u.role_id, r.code as role_code
                from users u join roles r on r.id = u.role_id where u.id = :id
                """).param("id", userId).query(UserRow.class).single();
        if (!u.active()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Account disabled");
        }
        AuthUser au = toAuthUser(u);
        return new LoginResult(jwt.issue(au), jwt.accessTtlSeconds(), au, newRefreshToken(u.id()));
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            jdbc.sql("update refresh_tokens set revoked = true where token_hash = :h")
                    .param("h", sha256(refreshToken)).update();
        }
    }

    private AuthUser toAuthUser(UserRow u) {
        List<String> perms = jdbc.sql("select permission_code from role_permissions where role_id = :r")
                .param("r", u.roleId()).query(String.class).list();
        return new AuthUser(u.id(), u.name(), u.email(), u.roleCode(), new HashSet<>(perms));
    }

    private String newRefreshToken(long userId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("""
                insert into refresh_tokens (user_id, token_hash, expires_at)
                values (:u, :h, :e)
                """)
                .param("u", userId).param("h", sha256(token))
                .param("e", OffsetDateTime.now().plusDays(props.security().refreshTokenDays()))
                .update();
        return token;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
