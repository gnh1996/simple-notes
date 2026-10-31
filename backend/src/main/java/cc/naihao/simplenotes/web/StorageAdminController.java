package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.service.StorageAdminService;
import cc.naihao.simplenotes.web.dto.StorageStatusResponse;
import cc.naihao.simplenotes.web.dto.UpdateStorageModeRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 附件存储设置（docs/attachment-storage.md §7.5）。 */
@RestController
@RequestMapping("/api/admin/media/storage")
@RequiredArgsConstructor
public class StorageAdminController {

    private final StorageAdminService storageAdminService;

    @GetMapping
    @PreAuthorize("@authz.can('admin:media')")
    public StorageStatusResponse status() {
        return storageAdminService.status();
    }

    @PutMapping
    @PreAuthorize("@authz.can('admin:media:storage')")
    public StorageStatusResponse switchMode(@Valid @RequestBody UpdateStorageModeRequest request) {
        return storageAdminService.switchMode(request.mode());
    }
}
