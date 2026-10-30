package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.service.BlogService;
import cc.naihao.simplenotes.service.BlogSortDirection;
import cc.naihao.simplenotes.service.BlogSortField;
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
 * 公开博客接口（docs/content-model.md §6.4）：匿名可读，只返回已发布快照。
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
            @RequestParam(required = false) Integer month,
            @RequestParam(defaultValue = "true") boolean pinnedFirst,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "displayAt") String sortBy) {
        return blogService.list(page, size, tagId, year, month, pinnedFirst,
                BlogSortDirection.from(direction), BlogSortField.from(sortBy));
    }

    @GetMapping("/posts/{slug}")
    public PublishedPostDetail detail(
            @PathVariable String slug,
            @RequestParam(defaultValue = "true") boolean pinnedFirst,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "displayAt") String sortBy,
            @RequestParam(required = false) String tagId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String q) {
        return blogService.detail(slug, pinnedFirst, BlogSortDirection.from(direction),
                BlogSortField.from(sortBy), tagId, year, month, q);
    }

    @GetMapping("/tags")
    public List<TagCountResponse> tags() {
        return blogService.tags();
    }

    @GetMapping("/archives")
    public List<ArchiveResponse> archives(
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "displayAt") String sortBy) {
        return blogService.archives(BlogSortField.from(sortBy), BlogSortDirection.from(direction));
    }

    @GetMapping("/search")
    public PageResponse<PublishedPostSummary> search(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "true") boolean pinnedFirst,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "displayAt") String sortBy) {
        return blogService.search(page, size, q, pinnedFirst,
                BlogSortDirection.from(direction), BlogSortField.from(sortBy));
    }
}
