package com.cafepos.identity;

import com.cafepos.platform.CafeProps;
import com.cafepos.security.AuthUser;
import com.cafepos.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private static final String COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/api/v1/auth";

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record TokenResponse(String accessToken, int expiresInSeconds, MeResponse user) {}

    public record MeResponse(long id, String name, String email, String role, Set<String> permissions) {}

    private final AuthService auth;
    private final CafeProps props;

    public AuthController(AuthService auth, CafeProps props) {
        this.auth = auth;
        this.props = props;
    }

    @PostMapping("/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req, HttpServletResponse res) {
        AuthService.LoginResult r = auth.login(req.email(), req.password());
        setRefreshCookie(res, r.refreshToken());
        return toResponse(r);
    }

    @PostMapping("/auth/refresh")
    public TokenResponse refresh(@CookieValue(name = COOKIE, required = false) String refreshToken,
                                 HttpServletResponse res) {
        AuthService.LoginResult r = auth.refresh(refreshToken);
        setRefreshCookie(res, r.refreshToken());
        return toResponse(r);
    }

    @PostMapping("/auth/logout")
    public void logout(@CookieValue(name = COOKIE, required = false) String refreshToken, HttpServletResponse res) {
        auth.logout(refreshToken);
        ResponseCookie clear = ResponseCookie.from(COOKIE, "").httpOnly(true)
                .secure(props.security().cookieSecure()).sameSite("Strict").path(COOKIE_PATH)
                .maxAge(Duration.ZERO).build();
        res.addHeader(HttpHeaders.SET_COOKIE, clear.toString());
    }

    @GetMapping("/me")
    public MeResponse me() {
        AuthUser u = CurrentUser.get();
        return new MeResponse(u.id(), u.name(), u.email(), u.roleCode(), u.permissions());
    }

    private void setRefreshCookie(HttpServletResponse res, String token) {
        ResponseCookie c = ResponseCookie.from(COOKIE, token).httpOnly(true)
                .secure(props.security().cookieSecure()).sameSite("Strict").path(COOKIE_PATH)
                .maxAge(Duration.ofDays(props.security().refreshTokenDays())).build();
        res.addHeader(HttpHeaders.SET_COOKIE, c.toString());
    }

    private static TokenResponse toResponse(AuthService.LoginResult r) {
        AuthUser u = r.user();
        return new TokenResponse(r.accessToken(), r.expiresInSeconds(),
                new MeResponse(u.id(), u.name(), u.email(), u.roleCode(), u.permissions()));
    }
}
