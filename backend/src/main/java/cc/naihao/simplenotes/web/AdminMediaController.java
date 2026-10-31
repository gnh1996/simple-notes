package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.service.AttachmentService;
import cc.naihao.simplenotes.web.dto.AttachmentResponse;
import cc.naihao.simplenotes.web.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 媒体库接口（docs/content-model.md §6）。 */
@RestController
@RequestMapping("/api/admin/media")
@RequiredArgsConstructor
public class AdminMediaController {

    private final AttachmentService attachmentService;

    @GetMapping
    @PreAuthorize("@authz.can('admin:media')")
    public PageResponse<AttachmentResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(required = false) String keyword) {
        return attachmentService.list(page, size, keyword);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.can('admin:media:upload')")
    public AttachmentResponse upload(@RequestParam("file") MultipartFile file) {
        return attachmentService.upload(file);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('admin:media:delete')")
    public void delete(@PathVariable String id) {
        attachmentService.delete(id);
    }
}
