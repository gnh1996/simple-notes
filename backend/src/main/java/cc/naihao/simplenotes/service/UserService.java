package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.QSysUser;
import cc.naihao.simplenotes.domain.SysRole;
import cc.naihao.simplenotes.domain.SysUser;
import cc.naihao.simplenotes.domain.SysUserRole;
import cc.naihao.simplenotes.repository.SysRoleRepository;
import cc.naihao.simplenotes.repository.SysUserRepository;
import cc.naihao.simplenotes.repository.SysUserRoleRepository;
import cc.naihao.simplenotes.security.AuthzCache;
import cc.naihao.simplenotes.security.Roles;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.security.SessionService;
import cc.naihao.simplenotes.web.dto.AssignRolesRequest;
import cc.naihao.simplenotes.web.dto.CreateUserRequest;
import cc.naihao.simplenotes.web.dto.PageResponse;
import cc.naihao.simplenotes.web.dto.ResetPasswordRequest;
import cc.naihao.simplenotes.web.dto.RoleBrief;
import cc.naihao.simplenotes.web.dto.UpdateUserRequest;
import cc.naihao.simplenotes.web.dto.UserResponse;
import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户管理（admin 端，docs/auth-rbac.md §8）。
 *
 * <p>超管保护（§4）：持有 SUPER_ADMIN 角色的用户不可删除 / 禁用 / 改角色集合 / 重置密码。
 * 写路径统一在事务提交后 bump 授权快照；禁用 / 删除另撤销该用户全部会话（§5.4）。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    /** 与建表规范一致：小写，[a-z0-9][a-z0-9_-]{2,31} */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{2,31}");

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;
    private final AuthzCache authzCache;
    private final JPAQueryFactory queryFactory;

    /** 分页列表（user: 权限码守护）；keyword 模糊匹配登录名 / 昵称 / 邮箱 */
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(int page, int size, String keyword) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.clamp(size, 1, 100);

        QSysUser user = QSysUser.sysUser;
        Predicate keywordFilter = null;
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            keywordFilter = user.username.containsIgnoreCase(kw)
                    .or(user.nickname.containsIgnoreCase(kw))
                    .or(user.email.containsIgnoreCase(kw));
        }

        JPAQuery<SysUser> rowsQuery = queryFactory.selectFrom(user);
        JPAQuery<Long> countQuery = queryFactory.select(user.count()).from(user);
        if (keywordFilter != null) {
            rowsQuery = rowsQuery.where(keywordFilter);
            countQuery = countQuery.where(keywordFilter);
        }
        List<SysUser> rows = rowsQuery
                .orderBy(user.createTime.desc())
                .offset((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .fetch();
        Long total = countQuery.fetchOne();

        Map<String, List<RoleBrief>> rolesByUser = rolesByUser(rows.stream().map(SysUser::getId).toList());
        List<UserResponse> items = rows.stream()
                .map(row -> toResponse(row, rolesByUser.getOrDefault(row.getId(), List.of())))
                .toList();
        return new PageResponse<>(items, total == null ? 0 : total, safePage, safeSize);
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String username = AuthService.normalizeUsername(request.username());
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw ApiException.badRequest("用户名需为 3-32 位字母 / 数字 / 下划线 / 中划线，且以字母或数字开头");
        }
        if (userRepository.existsByUsername(username)) {
            throw ApiException.conflict("登录名已被使用");
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname().trim());
        user.setEmail(normalizeEmail(request.email()));
        user.setCreateBy(SecurityUtils.currentUserId());
        userRepository.save(user);
        authzCache.bumpAfterCommit();
        return toResponse(user, List.of());
    }

    /** 编辑用户：昵称 / 邮箱 / 状态（用户名不可改）；禁用 = 撤销全部会话 + 即时生效 */
    @Transactional
    public UserResponse update(String id, UpdateUserRequest request) {
        SysUser user = requireUser(id);
        if (request.status() == 0 && holdsSuperAdmin(user.getId())) {
            throw ApiException.permissionDenied("超管账号不可禁用");
        }
        user.setNickname(request.nickname().trim());
        user.setEmail(normalizeEmail(request.email()));
        user.setStatus(request.status().shortValue());
        user.setUpdateBy(SecurityUtils.currentUserId());
        userRepository.save(user);
        if (request.status() == 0) {
            sessionService.revokeAll(user.getId());
        }
        authzCache.bumpAfterCommit();
        return toResponse(user, roleBriefs(user.getId()));
    }

    /** 分配角色：全量替换；内置角色不可分配；超管角色持有者拒绝（docs/auth-rbac.md §4 / §6.2） */
    @Transactional
    public UserResponse assignRoles(String id, AssignRolesRequest request) {
        SysUser user = requireUser(id);
        if (holdsSuperAdmin(user.getId())) {
            throw ApiException.permissionDenied("超管账号的角色集合不可修改");
        }
        List<String> roleIds = request.roleIds().stream().distinct().toList();
        List<SysRole> roles = roleIds.isEmpty() ? List.of() : roleRepository.findAllById(roleIds);
        if (roles.size() != roleIds.size()) {
            throw ApiException.badRequest("存在无效的角色 id");
        }
        if (roles.stream().anyMatch(role -> role.getBuiltin() == 1)) {
            throw ApiException.permissionDenied("内置角色不可分配给用户");
        }

        userRoleRepository.deleteAll(userRoleRepository.findByUserId(user.getId()));
        // 显式 flush：Hibernate flush 顺序为先 insert 后 delete，"删旧 + 插同 PK 新"若同一次
        // flush 内会撞主键，必须先删干净再插
        userRoleRepository.flush();
        String operator = SecurityUtils.currentUserId();
        userRoleRepository.saveAll(roleIds.stream()
                .map(roleId -> new SysUserRole(user.getId(), roleId, operator))
                .toList());
        authzCache.bumpAfterCommit();
        return toResponse(user, roles.stream().map(RoleBrief::from).toList());
    }

    /** 管理员重置他人密码：超管角色持有者拒绝；成功后撤销被重置用户的全部会话 */
    @Transactional
    public void resetPassword(String id, ResetPasswordRequest request) {
        SysUser user = requireUser(id);
        if (holdsSuperAdmin(user.getId())) {
            throw ApiException.permissionDenied("超管账号的密码不可被重置");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdateBy(SecurityUtils.currentUserId());
        userRepository.save(user);
        sessionService.revokeAll(user.getId());
        authzCache.bumpAfterCommit();
    }

    /** 软删除：超管角色持有者拒绝；撤销全部会话；角色绑定保留（占用判断只计未删除用户） */
    @Transactional
    public void delete(String id) {
        SysUser user = requireUser(id);
        if (holdsSuperAdmin(user.getId())) {
            throw ApiException.permissionDenied("超管账号不可删除");
        }
        user.markDeleted(SecurityUtils.currentUserId());
        userRepository.save(user);
        sessionService.revokeAll(user.getId());
        authzCache.bumpAfterCommit();
    }

    /** 是否持有超管角色（保护判定按绑定关系，不随角色启停变化） */
    private boolean holdsSuperAdmin(String userId) {
        List<String> roleIds = userRoleRepository.findByUserId(userId).stream()
                .map(SysUserRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return false;
        }
        return roleRepository.findAllById(roleIds).stream()
                .anyMatch(role -> Roles.SUPER_ADMIN.equals(role.getCode()));
    }

    private Map<String, List<RoleBrief>> rolesByUser(List<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<SysUserRole> bindings = userRoleRepository.findByUserIdIn(userIds);
        Set<String> roleIds = bindings.stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
        Map<String, SysRole> roleById = roleRepository.findAllById(roleIds).stream()
                .collect(Collectors.toMap(SysRole::getId, Function.identity()));
        Map<String, List<RoleBrief>> result = new HashMap<>();
        for (SysUserRole binding : bindings) {
            SysRole role = roleById.get(binding.getRoleId());
            if (role != null) {
                result.computeIfAbsent(binding.getUserId(), _ -> new ArrayList<>())
                        .add(RoleBrief.from(role));
            }
        }
        return result;
    }

    private List<RoleBrief> roleBriefs(String userId) {
        return rolesByUser(List.of(userId)).getOrDefault(userId, List.of());
    }

    private UserResponse toResponse(SysUser user, List<RoleBrief> roles) {
        boolean root = roles.stream().anyMatch(role -> Roles.SUPER_ADMIN.equals(role.code()));
        return new UserResponse(user.getId(), user.getUsername(), user.getNickname(), user.getEmail(),
                user.getStatus(), user.getLastLoginTime(), root, roles, user.getCreateTime());
    }

    private SysUser requireUser(String id) {
        return userRepository.findById(id).orElseThrow(() -> ApiException.notFound("用户不存在"));
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim();
    }
}
