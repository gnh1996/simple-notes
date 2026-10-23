package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.service.TrashService;
import cc.naihao.simplenotes.web.dto.TrashItemResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 回收站接口（docs/content-model.md §4.5）：笔记 / 文章分资源，便于分别套权限码。 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminTrashController {

    private final TrashService trashService;

    @GetMapping("/notes/trash")
    @PreAuthorize("@authz.can('admin:note:delete')")
    public List<TrashItemResponse> noteTrash() {
        return trashService.list(NoteKind.NOTE);
    }

    @PostMapping("/notes/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('admin:note:delete')")
    public void restoreNote(@PathVariable String id) {
        trashService.restoreNote(id);
    }

    @GetMapping("/posts/trash")
    @PreAuthorize("@authz.can('admin:post:delete')")
    public List<TrashItemResponse> postTrash() {
        return trashService.list(NoteKind.POST);
    }

    @PostMapping("/posts/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('admin:post:delete')")
    public void restorePost(@PathVariable String id) {
        trashService.restorePost(id);
    }
}
