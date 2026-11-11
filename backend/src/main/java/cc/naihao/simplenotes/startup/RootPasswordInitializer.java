package cc.naihao.simplenotes.startup;

import cc.naihao.simplenotes.domain.SysUser;
import cc.naihao.simplenotes.repository.SysUserRepository;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动覆写 root 的默认口令（部署用）。
 *
 * <p>背景：V1 迁移预置的 root 口令是公开的 {@code admin@123456}，个人部署直接暴露公网时必须
 * 在首次启动时替换。部署时通过 {@code ROOT_INITIAL_PASSWORD} 注入；本类只在“当前 hash 仍等于
 * 默认口令”时改写，用户改密后重启不会再次覆盖。
 *
 * <p><b>fail-fast</b>：root 仍是默认口令且未配置 {@code ROOT_INITIAL_PASSWORD} 时拒绝启动，
 * 避免把公开口令带到公网。用户已改密后该变量可以留空。
 *
 * <p>在 {@link SmartInitializingSingleton} 阶段执行：此时 DataSource / Flyway / JPA 已就绪，
 * 但 Servlet 容器尚未开始监听，失败时不会出现“已对外服务、随后才退出”的窗口。
 *
 * <p>口令本身不落日志。
 */
@Slf4j
@Component
public class RootPasswordInitializer implements SmartInitializingSingleton {

    /** 与 V1 迁移预置口令保持一致；仅用于判断是否需要覆盖。 */
    public static final String DEFAULT_ROOT_PASSWORD = "admin@123456";

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String initialPassword;

    public RootPasswordInitializer(
            SysUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${simple-notes.auth.root-initial-password:}") String initialPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.initialPassword = initialPassword;
    }

    @Override
    public void afterSingletonsInstantiated() {
        applyInitialPassword();
    }

    /** 启动期执行一次；独立方法便于单测直接调用。 */
    public void applyInitialPassword() {
        Optional<SysUser> found = userRepository.findByUsername("root");
        if (found.isEmpty()) {
            return;
        }
        SysUser user = found.get();
        if (!passwordEncoder.matches(DEFAULT_ROOT_PASSWORD, user.getPasswordHash())) {
            // 已改密：不再覆盖，也不再要求 ROOT_INITIAL_PASSWORD
            return;
        }
        if (initialPassword == null || initialPassword.isBlank()) {
            throw new IllegalStateException("""
                    root 仍使用迁移内置的默认口令，拒绝启动。
                    请设置环境变量 ROOT_INITIAL_PASSWORD（scripts/init-env.sh 会生成随机值）后重启；
                    或先用默认口令登录并改密，之后即可移除该变量。""");
        }
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        userRepository.save(user);
        log.info("root 初始口令已按 ROOT_INITIAL_PASSWORD 更新（仅默认口令时执行一次）");
    }
}
