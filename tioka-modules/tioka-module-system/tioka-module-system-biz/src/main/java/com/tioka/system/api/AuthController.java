package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.api.spi.IdPAdapter;
import com.tioka.system.application.AuthService;
import com.tioka.system.infrastructure.spi.LocalOidcIdPAdapter;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/system/auth")
public class AuthController {
    private final AuthService authService;
    private final ObjectProvider<IdPAdapter> idPAdapters;
    private final ObjectProvider<LocalOidcIdPAdapter> oidcAdapters;
    private final boolean oidcEnabled;

    public AuthController(AuthService authService,
                          ObjectProvider<IdPAdapter> idPAdapters,
                          ObjectProvider<LocalOidcIdPAdapter> oidcAdapters,
                          @Value("${tioka.auth.oidc-enabled:false}") boolean oidcEnabled) {
        this.authService = authService;
        this.idPAdapters = idPAdapters;
        this.oidcAdapters = oidcAdapters;
        this.oidcEnabled = oidcEnabled;
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(@RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req.username(), req.password()));
    }

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

    @PostMapping("/oidc/callback")
    public ApiResponse<AuthService.LoginResult> oidcCallback(@RequestBody OidcCallbackRequest req) {
        if (!oidcEnabled) {
            throw new BizException(ErrorCode.BAD_REQUEST, "OIDC 未启用");
        }
        IdPAdapter adapter = idPAdapters.stream()
                .filter(a -> a.name().startsWith("oidc:"))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "OIDC Adapter 未装配"));
        Optional<IdPAdapter.FederatedIdentity> identity = adapter.authenticate(Map.of("code", req.code()));
        if (identity.isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "OIDC 认证失败");
        }
        return ApiResponse.ok(authService.loginFederated(identity.get()));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        String token = auth == null ? null : auth.replace("Bearer ", "").trim();
        Long userId = token == null ? null : authService.resolveUserId(token);
        if (userId == null) {
            return ApiResponse.fail(com.tioka.framework.error.ErrorCode.UNAUTHORIZED, "未登录");
        }
        return ApiResponse.ok(Map.of("userId", userId));
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record OidcCallbackRequest(@NotBlank String code, String state) {}
}
