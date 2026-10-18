package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.security.AuthPrincipal;
import cc.naihao.simplenotes.service.AuthService;
import cc.naihao.simplenotes.web.dto.LoginRequest;
import cc.naihao.simplenotes.web.dto.LoginResponse;
import cc.naihao.simplenotes.web.dto.MeResponse;
import cc.naihao.simplenotes.web.dto.PasswordChangeRequest;
import cc.naihao.simplenotes.web.dto.ProfileUpdateRequest;
import cc.naihao.simplenotes.web.dto.UserSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（docs/auth-rbac.md §8）：登录 / 登出 / 身份 / 个人中心。
 *
 * <p>登录匿名；其余接口要求会话（见 SecurityConfig 路径规则）；个人中心属"登录即可"，不设权限码。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, clientIp(httpRequest));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthPrincipal principal) {
        authService.logout(principal);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal AuthPrincipal principal) {
        authService.logoutAll(principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
        return authService.me(principal);
    }

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody PasswordChangeRequest request) {
        authService.changePassword(principal, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/profile")
    public UserSummary updateProfile(@AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ProfileUpdateRequest request) {
        return authService.updateProfile(principal, request);
    }

    /**
     * 限流计数的来源标识。服务直连时取 remoteAddr；经反向代理部署（目前仅在本地开发，
     * 无代理）不信任 X-Forwarded-For，接入代理时再按部署形态补充可信转发处理。
     */
    private String clientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null ? "unknown" : remoteAddr;
    }
}
