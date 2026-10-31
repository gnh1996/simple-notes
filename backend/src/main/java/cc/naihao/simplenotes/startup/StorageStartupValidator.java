package cc.naihao.simplenotes.startup;

import cc.naihao.simplenotes.domain.StorageMode;
import cc.naihao.simplenotes.service.AttachmentStorage;
import cc.naihao.simplenotes.service.AttachmentStorageRouter;
import cc.naihao.simplenotes.service.StorageModeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 存储启动校验（docs/attachment-storage.md §6.3）。
 *
 * <p>当前模式不可解析 / 实现缺失 / 本地目录不可写 / DB 表不可访问 / R2 凭据缺失 → 启动失败；
 * R2 网络暂不可达只记录 WARN（切换模式时 StorageAdminService 会硬校验）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageStartupValidator implements ApplicationRunner {

    private final StorageModeProvider modeProvider;
    private final AttachmentStorageRouter storageRouter;

    @Override
    public void run(ApplicationArguments args) {
        StorageMode mode = modeProvider.current();
        AttachmentStorage storage = storageRouter.forRead(mode);
        try {
            storage.validate();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("附件存储启动校验失败：mode=" + mode + "，"
                    + ex.getMessage(), ex);
        }
        log.info("附件存储启动校验通过：mode={}，available={}", mode, storageRouter.availableModes());
    }
}
