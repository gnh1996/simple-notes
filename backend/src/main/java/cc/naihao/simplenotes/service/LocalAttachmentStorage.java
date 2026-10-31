package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.config.StorageProperties;
import cc.naihao.simplenotes.domain.StorageMode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 本地附件存储适配器：仅用于开发 / 单机验证（docs/attachment-storage.md §5.2）。
 *
 * <p>写入采用「同目录临时文件 + 原子移动」，避免进程中断留下半截文件；读取流式返回。
 */
@Slf4j
@Component
public class LocalAttachmentStorage implements AttachmentStorage {

    private final Path root;

    public LocalAttachmentStorage(StorageProperties properties) {
        this.root = Path.of(properties.getLocalRoot()).toAbsolutePath().normalize();
    }

    @Override
    public StorageMode mode() {
        return StorageMode.LOCAL;
    }

    @Override
    public void store(String storageKey, InputStream content, long size, FileMeta meta) {
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicMoveFailed) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("附件写入失败：" + storageKey, ex);
        }
    }

    @Override
    public StoredObject load(String storageKey) {
        Path target = resolve(storageKey);
        if (!Files.isRegularFile(target)) {
            throw ApiException.notFound("附件文件不存在");
        }
        try {
            return new StoredObject(Files.newInputStream(target, StandardOpenOption.READ),
                    Files.size(target), null, null);
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

    @Override
    public void validate() {
        try {
            Files.createDirectories(root);
            if (!Files.isWritable(root)) {
                throw new IllegalStateException("本地附件目录不可写：" + root);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("本地附件目录不可用：" + root, ex);
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
