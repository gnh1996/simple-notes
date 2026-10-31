package cc.naihao.simplenotes.domain;

import java.util.Locale;

/**
 * 附件二进制存储介质（docs/attachment-storage.md）。
 *
 * <p>行级持久化在 {@code attachment.storage_mode}：全局设置只决定新上传，读取 / 删除按行路由。
 */
public enum StorageMode {
    LOCAL,
    OSS,
    DATABASE;

    public static StorageMode parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("存储介质不能为空");
        }
        try {
            return StorageMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知存储介质：" + value);
        }
    }
}
