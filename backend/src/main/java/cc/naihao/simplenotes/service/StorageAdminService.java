package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.StorageMode;
import cc.naihao.simplenotes.repository.AttachmentRepository;
import cc.naihao.simplenotes.repository.SiteSettingRepository;
import cc.naihao.simplenotes.domain.SiteSetting;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.StorageStatusResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 存储模式运行时切换与健康检查（docs/attachment-storage.md §6 / §7.5）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageAdminService {

    private final AttachmentStorageRouter storageRouter;
    private final StorageModeProvider modeProvider;
    private final AttachmentRepository attachmentRepository;
    private final SiteSettingRepository siteSettingRepository;
    private final R2ClientFactory r2Clients;

    @Transactional(readOnly = true)
    public StorageStatusResponse status() {
        StorageMode current = modeProvider.current();
        List<StorageMode> modes = storageRouter.availableModes();
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, String> health = new LinkedHashMap<>();
        for (StorageMode mode : modes) {
            counts.put(mode.name(), attachmentRepository.countByStorageMode(mode));
            health.put(mode.name(), healthOf(mode));
        }
        return new StorageStatusResponse(current.name(),
                modes.stream().map(Enum::name).toList(), counts, health);
    }

    @Transactional
    public StorageStatusResponse switchMode(String rawMode) {
        StorageMode mode;
        try {
            mode = StorageMode.parse(rawMode);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("未知存储介质：" + rawMode);
        }
        if (!storageRouter.isAvailable(mode)) {
            throw ApiException.badRequest("存储介质未启用：" + mode);
        }
        try {
            validateForSwitch(mode);
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("存储介质不可用：" + messageOf(ex));
        }

        Instant now = Instant.now();
        String operator = SecurityUtils.currentUserId();
        SiteSetting row = siteSettingRepository.findById(StorageModeProvider.SETTING_KEY)
                .orElseGet(() -> {
                    SiteSetting created = new SiteSetting();
                    created.setSettingKey(StorageModeProvider.SETTING_KEY);
                    created.setCreateTime(now);
                    created.setCreateBy(operator);
                    return created;
                });
        row.setSettingValue(mode.name());
        row.setUpdateTime(now);
        row.setUpdateBy(operator);
        siteSettingRepository.save(row);
        siteSettingRepository.flush();
        return status();
    }

    private String healthOf(StorageMode mode) {
        try {
            validateForSwitch(mode);
            return "OK";
        } catch (RuntimeException ex) {
            log.debug("存储介质健康检查失败：{}", mode, ex);
            return messageOf(ex);
        }
    }

    private void validateForSwitch(StorageMode mode) {
        if (mode == StorageMode.OSS) {
            r2Clients.validateConfig();
            r2Clients.client().headBucket(builder -> builder.bucket(r2Clients.bucket()));
            return;
        }
        storageRouter.forRead(mode).validate();
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
