package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.config.StorageProperties;
import cc.naihao.simplenotes.domain.StorageMode;
import cc.naihao.simplenotes.repository.SiteSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 当前「新上传」介质来源（docs/attachment-storage.md §6）。
 *
 * <p>优先级：site_setting.storage.mode > simple-notes.storage.mode > LOCAL。
 * 读取路径不经过这里：历史附件按 attachment.storage_mode 路由。
 */
@Component
@RequiredArgsConstructor
public class StorageModeProvider {

    public static final String SETTING_KEY = "storage.mode";

    private final SiteSettingRepository siteSettingRepository;
    private final StorageProperties properties;

    @Transactional(readOnly = true)
    public StorageMode current() {
        return siteSettingRepository.findById(SETTING_KEY)
                .map(row -> parse(row.getSettingValue(), "site_setting.storage.mode"))
                .orElseGet(() -> parse(properties.getMode(), "simple-notes.storage.mode"));
    }

    private static StorageMode parse(String value, String source) {
        try {
            return StorageMode.parse(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(source + " 配置非法：" + value, ex);
        }
    }
}
