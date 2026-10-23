package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.service.AttachmentService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 稳定媒体 URL（docs/content-model.md §3.5）：{@code /api/media/{id}}，匿名可读。
 * 公开博客正文直接引用该地址，替换 R2 / CDN 时无需改历史 Markdown。
 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final AttachmentService attachmentService;

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable String id) {
        AttachmentService.MediaContent content = attachmentService.publicContent(id);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(content.mimeType());
        } catch (IllegalArgumentException ex) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(content.originalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(content.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(content.bytes());
    }
}
