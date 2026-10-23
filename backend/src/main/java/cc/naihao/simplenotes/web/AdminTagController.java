package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.service.TagAdminService;
import cc.naihao.simplenotes.web.dto.PageResponse;
import cc.naihao.simplenotes.web.dto.TagAdminSummary;
import cc.naihao.simplenotes.web.dto.TagAssignRequest;
import cc.naihao.simplenotes.web.dto.TagBrief;
import cc.naihao.simplenotes.web.dto.TagContentCandidate;
import cc.naihao.simplenotes.web.dto.TagContentResponse;
import cc.naihao.simplenotes.web.dto.TagNameRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标签管理接口（docs/content-model.md §3.3）。
 *
 * <p>标签为全局分类；改名 / 删除影响所有当前内容和已发布快照的展示。
 * 给已发布文章分配标签只写当前草稿，线上需“更新发布”。
 */
@RestController
@RequestMapping("/api/admin/tags")
@RequiredArgsConstructor
public class AdminTagController {

    private final TagAdminService tagAdminService;

    /** 编辑器联想：笔记 / 文章编辑权限任一即可读取标签选项。 */
    @GetMapping("/options")
    @PreAuthorize("@authz.canAny('admin:note', 'admin:post')")
    public List<TagBrief> options() {
        return tagAdminService.options();
    }

    @GetMapping
    @PreAuthorize("@authz.can('admin:tag')")
    public PageResponse<TagAdminSummary> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return tagAdminService.list(page, size, keyword);
    }

    @PostMapping
    @PreAuthorize("@authz.can('admin:tag:create')")
    public TagAdminSummary create(@Valid @RequestBody TagNameRequest request) {
        return tagAdminService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('admin:tag:update')")
    public TagAdminSummary rename(@PathVariable String id, @Valid @RequestBody TagNameRequest request) {
        return tagAdminService.rename(id, request);
    }

    /** 有使用量时必须 force=true；前端确认警告后重试。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('admin:tag:delete')")
    public void delete(@PathVariable String id, @RequestParam(defaultValue = "false") boolean force) {
        tagAdminService.delete(id, force);
    }

    @GetMapping("/{id}/contents")
    @PreAuthorize("@authz.can('admin:tag')")
    public PageResponse<TagContentResponse> contents(
            @PathVariable String id,
            @RequestParam NoteKind kind,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String keyword) {
        return tagAdminService.contents(id, kind, page, size, keyword);
    }

    @GetMapping("/{id}/candidates")
    @PreAuthorize("@authz.can('admin:tag:assign')")
    public PageResponse<TagContentCandidate> candidates(
            @PathVariable String id,
            @RequestParam NoteKind kind,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String keyword) {
        return tagAdminService.candidates(id, kind, page, size, keyword);
    }

    @PostMapping("/{id}/contents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('admin:tag:assign')")
    public void assign(@PathVariable String id, @Valid @RequestBody TagAssignRequest request) {
        tagAdminService.assign(id, request);
    }

    @DeleteMapping("/{id}/contents/{contentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('admin:tag:assign')")
    public void remove(@PathVariable String id, @PathVariable String contentId) {
        tagAdminService.remove(id, contentId);
    }
}
