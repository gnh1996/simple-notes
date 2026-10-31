package cc.naihao.simplenotes.site;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.SiteTheme;
import cc.naihao.simplenotes.domain.SiteThemeVersion;
import cc.naihao.simplenotes.repository.SiteThemeRepository;
import cc.naihao.simplenotes.repository.SiteThemeVersionRepository;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.CreateThemeRequest;
import cc.naihao.simplenotes.web.dto.CreateThemeVersionRequest;
import cc.naihao.simplenotes.web.dto.ThemeSummary;
import cc.naihao.simplenotes.web.dto.ThemeVersionResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主题管理（docs/blog-runtime-theme.md §6.1 / §7.1）。
 *
 * <p>版本不可变：保存、编辑、回滚都只新增行或移动指针；应用 = 移动
 * {@code site_theme.active_version_id} 并同步 {@code site_setting.theme.id}。
 */
@Service
@RequiredArgsConstructor
public class ThemeService {

    /** 当前运行时契约版本；主题声明的 contract 不得高于它 */
    public static final int RUNTIME_CONTRACT = 1;

    private final SiteThemeRepository themeRepository;
    private final SiteThemeVersionRepository versionRepository;
    private final SiteSettingService settingService;

    @Transactional(readOnly = true)
    public List<ThemeSummary> list() {
        String activeThemeId = settingService.activeThemeId();
        return themeRepository.findAllByOrderByCreateTimeAsc().stream()
                .map(theme -> toSummary(theme, activeThemeId))
                .toList();
    }

    @Transactional
    public ThemeSummary create(CreateThemeRequest request) {
        String name = request.name().trim();
        themeRepository.findByNameIgnoreCase(name).ifPresent(existing -> {
            throw ApiException.conflict("主题名称已存在");
        });
        SiteTheme theme = new SiteTheme();
        theme.setName(name);
        theme.setCreateBy(SecurityUtils.currentUserId());
        themeRepository.saveAndFlush(theme);
        return toSummary(theme, settingService.activeThemeId());
    }

    @Transactional(readOnly = true)
    public List<ThemeVersionResponse> versions(String themeId) {
        requireTheme(themeId);
        return versionRepository.findByThemeIdOrderByVersionNoDesc(themeId).stream()
                .map(this::toVersionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ThemeVersionResponse version(String themeId, String versionId) {
        return toVersionResponse(requireVersion(themeId, versionId));
    }

    @Transactional
    public ThemeVersionResponse createVersion(String themeId, CreateThemeVersionRequest request) {
        SiteTheme theme = requireTheme(themeId);
        validateContract(request.contract());
        validateHtml(request.html());
        validateCss(request.css());

        int nextVersionNo = versionRepository.findTopByThemeIdOrderByVersionNoDesc(themeId)
                .map(version -> version.getVersionNo() + 1)
                .orElse(1);

        SiteThemeVersion version = new SiteThemeVersion();
        version.setThemeId(themeId);
        version.setVersionNo(nextVersionNo);
        version.setFormat((short) 1);
        version.setHtml(request.html());
        version.setCss(request.css());
        version.setContract(request.contract());
        version.setCreateBy(SecurityUtils.currentUserId());
        versionRepository.saveAndFlush(version);

        theme.setUpdateBy(SecurityUtils.currentUserId());
        themeRepository.save(theme);
        return toVersionResponse(version);
    }

    /** 应用 / 回滚：版本必须属于该主题 */
    @Transactional
    public ThemeSummary activate(String themeId, String versionId) {
        SiteTheme theme = requireTheme(themeId);
        requireVersion(themeId, versionId);
        theme.setActiveVersionId(versionId);
        theme.setUpdateBy(SecurityUtils.currentUserId());
        themeRepository.saveAndFlush(theme);
        settingService.setActiveTheme(themeId);
        return toSummary(theme, themeId);
    }

    /** 页面组装用：当前生效主题版本；未配置或版本缺失时返回 null（回落内置默认主题） */
    @Transactional(readOnly = true)
    public SiteThemeVersion activeVersion() {
        String themeId = settingService.activeThemeId();
        if (themeId == null || themeId.isBlank()) {
            return null;
        }
        return themeRepository.findById(themeId)
                .map(theme -> theme.getActiveVersionId() == null ? null
                        : versionRepository.findById(theme.getActiveVersionId()).orElse(null))
                .orElse(null);
    }

    /** 预览用：按版本 id 读取任意草稿版本（token 校验在控制器完成） */
    @Transactional(readOnly = true)
    public SiteThemeVersion previewVersion(String versionId) {
        return versionRepository.findById(versionId)
                .orElseThrow(() -> ApiException.notFound("主题版本不存在"));
    }

    // ---- 校验 ----

    private void validateContract(int contract) {
        if (contract < 1 || contract > RUNTIME_CONTRACT) {
            throw ApiException.badRequest(
                    "主题契约版本不受支持：当前运行时为 " + RUNTIME_CONTRACT);
        }
    }

    private void validateHtml(String html) {
        ThemeTemplateValidator.validateHtml(html);
    }

    private void validateCss(String css) {
        ThemeTemplateValidator.validateCss(css);
    }

    private SiteTheme requireTheme(String themeId) {
        return themeRepository.findById(themeId)
                .orElseThrow(() -> ApiException.notFound("主题不存在"));
    }

    private SiteThemeVersion requireVersion(String themeId, String versionId) {
        return versionRepository.findByIdAndThemeId(versionId, themeId)
                .orElseThrow(() -> ApiException.notFound("主题版本不存在"));
    }

    private ThemeSummary toSummary(SiteTheme theme, String activeThemeId) {
        SiteThemeVersion active = theme.getActiveVersionId() == null ? null
                : versionRepository.findById(theme.getActiveVersionId()).orElse(null);
        return new ThemeSummary(
                theme.getId(),
                theme.getName(),
                theme.getActiveVersionId(),
                active == null ? null : active.getVersionNo(),
                active == null ? null : active.getContract(),
                theme.getId().equals(activeThemeId),
                theme.getUpdateTime() == null ? theme.getCreateTime() : theme.getUpdateTime());
    }

    private ThemeVersionResponse toVersionResponse(SiteThemeVersion version) {
        return new ThemeVersionResponse(
                version.getId(),
                version.getThemeId(),
                version.getVersionNo(),
                version.getFormat(),
                version.getContract(),
                version.getHtml(),
                version.getCss(),
                version.getCreateBy(),
                version.getCreateTime());
    }
}
