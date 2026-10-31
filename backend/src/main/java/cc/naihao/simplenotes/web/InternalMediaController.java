package cc.naihao.simplenotes.web;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.config.StorageProperties;
import cc.naihao.simplenotes.domain.Attachment;
import cc.naihao.simplenotes.domain.StorageMode;
import cc.naihao.simplenotes.service.AttachmentService;
import cc.naihao.simplenotes.service.R2PresignService;
import io.swagger.v3.oas.annotations.Hidden;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nginx auth_request 内部签名接口（docs/attachment-storage.md §7.3 方案 B）。
 *
 * <p>不对外暴露：Nginx 必须配置 {@code internal} + 共享令牌；方法内再校验一次令牌，
 * 避免误配导致任意 key 签名。
 */
@Hidden
@RestController
@RequestMapping("/api/internal/media")
@RequiredArgsConstructor
public class InternalMediaController {

    private final AttachmentService attachmentService;
    private final R2PresignService presignService;
    private final StorageProperties properties;

    @GetMapping("/presign")
    public ResponseEntity<Void> presign(
            @RequestParam("key") String key,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireToken(token);
        String normalized = normalizeKey(key);
        Attachment attachment = attachmentService.requireByStorageKey(normalized);
        if (attachment.getStorageMode() != StorageMode.OSS) {
            throw ApiException.notFound("附件不存在");
        }
        String url = presignService.presign(attachment.getStorageKey());
        String host = URI.create(url).getHost();
        return ResponseEntity.noContent()
                .header("X-R2-Url", url)
                .header("X-R2-Host", host == null ? "" : host)
                .header("Cache-Control", "no-store")
                .build();
    }

    private void requireToken(String token) {
        String expected = properties.getInternalToken();
        if (expected == null || expected.isBlank() || token == null || token.isBlank()) {
            throw ApiException.notFound("资源不存在");
        }
        boolean matches = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw ApiException.notFound("资源不存在");
        }
    }

    private static String normalizeKey(String key) {
        String value = key == null ? "" : key;
        if (value.startsWith("/oss/")) {
            value = value.substring("/oss/".length());
        }
        return value.replaceFirst("^/+", "");
    }
}
