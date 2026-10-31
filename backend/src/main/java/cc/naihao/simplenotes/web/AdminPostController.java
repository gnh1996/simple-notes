package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.domain.PublishStatus;
import cc.naihao.simplenotes.service.PostService;
import cc.naihao.simplenotes.service.SyncService;
import cc.naihao.simplenotes.web.dto.CreatePostRequest;
import cc.naihao.simplenotes.web.dto.PageResponse;
import cc.naihao.simplenotes.web.dto.PostDetail;
import cc.naihao.simplenotes.web.dto.PostSummary;
import cc.naihao.simplenotes.web.dto.PublishRequest;
import cc.naihao.simplenotes.web.dto.SyncPage;
import cc.naihao.simplenotes.web.dto.UpdatePostRequest;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 博客文章管理接口（docs/content-model.md §6.2）：只操作 kind=POST。
 */
@RestController
@RequestMapping("/api/admin/posts")
@RequiredArgsConstructor
public class AdminPostController {

    private final PostService postService;
    private final SyncService syncService;

    @GetMapping
    @PreAuthorize("@authz.can('admin:post')")
    public PageResponse<PostSummary> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) PublishStatus status,
            @RequestParam(required = false) Boolean hasUnpublishedChanges) {
        return postService.list(page, size, keyword, status, hasUnpublishedChanges);
    }

    @GetMapping("/sync")
    @PreAuthorize("@authz.can('admin:post')")
    public SyncPage sync(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "200") int limit) {
        return syncService.page(NoteKind.POST, cursor, limit);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('admin:post')")
    public PostDetail get(@PathVariable String id) {
        return postService.get(id);
    }

    @PostMapping
    @PreAuthorize("@authz.can('admin:post:create')")
    public PostDetail create(@Valid @RequestBody CreatePostRequest request) {
        return postService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('admin:post:update')")
    public PostDetail update(@PathVariable String id, @Valid @RequestBody UpdatePostRequest request) {
        return postService.update(id, request);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("@authz.can('admin:post:publish')")
    public PostDetail publish(@PathVariable String id, @Valid @RequestBody PublishRequest request) {
        return postService.publish(id, request.version());
    }

    @PostMapping("/{id}/unpublish")
    @PreAuthorize("@authz.can('admin:post:unpublish')")
    public PostDetail unpublish(@PathVariable String id) {
        return postService.unpublish(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('admin:post:delete')")
    public void delete(@PathVariable String id) {
        postService.delete(id);
    }
}
