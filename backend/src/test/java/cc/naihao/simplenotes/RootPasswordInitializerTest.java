package cc.naihao.simplenotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cc.naihao.simplenotes.domain.SysUser;
import cc.naihao.simplenotes.repository.SysUserRepository;
import cc.naihao.simplenotes.startup.RootPasswordInitializer;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class RootPasswordInitializerTest {

    @Mock
    private SysUserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void replacesDefaultPasswordOnlyOnce() {
        SysUser root = new SysUser();
        root.setUsername("root");
        root.setPasswordHash("default-hash");
        when(userRepository.findByUsername("root")).thenReturn(Optional.of(root));
        when(passwordEncoder.matches(RootPasswordInitializer.DEFAULT_ROOT_PASSWORD, "default-hash"))
                .thenReturn(true);
        when(passwordEncoder.encode("random-initial")).thenReturn("new-hash");

        new RootPasswordInitializer(userRepository, passwordEncoder, "random-initial").run(null);

        assertThat(root.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).save(root);
    }

    @Test
    void keepsUserChangedPassword() {
        SysUser root = new SysUser();
        root.setUsername("root");
        root.setPasswordHash("user-changed-hash");
        when(userRepository.findByUsername("root")).thenReturn(Optional.of(root));
        when(passwordEncoder.matches(RootPasswordInitializer.DEFAULT_ROOT_PASSWORD, "user-changed-hash"))
                .thenReturn(false);

        new RootPasswordInitializer(userRepository, passwordEncoder, "random-initial").run(null);

        assertThat(root.getPasswordHash()).isEqualTo("user-changed-hash");
        verify(userRepository, never()).save(root);
    }

    @Test
    void skipsWhenNotConfigured() {
        new RootPasswordInitializer(userRepository, passwordEncoder, " ").run(null);

        verify(userRepository, never()).findByUsername("root");
    }
}
