package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.config.StorageProperties;
import cc.naihao.simplenotes.domain.Attachment;
import cc.naihao.simplenotes.domain.StorageMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 附件对访问 URL（docs/attachment-storage.md §7.1）。
 *
 * <p>DIRECT：OSS 返回 /oss/{key}，本地 / DB 返回 /api/media/{id}；
 * STABLE：恒返回 /api/media/{id}（OSS 由稳定端点 302）。
 */
@Component
@RequiredArgsConstructor
public class MediaUrlResolver {

    private final StorageProperties properties;

    public String publicUrl(Attachment attachment) {
        // 无 Nginx 的本地开发用 edge=backend：OSS 也走稳定端点，由后端流式读取。
        if (properties.getUrlMode() == StorageProperties.UrlMode.DIRECT
                && attachment.getStorageMode() == StorageMode.OSS
                && !"backend".equalsIgnoreCase(properties.getR2().getEdge())) {
            return ossUrl(attachment.getStorageKey());
        }
        return base() + "/api/media/" + attachment.getId();
    }

    public String ossUrl(String storageKey) {
        return base() + "/oss/" + storageKey;
    }

    public String mediaPath(String attachmentId) {
        return base() + "/api/media/" + attachmentId;
    }

    private String base() {
        String base = properties.getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            return "";
        }
        return base.replaceAll("/+$", "");
    }
}
