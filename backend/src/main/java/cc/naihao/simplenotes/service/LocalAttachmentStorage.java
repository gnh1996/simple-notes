package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地附件存储适配器：仅用于 v1 开发 / 单机验证。
 *
 * <p>生产按 docs/tech-stack.md 使用 Cloudflare R2；接入时新增 {@link AttachmentStorage} 实现，
 * 本类保留为本地开发 fallback。
 */
@Slf4j
@Component
public class LocalAttachmentStorage implements AttachmentStorage {

    private final Path root;

    public LocalAttachmentStorage(@Value("${simple-notes.storage.local-root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void store(String storageKey, byte[] bytes) {
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException ex) {
            throw new IllegalStateException("附件写入失败：" + storageKey, ex);
        }
    }

    @Override
    public byte[] load(String storageKey) {
        Path target = resolve(storageKey);
        if (!Files.isRegularFile(target)) {
            throw ApiException.notFound("附件文件不存在");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException ex) {
            throw new IllegalStateException("附件读取失败：" + storageKey, ex);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException ex) {
            log.warn("附件文件删除失败：{}", storageKey, ex);
        }
    }

    private Path resolve(String storageKey) {
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法附件 key");
        }
        return target;
    }
}
