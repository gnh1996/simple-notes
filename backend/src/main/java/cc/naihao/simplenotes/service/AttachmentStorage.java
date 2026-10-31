package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.domain.StorageMode;
import java.io.InputStream;

/**
 * 附件二进制存储抽象（docs/attachment-storage.md §5）。
 *
 * <p>实现只处理 storageKey，不感知 attachment.id / owner / 权限；流由调用方打开和关闭。
 */
public interface AttachmentStorage {

    StorageMode mode();

    void store(String storageKey, InputStream content, long size, FileMeta meta);

    StoredObject load(String storageKey);

    void delete(String storageKey);

    /** 启动 / 切换时校验配置与可用性；默认无操作。 */
    default void validate() {
    }

    record FileMeta(String contentType, String contentDisposition, String cacheControl) {
    }

    record StoredObject(InputStream content, long size, String contentType, String contentDisposition) {
    }
}
