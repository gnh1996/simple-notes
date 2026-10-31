package cc.naihao.simplenotes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.naihao.simplenotes.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.mock.web.MockMultipartFile;

class FileTypeDetectorTest {

    @Test
    void detectsPngByMagic() {
        MultipartFile file = new MockMultipartFile("file", "pixel.png", "image/png",
                new byte[] { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 });

        FileTypeDetector.DetectedFile detected = FileTypeDetector.detect(file);

        assertThat(detected.mimeType()).isEqualTo("image/png");
        assertThat(detected.extension()).isEqualTo("png");
        assertThat(detected.inline()).isTrue();
    }

    @Test
    void rejectsSvgByDeclaredType() {
        MultipartFile file = new MockMultipartFile("file", "icon.svg", "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes());

        assertThatThrownBy(() -> FileTypeDetector.detect(file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("SVG");
    }

    @Test
    void rejectsSvgByContent() {
        MultipartFile file = new MockMultipartFile("file", "looks-like.png", "image/png",
                "<?xml version=\"1.0\"?><svg></svg>".getBytes());

        assertThatThrownBy(() -> FileTypeDetector.detect(file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("SVG");
    }

    @Test
    void forcesHtmlDownload() {
        MultipartFile file = new MockMultipartFile("file", "page.html", "text/html",
                "<!doctype html><html><body>x</body></html>".getBytes());

        FileTypeDetector.DetectedFile detected = FileTypeDetector.detect(file);

        assertThat(detected.mimeType()).isEqualTo("text/html");
        assertThat(detected.inline()).isFalse();
    }

    @Test
    void doesNotTrustFakeImageContentType() {
        MultipartFile file = new MockMultipartFile("file", "fake.png", "image/png",
                "not an image".getBytes());

        FileTypeDetector.DetectedFile detected = FileTypeDetector.detect(file);

        assertThat(detected.mimeType()).isEqualTo("application/octet-stream");
        assertThat(detected.inline()).isFalse();
    }
}
