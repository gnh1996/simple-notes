package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.site.SitePageService;
import cc.naihao.simplenotes.site.SiteSettingService;
import cc.naihao.simplenotes.web.dto.SitePageResponse;
import cc.naihao.simplenotes.web.dto.SiteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开站点配置与独立页面（docs/blog-runtime-theme.md §9）：匿名可读。
 *
 * <p>页面组装（HTML）由后续的 BlogPageController 负责；这里提供 runtime 与未来的
 * 桌面端可复用的 JSON 读取入口。
 */
@RestController
@RequestMapping("/api/blog")
@RequiredArgsConstructor
public class BlogSiteController {

    private final SiteSettingService settingService;
    private final SitePageService pageService;

    @GetMapping("/site")
    public SiteResponse site() {
        return settingService.publicSite();
    }

    @GetMapping("/pages/{slug}")
    public SitePageResponse page(@PathVariable String slug) {
        return pageService.get(slug);
    }
}
