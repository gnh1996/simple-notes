package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.service.AttachmentService;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 媒体读取（docs/attachment-storage.md §7）。
 *
 * <p>本地 / DB 由后端流式返回；OSS 的稳定端点返回 302（Nginx 直链或预签名）。
 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private static final CacheControl MEDIA_CACHE =
            CacheControl.maxAge(Duration.ofDays(30)).cachePublic();

    private final AttachmentService attachmentService;

    @GetMapping("/{id}")
    public ResponseEntity<StreamingResponseBody> get(@PathVariable String id) {
        return response(attachmentService.resolve(id));
    }

    private ResponseEntity<StreamingResponseBody> response(AttachmentService.MediaResolution resolution) {
        return switch (resolution) {
            case AttachmentService.MediaRedirect redirect -> ResponseEntity
                    .<StreamingResponseBody>status(HttpStatus.FOUND)
                    .location(URI.create(redirect.location()))
                    .cacheControl(CacheControl.noStore())
                    .build();
            case AttachmentService.MediaStream stream -> stream(stream);
        };
    }

    private ResponseEntity<StreamingResponseBody> stream(AttachmentService.MediaStream stream) {
        ContentDisposition disposition = (stream.inline()
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(stream.originalName(), StandardCharsets.UTF_8)
                .build();
        StreamingResponseBody body = output -> {
            try (InputStream input = stream.content()) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .contentType(mediaType(stream.mimeType()))
                .contentLength(stream.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(MEDIA_CACHE)
                .body(body);
    }

    private static MediaType mediaType(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (IllegalArgumentException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
