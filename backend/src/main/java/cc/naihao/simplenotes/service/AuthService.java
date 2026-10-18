package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.common.ErrorCodes;
import cc.naihao.simplenotes.domain.ClientType;
import cc.naihao.simplenotes.domain.SysRole;
import cc.naihao.simplenotes.domain.SysUser;
import cc.naihao.simplenotes.domain.SysUserRole;
import cc.naihao.simplenotes.domain.SysUserSession;
import cc.naihao.simplenotes.repository.SysRoleRepository;
import cc.naihao.simplenotes.repository.SysUserRepository;
import cc.naihao.simplenotes.repository.SysUserRoleRepository;
import cc.naihao.simplenotes.security.AuthPrincipal;
import cc.naihao.simplenotes.security.AuthzCache;
import cc.naihao.simplenotes.security.AuthzSnapshot;
import cc.naihao.simplenotes.security.JwtService;
import cc.naihao.simplenotes.security.SessionService;
import cc.naihao.simplenotes.web.dto.LoginRequest;
import cc.naihao.simplenotes.web.dto.LoginResponse;
import cc.naihao.simplenotes.web.dto.MeResponse;
import cc.naihao.simplenotes.web.dto.MenuNode;
import cc.naihao.simplenotes.web.dto.PasswordChangeRequest;
import cc.naihao.simplenotes.web.dto.ProfileUpdateRequest;
import cc.naihao.simplenotes.web.dto.RoleBrief;
import cc.naihao.simplenotes.web.dto.UserSummary;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证：登录 / 登出 / 当前身份 / 个人中心（docs/auth-rbac.md §5.2 / §5.4 / §8）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final AuthzCache authzCache;
    private final SessionService sessionService;
    private final JwtService jwtService;
    private final LoginRateLimiter rateLimiter;
    private final MenuService menuService;

    /**
     * 登录（docs/auth-rbac.md §5.2）：限流 → 标准认证链（加载用户 / 账号状态 / 密码比对，§5.1）→
     * 该端入口权限（超管直通）；成功更新 last_login_time 并签发会话 + JWT。
     *
     * @param clientIp 用于限流计数的来源 IP
     */
    @Transactional
    public LoginResponse login(LoginRequest request, String clientIp) {
        String username = normalizeUsername(request.username());
        rateLimiter.checkAllowed(username, clientIp);

        try {
            // 凭据比对与账号状态（禁用）检查由 DaoAuthenticationProvider 完成
            authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, request.password()));
        } catch (DisabledException e) {
            log.warn("登录失败（账号禁用）：username={} ip={}", username, clientIp);
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.LOGIN_USER_DISABLED, "账号已被禁用");
        } catch (AuthenticationException e) {
            // 凭据错误 / 用户名不存在（provider 同码处理，不泄露存在性）统一 401
            rateLimiter.recordFailure(username, clientIp);
            log.warn("登录失败（凭据错误）：username={} ip={}", username, clientIp);
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.LOGIN_CREDENTIALS_INVALID,
                    "用户名或密码错误");
        }

        // 认证通过：取实体供入口校验与响应（登录低频，二次查询换取 UserDetails 保持标准形态）
        SysUser user = userRepository.findByUsername(username).orElseThrow(
                () -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.LOGIN_CREDENTIALS_INVALID,
                        "用户名或密码错误"));

        // 登录本就需计算入口权限，顺手预热快照
        AuthzSnapshot snapshot = authzCache.get(user.getId());
        if (!snapshot.root() && !snapshot.codes().contains(request.client().entryCode())) {
            log.warn("登录失败（无入口权限）：username={} client={} ip={}", username, request.client(), clientIp);
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.LOGIN_ENTRY_DENIED, "没有该端的登录权限");
        }

        rateLimiter.recordSuccess(username, clientIp);

        user.setLastLoginTime(Instant.now());
        user.setUpdateBy(user.getId());
        userRepository.save(user);

        SysUserSession session = sessionService.create(user.getId(), request.client());
        String token = jwtService.issue(user.getId(), session.getId(), session.getCreateTime(),
                session.getExpireTime());
        log.info("登录成功：username={} client={} ip={}", username, request.client(), clientIp);
        return new LoginResponse(token, UserSummary.from(user));
    }

    @Transactional(readOnly = true)
    public MeResponse me(AuthPrincipal principal) {
        SysUser user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_REQUIRED,
                        "账号不存在或已删除"));
        AuthzSnapshot snapshot = authzCache.get(user.getId());
        List<String> codes = snapshot.codes().stream().sorted().toList();
        List<String> entries = codes.stream().filter(code -> code.startsWith("entry:")).toList();
        List<RoleBrief> roles = enabledRoles(user.getId()).stream()
                .sorted(Comparator.comparingInt(SysRole::getSort))
                .map(RoleBrief::from)
                .toList();
        // menus 仅 ADMIN 端返回（blog / desktop 无菜单概念）
        List<MenuNode> menus = principal.client() == ClientType.ADMIN
                ? menuService.visibleMenuTree(snapshot)
                : null;
        return new MeResponse(UserSummary.from(user), snapshot.root(), entries, codes, roles, menus);
    }

    public void logout(AuthPrincipal principal) {
        sessionService.revoke(principal.sessionId());
    }

    public void logoutAll(AuthPrincipal principal) {
        sessionService.revokeAll(principal.userId());
    }

    /** 修改自己的密码：需旧密码；成功后撤销该用户除当前外的全部会话（docs/auth-rbac.md §5.4） */
    @Transactional
    public void changePassword(AuthPrincipal principal, PasswordChangeRequest request) {
        SysUser user = requireUser(principal.userId());
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("旧密码不正确");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("新密码不能与旧密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdateBy(user.getId());
        userRepository.save(user);
        sessionService.revokeAllExcept(user.getId(), principal.sessionId());
        authzCache.bumpAfterCommit();
    }

    /** 修改自己的昵称（登录即可，不设权限码） */
    @Transactional
    public UserSummary updateProfile(AuthPrincipal principal, ProfileUpdateRequest request) {
        SysUser user = requireUser(principal.userId());
        user.setNickname(request.nickname().trim());
        user.setUpdateBy(user.getId());
        userRepository.save(user);
        authzCache.bumpAfterCommit();
        return UserSummary.from(user);
    }

    private List<SysRole> enabledRoles(String userId) {
        List<String> roleIds = userRoleRepository.findByUserId(userId).stream()
                .map(SysUserRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleRepository.findAllById(roleIds).stream()
                .filter(role -> role.getStatus() == 1)
                .toList();
    }

    private SysUser requireUser(String userId) {
        return userRepository.findById(userId).orElseThrow(
                () -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_REQUIRED, "账号不存在或已删除"));
    }

    /** 用户名归一化：去空白 + 小写（docs/auth-rbac.md §5.2） */
    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
