package cc.naihao.simplenotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cc.naihao.simplenotes.domain.SysUser;
import cc.naihao.simplenotes.repository.SysUserRepository;
import cc.naihao.simplenotes.startup.AdminPasswordInitializer;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminPasswordInitializerTest {

    @Mock
    private SysUserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private static SysUser admin(String hash) {
        SysUser admin = new SysUser();
        admin.setUsername(AdminPasswordInitializer.ADMIN_USERNAME);
        admin.setPasswordHash(hash);
        return admin;
    }

    @Test
    void replacesDefaultPasswordOnlyOnce() {
        SysUser admin = admin("default-hash");
        when(userRepository.findByUsername(AdminPasswordInitializer.ADMIN_USERNAME)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches(AdminPasswordInitializer.DEFAULT_ADMIN_PASSWORD, "default-hash"))
                .thenReturn(true);
        when(passwordEncoder.encode("random-initial")).thenReturn("new-hash");

        new AdminPasswordInitializer(userRepository, passwordEncoder, "random-initial").applyInitialPassword();

        assertThat(admin.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).save(admin);
    }

    @Test
    void keepsUserChangedPassword() {
        SysUser admin = admin("user-changed-hash");
        when(userRepository.findByUsername(AdminPasswordInitializer.ADMIN_USERNAME)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches(AdminPasswordInitializer.DEFAULT_ADMIN_PASSWORD, "user-changed-hash"))
                .thenReturn(false);

        new AdminPasswordInitializer(userRepository, passwordEncoder, "random-initial").applyInitialPassword();

        assertThat(admin.getPasswordHash()).isEqualTo("user-changed-hash");
        verify(userRepository, never()).save(admin);
    }

    @Test
    void allowsStartupWithoutInitialPasswordAfterUserChangedIt() {
        SysUser admin = admin("user-changed-hash");
        when(userRepository.findByUsername(AdminPasswordInitializer.ADMIN_USERNAME)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches(AdminPasswordInitializer.DEFAULT_ADMIN_PASSWORD, "user-changed-hash"))
                .thenReturn(false);

        new AdminPasswordInitializer(userRepository, passwordEncoder, " ").applyInitialPassword();

        assertThat(admin.getPasswordHash()).isEqualTo("user-changed-hash");
        verify(userRepository, never()).save(admin);
    }

    @Test
    void failsFastWhenDefaultPasswordStillActiveWithoutOverride() {
        SysUser admin = admin("default-hash");
        when(userRepository.findByUsername(AdminPasswordInitializer.ADMIN_USERNAME)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches(AdminPasswordInitializer.DEFAULT_ADMIN_PASSWORD, "default-hash"))
                .thenReturn(true);

        AdminPasswordInitializer initializer = new AdminPasswordInitializer(userRepository, passwordEncoder, "");

        assertThatThrownBy(() -> initializer.applyInitialPassword())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_INITIAL_PASSWORD");
        verify(userRepository, never()).save(admin);
    }

    @Test
    void ignoresMissingAdminUser() {
        when(userRepository.findByUsername(AdminPasswordInitializer.ADMIN_USERNAME)).thenReturn(Optional.empty());

        new AdminPasswordInitializer(userRepository, passwordEncoder, "random-initial").applyInitialPassword();

        verify(userRepository, never()).save(any());
    }
}
