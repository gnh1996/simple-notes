package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.site.AiThemeService;
import cc.naihao.simplenotes.site.PreviewTokenService;
import cc.naihao.simplenotes.site.ThemeService;
import cc.naihao.simplenotes.web.dto.AiChatRequest;
import cc.naihao.simplenotes.web.dto.CreateThemeRequest;
import cc.naihao.simplenotes.web.dto.CreateThemeVersionRequest;
import cc.naihao.simplenotes.web.dto.PreviewTokenResponse;
import cc.naihao.simplenotes.web.dto.ThemeSummary;
import cc.naihao.simplenotes.web.dto.ThemeVersionResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 主题管理（docs/blog-runtime-theme.md §7.1）：版本不可变，应用 / 回滚都走 activate。
 */
@RestController
@RequestMapping("/api/admin/themes")
@RequiredArgsConstructor
public class AdminThemeController {

    private final ThemeService themeService;
    private final PreviewTokenService previewTokenService;
    private final AiThemeService aiThemeService;

    @GetMapping
    @PreAuthorize("@authz.can('admin:theme')")
    public List<ThemeSummary> list() {
        return themeService.list();
    }

    @PostMapping
    @PreAuthorize("@authz.can('admin:theme:write')")
    public ThemeSummary create(@Valid @RequestBody CreateThemeRequest request) {
        return themeService.create(request);
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("@authz.can('admin:theme')")
    public List<ThemeVersionResponse> versions(@PathVariable String id) {
        return themeService.versions(id);
    }

    @GetMapping("/{id}/versions/{versionId}")
    @PreAuthorize("@authz.can('admin:theme')")
    public ThemeVersionResponse version(@PathVariable String id, @PathVariable String versionId) {
        return themeService.version(id, versionId);
    }

    @PostMapping("/{id}/versions")
    @PreAuthorize("@authz.can('admin:theme:write')")
    public ThemeVersionResponse createVersion(@PathVariable String id,
            @Valid @RequestBody CreateThemeVersionRequest request) {
        return themeService.createVersion(id, request);
    }

    @PostMapping("/{id}/versions/{versionId}/activate")
    @PreAuthorize("@authz.can('admin:theme:activate')")
    public ThemeSummary activate(@PathVariable String id, @PathVariable String versionId) {
        return themeService.activate(id, versionId);
    }

    @PostMapping("/{id}/versions/{versionId}/preview-token")
    @PreAuthorize("@authz.can('admin:theme')")
    public PreviewTokenResponse previewToken(@PathVariable String id, @PathVariable String versionId) {
        ThemeVersionResponse version = themeService.version(id, versionId);
        PreviewTokenService.IssuedToken issued = previewTokenService.issue(version.id());
        return new PreviewTokenResponse(version.id(), issued.token(), issued.expiresAt());
    }

    /**
     * AI 主题对话（流式，docs/theme-ai.md §2）：SSE 事件 token / round / done / error。
     * 无服务端记忆，会话态由前端携带；产物进临时草稿缓存，定版仍走既有 createVersion。
     */
    @PostMapping(value = "/ai-chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@authz.can('admin:theme:write')")
    public SseEmitter aiChatStream(@Valid @RequestBody AiChatRequest request) {
        return aiThemeService.stream(request, SecurityUtils.currentUserId());
    }

    /** AI 草稿整页预览 token（docs/theme-ai.md §4）：拼 {BLOG_URL}/?previewDraft=...&token=... 进 iframe */
    @PostMapping("/ai-drafts/{draftId}/preview-token")
    @PreAuthorize("@authz.can('admin:theme')")
    public PreviewTokenResponse draftPreviewToken(@PathVariable String draftId) {
        PreviewTokenService.IssuedToken issued = aiThemeService.draftPreviewToken(draftId);
        return new PreviewTokenResponse(draftId, issued.token(), issued.expiresAt());
    }
}
