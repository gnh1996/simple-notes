package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.service.NoteFolderService;
import cc.naihao.simplenotes.service.NoteService;
import cc.naihao.simplenotes.service.PostService;
import cc.naihao.simplenotes.service.SyncService;
import cc.naihao.simplenotes.web.dto.CreateNoteRequest;
import cc.naihao.simplenotes.web.dto.CreatePostRequest;
import cc.naihao.simplenotes.web.dto.NoteDetail;
import cc.naihao.simplenotes.web.dto.NoteFolderRequest;
import cc.naihao.simplenotes.web.dto.NoteFolderResponse;
import cc.naihao.simplenotes.web.dto.PostDetail;
import cc.naihao.simplenotes.web.dto.SyncPage;
import cc.naihao.simplenotes.web.dto.UpdateNoteRequest;
import cc.naihao.simplenotes.web.dto.UpdatePostRequest;
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
 * 桌面端内容接口（docs/desktop-sync.md §5）。
 *
 * <p>会话端为 {@code DESKTOP}，因此不能复用 {@code /api/admin/**}
 * （{@code SessionJwtFilter} 会校验会话端与路径域一致）；这里只暴露同步所需的最小读接口。
 * 编辑 / 推送 / 删除随 M1–M3 追加。
 */
@RestController
@RequestMapping("/api/desktop")
@RequiredArgsConstructor
public class DesktopContentController {

    private final NoteService noteService;
    private final PostService postService;
    private final NoteFolderService folderService;
    private final SyncService syncService;

    @GetMapping("/notes/sync")
    @PreAuthorize("@authz.can('desktop:note')")
    public SyncPage syncNotes(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "200") int limit) {
        return syncService.page(NoteKind.NOTE, cursor, limit);
    }

    @GetMapping("/notes/{id}")
    @PreAuthorize("@authz.can('desktop:note')")
    public NoteDetail getNote(@PathVariable String id) {
        return noteService.get(id);
    }

    @GetMapping("/note-folders")
    @PreAuthorize("@authz.can('desktop:note')")
    public List<NoteFolderResponse> folders() {
        return folderService.list();
    }

    @GetMapping("/posts/sync")
    @PreAuthorize("@authz.can('desktop:post')")
    public SyncPage syncPosts(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "200") int limit) {
        return syncService.page(NoteKind.POST, cursor, limit);
    }

    @GetMapping("/posts/{id}")
    @PreAuthorize("@authz.can('desktop:post')")
    public PostDetail getPost(@PathVariable String id) {
        return postService.get(id);
    }

    @PutMapping("/notes/{id}")
    @PreAuthorize("@authz.can('desktop:note:update')")
    public NoteDetail updateNote(@PathVariable String id, @Valid @RequestBody UpdateNoteRequest request) {
        return noteService.update(id, request);
    }

    @PutMapping("/posts/{id}")
    @PreAuthorize("@authz.can('desktop:post:update')")
    public PostDetail updatePost(@PathVariable String id, @Valid @RequestBody UpdatePostRequest request) {
        return postService.update(id, request);
    }

    @DeleteMapping("/notes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('desktop:note:delete')")
    public void deleteNote(@PathVariable String id) {
        noteService.delete(id);
    }

    @DeleteMapping("/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('desktop:post:delete')")
    public void deletePost(@PathVariable String id) {
        postService.delete(id);
    }

    @PostMapping("/notes")
    @PreAuthorize("@authz.can('desktop:note:create')")
    public NoteDetail createNote(@Valid @RequestBody CreateNoteRequest request) {
        return noteService.create(request);
    }

    @PostMapping("/posts")
    @PreAuthorize("@authz.can('desktop:post:create')")
    public PostDetail createPost(@Valid @RequestBody CreatePostRequest request) {
        return postService.create(request);
    }

    @PostMapping("/note-folders")
    @PreAuthorize("@authz.can('desktop:folder:write')")
    public NoteFolderResponse createFolder(@Valid @RequestBody NoteFolderRequest request) {
        return folderService.create(request);
    }

    @PutMapping("/note-folders/{id}")
    @PreAuthorize("@authz.can('desktop:folder:write')")
    public NoteFolderResponse renameFolder(@PathVariable String id,
            @Valid @RequestBody NoteFolderRequest request) {
        return folderService.update(id, request);
    }

    @DeleteMapping("/note-folders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('desktop:folder:write')")
    public void deleteFolder(@PathVariable String id) {
        folderService.delete(id);
    }
}
