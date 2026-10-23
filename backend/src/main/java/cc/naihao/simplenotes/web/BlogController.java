package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.service.BlogService;
import cc.naihao.simplenotes.web.dto.ArchiveResponse;
import cc.naihao.simplenotes.web.dto.PageResponse;
import cc.naihao.simplenotes.web.dto.PublishedPostDetail;
import cc.naihao.simplenotes.web.dto.PublishedPostSummary;
import cc.naihao.simplenotes.web.dto.TagCountResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开博客接口（docs/content-model.md §6.3）：匿名可读，只返回已发布快照。
 */
@RestController
@RequestMapping("/api/blog")
@RequiredArgsConstructor
public class BlogController {

    private final BlogService blogService;

    @GetMapping("/posts")
    public PageResponse<PublishedPostSummary> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String tagId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return blogService.list(page, size, tagId, year, month);
    }

    @GetMapping("/posts/{slug}")
    public PublishedPostDetail detail(@PathVariable String slug) {
        return blogService.detail(slug);
    }

    @GetMapping("/tags")
    public List<TagCountResponse> tags() {
        return blogService.tags();
    }

    @GetMapping("/archives")
    public List<ArchiveResponse> archives() {
        return blogService.archives();
    }

    @GetMapping("/search")
    public PageResponse<PublishedPostSummary> search(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q) {
        return blogService.search(page, size, q);
    }
}
