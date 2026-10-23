package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.service.NoteFolderService;
import cc.naihao.simplenotes.web.dto.NoteFolderRequest;
import cc.naihao.simplenotes.web.dto.NoteFolderResponse;
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

/** 记事本文件夹接口（docs/content-model.md §3.4）：借随手记权限码守护。 */
@RestController
@RequestMapping("/api/admin/note-folders")
@RequiredArgsConstructor
public class AdminNoteFolderController {

    private final NoteFolderService folderService;

    @GetMapping
    @PreAuthorize("@authz.can('admin:note')")
    public List<NoteFolderResponse> list() {
        return folderService.list();
    }

    @PostMapping
    @PreAuthorize("@authz.can('admin:note:create')")
    public NoteFolderResponse create(@Valid @RequestBody NoteFolderRequest request) {
        return folderService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('admin:note:update')")
    public NoteFolderResponse update(@PathVariable String id, @Valid @RequestBody NoteFolderRequest request) {
        return folderService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('admin:note:delete')")
    public void delete(@PathVariable String id) {
        folderService.delete(id);
    }
}
