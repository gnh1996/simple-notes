package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传文件类型检测（docs/attachment-storage.md §8 / §10）。
 *
 * <p>不信任浏览器声明的 Content-Type：图片按魔数识别；SVG 直接拒绝；未知类型回落
 * application/octet-stream。所有非图片类型一律强制下载，只有图片允许 inline。
 */
public final class FileTypeDetector {

    private static final int HEAD_BYTES = 1024;

    private static final Map<String, String> DECLARED_EXTENSIONS = Map.ofEntries(
            Map.entry("image/png", "png"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/webp", "webp"),
            Map.entry("image/avif", "avif"),
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/zip", "zip"),
            Map.entry("text/plain", "txt"));

    private FileTypeDetector() {
    }

    public record DetectedFile(String mimeType, String extension, boolean inline) {
    }

    public static DetectedFile detect(MultipartFile file) {
        byte[] head = readHead(file);
        String declared = normalize(file.getContentType());
        String originalName = file.getOriginalFilename();

        if (isSvg(head, declared, originalName)) {
            throw ApiException.badRequest("禁止上传 SVG 文件");
        }

        if (startsWith(head, new int[] { 0x89, 'P', 'N', 'G' })) {
            return image("image/png", "png");
        }
        if (startsWith(head, new int[] { 0xFF, 0xD8, 0xFF })) {
            return image("image/jpeg", "jpg");
        }
        if (startsWithAscii(head, "GIF87a") || startsWithAscii(head, "GIF89a")) {
            return image("image/gif", "gif");
        }
        if (startsWithAscii(head, "RIFF") && asciiAt(head, 8, "WEBP")) {
            return image("image/webp", "webp");
        }
        if (asciiAt(head, 4, "ftyp") && (asciiAt(head, 8, "avif") || asciiAt(head, 8, "avis"))) {
            return image("image/avif", "avif");
        }

        if (startsWithAscii(head, "%PDF-")) {
            return new DetectedFile("application/pdf", extension(originalName, "pdf"), false);
        }
        if (startsWith(head, new int[] { 'P', 'K', 0x03, 0x04 })) {
            return new DetectedFile("application/zip", extension(originalName, "zip"), false);
        }
        if (looksLikeHtml(head)) {
            return new DetectedFile("text/html", extension(originalName, "html"), false);
        }

        String mimeType = declared.isBlank() ? "application/octet-stream" : declared;
        // 声明为图片但魔数不匹配：按未知二进制处理，避免伪装内容被 inline。
        if (mimeType.startsWith("image/")) {
            mimeType = "application/octet-stream";
        }
        return new DetectedFile(mimeType, extension(originalName, safeDefaultExtension(mimeType)), false);
    }

    public static boolean isImageMime(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("image/") && !normalized.equals("image/svg+xml");
    }

    private static DetectedFile image(String mimeType, String extension) {
        return new DetectedFile(mimeType, extension, true);
    }

    private static byte[] readHead(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            return input.readNBytes(HEAD_BYTES);
        } catch (IOException ex) {
            throw ApiException.badRequest("读取上传文件失败");
        }
    }

    private static String normalize(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String value = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isSvg(byte[] head, String declared, String originalName) {
        if (declared.contains("svg")) {
            return true;
        }
        String lowerName = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".svg") || lowerName.endsWith(".svgz")) {
            return true;
        }
        String text = asciiLower(head);
        return text.contains("<svg") || (text.contains("<?xml") && text.contains("svg"));
    }

    private static boolean looksLikeHtml(byte[] head) {
        String text = asciiLower(head).stripLeading();
        return text.startsWith("<!doctype html")
                || text.startsWith("<html")
                || text.startsWith("<head")
                || text.startsWith("<body")
                || text.startsWith("<script")
                || text.contains("<html");
    }

    private static boolean startsWith(byte[] bytes, int[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] bytes, String prefix) {
        return asciiAt(bytes, 0, prefix);
    }

    private static boolean asciiAt(byte[] bytes, int offset, String value) {
        if (bytes.length < offset + value.length()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char expected = value.charAt(i);
            if (expected > 0x7F || (bytes[offset + i] & 0xFF) != expected) {
                return false;
            }
        }
        return true;
    }

    private static String asciiLower(byte[] bytes) {
        StringBuilder builder = new StringBuilder(Math.min(bytes.length, HEAD_BYTES));
        for (byte b : bytes) {
            int value = b & 0xFF;
            builder.append(value >= 0x20 && value <= 0x7E
                    ? Character.toLowerCase((char) value)
                    : ' ');
        }
        return builder.toString();
    }

    private static String extension(String originalName, String fallback) {
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0 && dot < originalName.length() - 1) {
                String candidate = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (candidate.matches("[a-z0-9]{1,10}")) {
                    return candidate;
                }
            }
        }
        return DECLARED_EXTENSIONS.getOrDefault(fallback, fallback);
    }

    private static String safeDefaultExtension(String mimeType) {
        return DECLARED_EXTENSIONS.getOrDefault(mimeType, "bin");
    }
}
