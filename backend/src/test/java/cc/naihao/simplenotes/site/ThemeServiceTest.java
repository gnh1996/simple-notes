package cc.naihao.simplenotes.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.SiteTheme;
import cc.naihao.simplenotes.repository.SiteThemeRepository;
import cc.naihao.simplenotes.repository.SiteThemeVersionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 主题删除 / 默认主题回退的边界（docs/blog-runtime-theme.md §6.1 / §7.1）。 */
@ExtendWith(MockitoExtension.class)
class ThemeServiceTest {

    @Mock
    private SiteThemeRepository themeRepository;

    @Mock
    private SiteThemeVersionRepository versionRepository;

    @Mock
    private SiteSettingService settingService;

    @InjectMocks
    private ThemeService themeService;

    @Test
    void deleteRejectsActiveTheme() {
        SiteTheme theme = new SiteTheme();
        when(themeRepository.findById("theme-1")).thenReturn(Optional.of(theme));
        when(settingService.activeThemeId()).thenReturn("theme-1");

        assertThatThrownBy(() -> themeService.delete("theme-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("线上主题不能删除");

        assertThat(theme.getDeleteFlag()).isZero();
        verify(themeRepository, never()).save(theme);
    }

    @Test
    void deleteSoftDeletesInactiveThemeAndKeepsVersions() {
        SiteTheme theme = new SiteTheme();
        when(themeRepository.findById("theme-1")).thenReturn(Optional.of(theme));
        when(settingService.activeThemeId()).thenReturn("theme-2");

        themeService.delete("theme-1");

        assertThat(theme.getDeleteFlag()).isEqualTo((short) 1);
        assertThat(theme.getDeleteTime()).isNotNull();
        verify(themeRepository).save(theme);
        verify(versionRepository, never()).deleteAll();
    }

    @Test
    void activateDefaultClearsActiveThemeSetting() {
        themeService.activateDefault();

        verify(settingService).setActiveTheme(null);
    }
}
