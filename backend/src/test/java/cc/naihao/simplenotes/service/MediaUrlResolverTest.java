package cc.naihao.simplenotes.service;

import static org.assertj.core.api.Assertions.assertThat;

import cc.naihao.simplenotes.config.StorageProperties;
import cc.naihao.simplenotes.domain.Attachment;
import cc.naihao.simplenotes.domain.StorageMode;
import org.junit.jupiter.api.Test;

class MediaUrlResolverTest {

    @Test
    void directModeUsesOssPathForOssOnly() {
        StorageProperties properties = new StorageProperties();
        properties.setUrlMode(StorageProperties.UrlMode.DIRECT);
        properties.setPublicBaseUrl("https://media.example.com/");
        MediaUrlResolver resolver = new MediaUrlResolver(properties);

        Attachment oss = new Attachment();
        oss.setStorageMode(StorageMode.OSS);
        oss.setStorageKey("abc.png");
        assertThat(resolver.publicUrl(oss)).isEqualTo("https://media.example.com/oss/abc.png");

        Attachment local = new Attachment();
        local.setStorageMode(StorageMode.LOCAL);
        assertThat(resolver.publicUrl(local)).isEqualTo("https://media.example.com/api/media/" + local.getId());
    }

    @Test
    void stableModeUsesStablePathForOss() {
        StorageProperties properties = new StorageProperties();
        properties.setUrlMode(StorageProperties.UrlMode.STABLE);
        MediaUrlResolver resolver = new MediaUrlResolver(properties);

        Attachment oss = new Attachment();
        oss.setStorageMode(StorageMode.OSS);
        oss.setStorageKey("abc.png");
        assertThat(resolver.publicUrl(oss)).isEqualTo("/api/media/" + oss.getId());
    }

    @Test
    void backendEdgeFallsBackToStablePathForOss() {
        StorageProperties properties = new StorageProperties();
        properties.setUrlMode(StorageProperties.UrlMode.DIRECT);
        properties.getR2().setEdge("backend");
        MediaUrlResolver resolver = new MediaUrlResolver(properties);

        Attachment oss = new Attachment();
        oss.setStorageMode(StorageMode.OSS);
        oss.setStorageKey("abc.png");
        assertThat(resolver.publicUrl(oss)).isEqualTo("/api/media/" + oss.getId());
    }
}
