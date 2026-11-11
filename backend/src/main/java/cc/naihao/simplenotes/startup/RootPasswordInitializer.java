package cc.naihao.simplenotes.startup;

import cc.naihao.simplenotes.repository.SysUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动覆写 root 的默认口令（一键容器部署用）。
 *
 * <p>背景：V1 迁移预置的 root 口令是公开的 {@code admin@123456}，个人部署直接暴露公网时必须
 * 在首次启动时替换。容器 entrypoint 生成随机口令并通过 {@code ROOT_INITIAL_PASSWORD} 注入；
 * 本类只在“当前 hash 仍等于默认口令”时改写，用户改密后重启不会再次覆盖。
 *
 * <p>口令本身不落日志；未配置该环境变量时完全跳过，不影响本地开发与已有部署。
 */
@Slf4j
@Component
public class RootPasswordInitializer implements ApplicationRunner {

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
    public void run(ApplicationArguments args) {
        if (initialPassword == null || initialPassword.isBlank()) {
            return;
        }
        userRepository.findByUsername("root").ifPresent(user -> {
            if (!passwordEncoder.matches(DEFAULT_ROOT_PASSWORD, user.getPasswordHash())) {
                return;
            }
            user.setPasswordHash(passwordEncoder.encode(initialPassword));
            userRepository.save(user);
            log.info("root 初始口令已按 ROOT_INITIAL_PASSWORD 更新（仅默认口令时执行一次）");
        });
    }
}
