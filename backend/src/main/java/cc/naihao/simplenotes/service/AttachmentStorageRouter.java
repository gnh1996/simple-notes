package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.StorageMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 存储介质路由（docs/attachment-storage.md §5.3）。
 *
 * <p>写路径读全局设置；读 / 删按 attachment.storage_mode。重复 mode 在启动期 fail-fast。
 */
@Slf4j
@Component
public class AttachmentStorageRouter {

    private final Map<StorageMode, AttachmentStorage> storages = new EnumMap<>(StorageMode.class);
    private final StorageModeProvider modeProvider;

    public AttachmentStorageRouter(List<AttachmentStorage> implementations,
            StorageModeProvider modeProvider) {
        this.modeProvider = modeProvider;
        for (AttachmentStorage storage : implementations) {
            AttachmentStorage previous = storages.put(storage.mode(), storage);
            if (previous != null) {
                throw new IllegalStateException("存储介质实现重复：" + storage.mode()
                        + "（" + previous.getClass().getName() + " / "
                        + storage.getClass().getName() + "）");
            }
        }
        log.info("附件存储实现注册完成：{}", storages.keySet());
    }

    public AttachmentStorage forWrite() {
        return forRead(modeProvider.current());
    }

    public AttachmentStorage forRead(StorageMode mode) {
        AttachmentStorage storage = storages.get(mode);
        if (storage == null) {
            throw ApiException.serviceUnavailable("存储介质未启用：" + mode);
        }
        return storage;
    }

    public List<StorageMode> availableModes() {
        return new ArrayList<>(storages.keySet());
    }

    public boolean isAvailable(StorageMode mode) {
        return storages.containsKey(mode);
    }
}
