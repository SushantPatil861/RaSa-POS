package com.cafepos.security;

import com.cafepos.platform.CafeProps;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

@Component
public class JwtService {

    private final SecretKey key;
    private final int accessMinutes;

    public JwtService(CafeProps props) {
        byte[] secret = props.security().jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("cafe.security.jwt-secret must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.accessMinutes = props.security().accessTokenMinutes();
    }

    public int accessTtlSeconds() {
        return accessMinutes * 60;
    }

    public String issue(AuthUser u) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(u.id()))
                .claim("name", u.name())
                .claim("email", u.email())
                .claim("role", u.roleCode())
                .claim("perms", List.copyOf(u.permissions()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds())))
                .signWith(key)
                .compact();
    }

    @SuppressWarnings("unchecked")
    public Optional<AuthUser> parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            List<String> perms = c.get("perms", List.class);
            return Optional.of(new AuthUser(Long.parseLong(c.getSubject()), c.get("name", String.class),
                    c.get("email", String.class), c.get("role", String.class),
                    new HashSet<>(perms == null ? List.of() : perms)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
