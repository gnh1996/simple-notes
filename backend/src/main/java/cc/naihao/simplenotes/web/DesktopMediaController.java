package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.service.AttachmentService;
import cc.naihao.simplenotes.web.dto.AttachmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 桌面端媒体上传（docs/desktop-media.md）。
 *
 * <p>桌面会话不能访问 {@code /api/admin/**}，因此单独暴露最小上传接口；
 * 文件校验、存储介质选择与 URL 解析全部复用 {@link AttachmentService}。
 */
@RestController
@RequestMapping("/api/desktop/media")
@RequiredArgsConstructor
public class DesktopMediaController {

    private final AttachmentService attachmentService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.can('desktop:media:upload')")
    public AttachmentResponse upload(@RequestParam("file") MultipartFile file) {
        return attachmentService.upload(file);
    }
}
