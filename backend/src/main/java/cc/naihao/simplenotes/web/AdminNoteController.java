package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.service.NoteService;
import cc.naihao.simplenotes.service.PostService;
import cc.naihao.simplenotes.service.SyncService;
import cc.naihao.simplenotes.web.dto.CreateNoteRequest;
import cc.naihao.simplenotes.web.dto.NoteDetail;
import cc.naihao.simplenotes.web.dto.NoteSummary;
import cc.naihao.simplenotes.web.dto.PageResponse;
import cc.naihao.simplenotes.web.dto.PostDetail;
import cc.naihao.simplenotes.web.dto.SyncPage;
import cc.naihao.simplenotes.web.dto.UpdateNoteRequest;
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
 * 随手记接口（docs/content-model.md §6.1）：只操作 kind=NOTE。
 */
@RestController
@RequestMapping("/api/admin/notes")
@RequiredArgsConstructor
public class AdminNoteController {

    private final NoteService noteService;
    private final PostService postService;
    private final SyncService syncService;

    @GetMapping
    @PreAuthorize("@authz.can('admin:note')")
    public PageResponse<NoteSummary> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String folderId,
            @RequestParam(required = false) String tagId,
            @RequestParam(defaultValue = "false") boolean pinnedOnly) {
        return noteService.list(page, size, keyword, folderId, tagId, pinnedOnly);
    }

    @GetMapping("/sync")
    @PreAuthorize("@authz.can('admin:note')")
    public SyncPage sync(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "200") int limit) {
        return syncService.page(NoteKind.NOTE, cursor, limit);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('admin:note')")
    public NoteDetail get(@PathVariable String id) {
        return noteService.get(id);
    }

    @PostMapping
    @PreAuthorize("@authz.can('admin:note:create')")
    public NoteDetail create(@Valid @RequestBody CreateNoteRequest request) {
        return noteService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('admin:note:update')")
    public NoteDetail update(@PathVariable String id, @Valid @RequestBody UpdateNoteRequest request) {
        return noteService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('admin:note:delete')")
    public void delete(@PathVariable String id) {
        noteService.delete(id);
    }

    /** 笔记转博客草稿；使用 admin:post:create 权限，不提供反向操作。 */
    @PostMapping("/{id}/promote")
    @PreAuthorize("@authz.can('admin:post:create')")
    public PostDetail promote(@PathVariable String id) {
        return postService.promoteFromNote(id);
    }
}
