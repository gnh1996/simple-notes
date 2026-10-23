package cc.naihao.simplenotes.service;

/**
 * 附件二进制存储抽象（docs/content-model.md §3.5）。
 *
 * <p>v1 提供本地开发适配器；接入 Cloudflare R2 时新增实现并替换注入即可，
 * 数据库只保存 {@code storage_key}，正文使用稳定站内 URL。
 */
public interface AttachmentStorage {

    void store(String storageKey, byte[] bytes);

    byte[] load(String storageKey);

    void delete(String storageKey);
}
