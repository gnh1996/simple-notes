package cc.naihao.simplenotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private static SysUser root(String hash) {
        SysUser root = new SysUser();
        root.setUsername("root");
        root.setPasswordHash(hash);
        return root;
    }

    @Test
    void replacesDefaultPasswordOnlyOnce() {
        SysUser root = root("default-hash");
        when(userRepository.findByUsername("root")).thenReturn(Optional.of(root));
        when(passwordEncoder.matches(RootPasswordInitializer.DEFAULT_ROOT_PASSWORD, "default-hash"))
                .thenReturn(true);
        when(passwordEncoder.encode("random-initial")).thenReturn("new-hash");

        new RootPasswordInitializer(userRepository, passwordEncoder, "random-initial").applyInitialPassword();

        assertThat(root.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).save(root);
    }

    @Test
    void keepsUserChangedPassword() {
        SysUser root = root("user-changed-hash");
        when(userRepository.findByUsername("root")).thenReturn(Optional.of(root));
        when(passwordEncoder.matches(RootPasswordInitializer.DEFAULT_ROOT_PASSWORD, "user-changed-hash"))
                .thenReturn(false);

        new RootPasswordInitializer(userRepository, passwordEncoder, "random-initial").applyInitialPassword();

        assertThat(root.getPasswordHash()).isEqualTo("user-changed-hash");
        verify(userRepository, never()).save(root);
    }

    @Test
    void allowsStartupWithoutInitialPasswordAfterUserChangedIt() {
        SysUser root = root("user-changed-hash");
        when(userRepository.findByUsername("root")).thenReturn(Optional.of(root));
        when(passwordEncoder.matches(RootPasswordInitializer.DEFAULT_ROOT_PASSWORD, "user-changed-hash"))
                .thenReturn(false);

        new RootPasswordInitializer(userRepository, passwordEncoder, " ").applyInitialPassword();

        assertThat(root.getPasswordHash()).isEqualTo("user-changed-hash");
        verify(userRepository, never()).save(root);
    }

    @Test
    void failsFastWhenDefaultPasswordStillActiveWithoutOverride() {
        SysUser root = root("default-hash");
        when(userRepository.findByUsername("root")).thenReturn(Optional.of(root));
        when(passwordEncoder.matches(RootPasswordInitializer.DEFAULT_ROOT_PASSWORD, "default-hash"))
                .thenReturn(true);

        RootPasswordInitializer initializer = new RootPasswordInitializer(userRepository, passwordEncoder, "");

        assertThatThrownBy(() -> initializer.applyInitialPassword())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROOT_INITIAL_PASSWORD");
        verify(userRepository, never()).save(root);
    }

    @Test
    void ignoresMissingRootUser() {
        when(userRepository.findByUsername("root")).thenReturn(Optional.empty());

        new RootPasswordInitializer(userRepository, passwordEncoder, "random-initial").applyInitialPassword();

        verify(userRepository, never()).save(any());
    }
}
