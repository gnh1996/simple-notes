package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.site.SitePageService;
import cc.naihao.simplenotes.site.SiteSettingService;
import cc.naihao.simplenotes.web.dto.SitePageRequest;
import cc.naihao.simplenotes.web.dto.SitePageResponse;
import cc.naihao.simplenotes.web.dto.SiteSettingsResponse;
import cc.naihao.simplenotes.web.dto.UpdateSiteSettingsRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点设置与独立页面（docs/blog-runtime-theme.md §7.3 / §9）。
 */
@RestController
@RequestMapping("/api/admin/site")
@RequiredArgsConstructor
public class AdminSiteController {

    private final SiteSettingService settingService;
    private final SitePageService pageService;

    @GetMapping("/settings")
    @PreAuthorize("@authz.can('admin:site')")
    public SiteSettingsResponse settings() {
        return settingService.adminView();
    }

    @PutMapping("/settings")
    @PreAuthorize("@authz.can('admin:site:update')")
    public SiteSettingsResponse updateSettings(@Valid @RequestBody UpdateSiteSettingsRequest request) {
        return settingService.update(request);
    }

    @GetMapping("/pages")
    @PreAuthorize("@authz.can('admin:site')")
    public List<SitePageResponse> pages() {
        return pageService.list();
    }

    @PostMapping("/pages")
    @PreAuthorize("@authz.can('admin:site:update')")
    public SitePageResponse createPage(@Valid @RequestBody SitePageRequest request) {
        return pageService.create(request);
    }

    @PutMapping("/pages/{id}")
    @PreAuthorize("@authz.can('admin:site:update')")
    public SitePageResponse updatePage(@PathVariable String id,
            @Valid @RequestBody SitePageRequest request) {
        return pageService.update(id, request);
    }

    @DeleteMapping("/pages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('admin:site:update')")
    public void deletePage(@PathVariable String id) {
        pageService.delete(id);
    }
}
