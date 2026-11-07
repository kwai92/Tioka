package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PublicApi;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.application.AuthService;
import com.tioka.system.application.audit.ClientIpResolver;
import com.tioka.system.application.oidc.OidcLoginService;
import com.tioka.system.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/system/auth")
public class AuthController {
    private static final String RT_COOKIE = "tioka_rt";

    private final AuthService authService;
    private final AuthProperties authProperties;
    private final OidcLoginService oidcLogin;
    private final PrincipalHolder principals;
    private final ClientIpResolver clientIpResolver;
    private final boolean cookieSecure;

    public AuthController(AuthService authService,
                          AuthProperties authProperties,
                          OidcLoginService oidcLogin,
                          PrincipalHolder principals,
                          ClientIpResolver clientIpResolver,
                          @org.springframework.beans.factory.annotation.Value("${tioka.auth.cookie-secure:false}") boolean cookieSecure) {
        this.authService = authService;
        this.authProperties = authProperties;
        this.oidcLogin = oidcLogin;
        this.principals = principals;
        this.clientIpResolver = clientIpResolver;
        this.cookieSecure = cookieSecure;
    }

    @PublicApi(reason = "本地登录签发令牌")
    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(@RequestBody LoginRequest req,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        AuthService.LoginResult result = authService.login(req.username(), req.password(),
                clientIpResolver.resolve(request));
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

    @RequirePermission({})
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String access = bearer(request);
        if (access != null) {
            authService.logout(access);
        }
        clearRefreshCookie(response);
        return ApiResponse.ok(null);
    }

    @RequirePermission({})
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody PasswordRequest req, HttpServletRequest request) {
        Long userId = currentUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        authService.changePassword(userId, req.oldPassword(), req.newPassword());
        return ApiResponse.ok(null);
    }

    @PublicApi(reason = "OIDC 授权入口（FND-05）")
    @GetMapping("/oidc/{idp}/authorize")
    public void oidcAuthorize(@PathVariable String idp,
                              @RequestParam(value = "redirect", defaultValue = "/") String redirect,
                              HttpServletRequest request,
                              HttpServletResponse response) throws Exception {
        String callback = absoluteCallback(request, idp);
        String authUrl = oidcLogin.beginAuthorize(idp, redirect, callback);
        response.setStatus(302);
        response.setHeader(HttpHeaders.LOCATION, authUrl);
    }

    @PublicApi(reason = "OIDC 回调（FND-05）")
    @GetMapping("/oidc/{idp}/callback")
    public void oidcCallback(@PathVariable String idp,
                             @RequestParam(required = false) String code,
                             @RequestParam(required = false) String state,
                             @RequestParam(required = false) String error,
                             HttpServletResponse response) throws Exception {
        if (error != null && !error.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "OIDC 错误: " + error);
        }
        OidcLoginService.CallbackResult result = oidcLogin.handleCallback(idp, code, state);
        writeRefreshCookie(response, result.tokens().refreshToken());
        response.setStatus(302);
        response.setHeader(HttpHeaders.LOCATION, result.frontendRedirect());
    }

    @RequirePermission({})
    @GetMapping("/me")
    public ApiResponse<AuthService.MeResult> me() {
        Long userId = currentUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return ApiResponse.ok(authService.me(userId));
    }

    private Long currentUserId() {
        return principals.find()
                .map(Principal::userId)
                .map(Long::parseLong)
                .orElse(null);
    }

    private static String absoluteCallback(HttpServletRequest request, String idp) {
        StringBuffer url = request.getRequestURL();
        // .../authorize → .../callback
        String s = url.toString();
        int idx = s.lastIndexOf("/authorize");
        if (idx > 0) {
            return s.substring(0, idx) + "/callback";
        }
        return request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort())
                + "/api/system/auth/oidc/" + idp + "/callback";
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

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record PasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {}
}
