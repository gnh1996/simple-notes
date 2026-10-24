package cc.naihao.simplenotes.site;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.SitePage;
import cc.naihao.simplenotes.repository.SitePageRepository;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.SitePageRequest;
import cc.naihao.simplenotes.web.dto.SitePageResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 独立页面（docs/blog-runtime-theme.md §6.3）：保存即线上，不做发布快照。
 *
 * <p>与文章刻意不同：站点家具不需要“草稿 / 发布”两态；误改依靠审计字段与版本控制兜底。
 */
@Service
@RequiredArgsConstructor
public class SitePageService {

    private final SitePageRepository repository;

    @Transactional(readOnly = true)
    public List<SitePageResponse> list() {
        return repository.findAllByOrderBySortAscCreateTimeAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SitePageResponse get(String slug) {
        return repository.findBySlugIgnoreCase(slug)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("页面不存在"));
    }

    @Transactional
    public SitePageResponse create(SitePageRequest request) {
        String slug = normalizeSlug(request.slug());
        repository.findBySlugIgnoreCase(slug).ifPresent(existing -> {
            throw ApiException.conflict("slug 已被占用");
        });
        SitePage page = new SitePage();
        apply(page, request, slug);
        page.setCreateBy(SecurityUtils.currentUserId());
        repository.saveAndFlush(page);
        return toResponse(page);
    }

    @Transactional
    public SitePageResponse update(String id, SitePageRequest request) {
        SitePage page = requirePage(id);
        String slug = normalizeSlug(request.slug());
        repository.findBySlugIgnoreCase(slug)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw ApiException.conflict("slug 已被占用");
                });
        apply(page, request, slug);
        page.setUpdateBy(SecurityUtils.currentUserId());
        repository.saveAndFlush(page);
        return toResponse(page);
    }

    @Transactional
    public void delete(String id) {
        SitePage page = requirePage(id);
        page.markDeleted(SecurityUtils.currentUserId());
        repository.save(page);
        repository.flush();
    }

    // ---- 内部 ----

    private void apply(SitePage page, SitePageRequest request, String slug) {
        page.setSlug(slug);
        page.setTitle(request.title().trim());
        page.setContent(request.content());
        page.setVisible((short) (request.visible() ? 1 : 0));
        page.setSort(request.sort());
    }

    private String normalizeSlug(String slug) {
        return slug.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private SitePage requirePage(String id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("页面不存在"));
    }

    private SitePageResponse toResponse(SitePage page) {
        return new SitePageResponse(
                page.getId(),
                page.getSlug(),
                page.getTitle(),
                page.getContent(),
                page.getVisible() == 1,
                page.getSort(),
                page.getUpdateTime() == null ? page.getCreateTime() : page.getUpdateTime());
    }
}
