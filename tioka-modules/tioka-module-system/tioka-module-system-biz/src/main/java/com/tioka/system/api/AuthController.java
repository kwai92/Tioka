package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.PublicApi;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.api.spi.IdPAdapter;
import com.tioka.system.application.AuthService;
import com.tioka.system.config.AuthProperties;
import com.tioka.system.infrastructure.spi.LocalOidcIdPAdapter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/system/auth")
public class AuthController {
    private static final String RT_COOKIE = "tioka_rt";

    private final AuthService authService;
    private final AuthProperties authProperties;
    private final ObjectProvider<IdPAdapter> idPAdapters;
    private final ObjectProvider<LocalOidcIdPAdapter> oidcAdapters;
    private final boolean oidcEnabled;
    private final boolean cookieSecure;

    public AuthController(AuthService authService,
                          AuthProperties authProperties,
                          ObjectProvider<IdPAdapter> idPAdapters,
                          ObjectProvider<LocalOidcIdPAdapter> oidcAdapters,
                          @Value("${tioka.auth.oidc-enabled:false}") boolean oidcEnabled,
                          @Value("${tioka.auth.cookie-secure:false}") boolean cookieSecure) {
        this.authService = authService;
        this.authProperties = authProperties;
        this.idPAdapters = idPAdapters;
        this.oidcAdapters = oidcAdapters;
        this.oidcEnabled = oidcEnabled;
        this.cookieSecure = cookieSecure;
    }

    @PublicApi(reason = "本地登录签发令牌")
    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(@RequestBody LoginRequest req,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        AuthService.LoginResult result = authService.login(req.username(), req.password(), clientIp(request));
        writeRefreshCookie(response, result.refreshToken());
        return ApiResponse.ok(result);
    }

    @PublicApi(reason = "刷新访问令牌（读 HttpOnly Cookie）")
    @PostMapping("/refresh")
    public ApiResponse<AuthService.LoginResult> refresh(HttpServletRequest request, HttpServletResponse response) {
        String rt = readCookie(request, RT_COOKIE);
        if (rt == null || rt.isBlank()) {
            clearRefreshCookie(response);
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效");
        }
        try {
            AuthService.LoginResult result = authService.refresh(rt);
            writeRefreshCookie(response, result.refreshToken());
            return ApiResponse.ok(result);
        } catch (BizException e) {
            clearRefreshCookie(response);
            throw e;
        }
    }

    @RequirePermission("auth:logout")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String access = bearer(request);
        if (access != null) {
            authService.logout(access);
        }
        clearRefreshCookie(response);
        return ApiResponse.ok(null);
    }

    @RequirePermission("auth:password")
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody PasswordRequest req, HttpServletRequest request) {
        Long userId = authService.resolveUserId(bearer(request));
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        authService.changePassword(userId, req.oldPassword(), req.newPassword());
        return ApiResponse.ok(null);
    }

    @PublicApi(reason = "OIDC 授权入口")
    @GetMapping("/oidc/start")
    public ApiResponse<Map<String, Object>> oidcStart(@RequestParam(defaultValue = "tioka") String state) {
        if (!oidcEnabled) {
            throw new BizException(ErrorCode.BAD_REQUEST, "OIDC 未启用");
        }
        LocalOidcIdPAdapter oidc = oidcAdapters.getIfAvailable();
        if (oidc == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "OIDC Adapter 未装配");
        }
        return ApiResponse.ok(Map.of(
                "enabled", true,
                "authorizationUrl", oidc.authorizationUrl(state),
                "provider", oidc.name()
        ));
    }

    @PublicApi(reason = "OIDC 回调换码")
    @PostMapping("/oidc/callback")
    public ApiResponse<AuthService.LoginResult> oidcCallback(@RequestBody OidcCallbackRequest req,
                                                             HttpServletResponse response) {
        if (!oidcEnabled) {
            throw new BizException(ErrorCode.BAD_REQUEST, "OIDC 未启用");
        }
        IdPAdapter adapter = idPAdapters.stream()
                .filter(a -> a.name().startsWith("oidc:"))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "OIDC Adapter 未装配"));
        Map<String, String> creds = new java.util.HashMap<>();
        creds.put("code", req.code());
        if (req.codeVerifier() != null && !req.codeVerifier().isBlank()) {
            creds.put("code_verifier", req.codeVerifier());
        }
        Optional<IdPAdapter.FederatedIdentity> identity = adapter.authenticate(creds);
        if (identity.isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "OIDC 认证失败");
        }
        AuthService.LoginResult result = authService.loginFederated(identity.get());
        writeRefreshCookie(response, result.refreshToken());
        return ApiResponse.ok(result);
    }

    @RequirePermission("auth:me")
    @GetMapping("/me")
    public ApiResponse<AuthService.MeResult> me(HttpServletRequest request) {
        Long userId = authService.resolveUserId(bearer(request));
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return ApiResponse.ok(authService.me(userId));
    }

    private void writeRefreshCookie(HttpServletResponse response, String refreshToken) {
        Duration ttl = authProperties.getRefreshTtl();
        ResponseCookie cookie = ResponseCookie.from(RT_COOKIE, refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/api/system/auth")
                .sameSite("Strict")
                .maxAge(ttl)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(RT_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/api/system/auth")
                .sameSite("Strict")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String bearer(HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        return auth.substring(7).trim();
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record OidcCallbackRequest(@NotBlank String code, String state, String codeVerifier) {}
    public record PasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {}
}
