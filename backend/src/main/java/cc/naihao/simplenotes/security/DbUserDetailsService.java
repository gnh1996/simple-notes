package cc.naihao.simplenotes.security;

import cc.naihao.simplenotes.domain.SysUser;
import cc.naihao.simplenotes.repository.SysUserRepository;
import cc.naihao.simplenotes.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * 标准认证链的用户加载（docs/auth-rbac.md §5.1）：从 {@code sys_user} 读取账号，
 * 交 {@code DaoAuthenticationProvider} 完成账号状态检查与密码比对。
 *
 * <p>边界：仅承担"加载用户"；认证结果不承载权限（授权走 {@code AuthzCache} 快照 + {@code @authz.can}）。
 * 不存在的用户抛 {@link UsernameNotFoundException}，由 provider 转为凭据错误（不泄露存在性）。
 *
 * <p>存在本 bean 后，Spring Boot 的默认内存用户自动配置（{@code UserDetailsServiceAutoConfiguration}）
 * 不再生效——不存在默认账号与随机密码日志。
 */
@Component
@RequiredArgsConstructor
public class DbUserDetailsService implements UserDetailsService {

    private final SysUserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalized = AuthService.normalizeUsername(username);
        SysUser user = userRepository.findByUsername(normalized)
                .orElseThrow(() -> new UsernameNotFoundException("账号不存在：" + normalized));
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .disabled(user.getStatus() != 1)
                .authorities(AuthorityUtils.NO_AUTHORITIES)
                .build();
    }
}
