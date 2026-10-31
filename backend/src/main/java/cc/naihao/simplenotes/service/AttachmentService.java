package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.config.StorageProperties;
import cc.naihao.simplenotes.domain.Attachment;
import cc.naihao.simplenotes.domain.StorageMode;
import cc.naihao.simplenotes.repository.AttachmentRepository;
import cc.naihao.simplenotes.repository.NoteRepository;
import cc.naihao.simplenotes.repository.NoteRevisionRepository;
import cc.naihao.simplenotes.security.AuthPrincipal;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.AttachmentResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件 / 媒体库（docs/attachment-storage.md）。
 *
 * <p>业务调用方只依赖本服务；介质选择、字节传输和 URL 解析都在内部完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final AttachmentRepository attachmentRepository;
    private final NoteRepository noteRepository;
    private final NoteRevisionRepository noteRevisionRepository;
    private final AttachmentStorageRouter storageRouter;
    private final StorageModeProvider modeProvider;
    private final MediaUrlResolver urlResolver;
    private final R2PresignService presignService;
    private final StorageProperties properties;

    @Transactional
    public AttachmentResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择要上传的文件");
        }
        long maxBytes = properties.getMaxBytes();
        if (file.getSize() > maxBytes) {
            throw ApiException.badRequest("文件不能超过 " + (maxBytes / 1024 / 1024) + "MB");
        }

        FileTypeDetector.DetectedFile detected = FileTypeDetector.detect(file);
        StorageMode mode = modeProvider.current();
        if (mode == StorageMode.DATABASE && file.getSize() > properties.getDatabase().getMaxBytes()) {
            throw ApiException.badRequest("数据库模式单文件不能超过 "
                    + (properties.getDatabase().getMaxBytes() / 1024 / 1024) + "MB");
        }

        String storageKey = UUID.randomUUID() + "." + detected.extension();
        String originalName = safeOriginalName(file.getOriginalFilename(), storageKey);
        String storageKeyFinal = storageKey;

        Attachment attachment = new Attachment();
        attachment.setOwnerId(SecurityUtils.requirePrincipal().userId());
        attachment.setStorageMode(mode);
        attachment.setStorageKey(storageKey);
        attachment.setOriginalName(originalName);
        attachment.setMimeType(truncate(detected.mimeType(), 128));
        attachment.setSizeBytes(file.getSize());
        attachment.setCreateBy(SecurityUtils.currentUserId());
        attachmentRepository.saveAndFlush(attachment);

        AttachmentStorage storage = storageRouter.forRead(mode);
        registerUploadCompensation(storage, storageKeyFinal);

        String disposition = detected.inline()
                ? ContentDisposition.inline().filename(originalName, StandardCharsets.UTF_8).build().toString()
                : ContentDisposition.attachment().filename(originalName, StandardCharsets.UTF_8).build().toString();
        AttachmentStorage.FileMeta meta = new AttachmentStorage.FileMeta(
                detected.mimeType(), disposition, CACHE_CONTROL);
        try (InputStream input = file.getInputStream()) {
            storage.store(storageKey, input, file.getSize(), meta);
        } catch (IOException ex) {
            throw new IllegalStateException("读取上传文件失败", ex);
        }

        return toResponse(attachment);
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list() {
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        List<Attachment> attachments = principal.root()
                ? attachmentRepository.findAllByOrderByCreateTimeDesc()
                : attachmentRepository.findAllByOwnerIdOrderByCreateTimeDesc(principal.userId());
        return attachments.stream().map(this::toResponse).toList();
    }

    /** 删除前检查封面引用；正文内引用 v1 不做完整反向解析（孤儿清理 P1）。 */
    @Transactional
    public void delete(String id) {
        Attachment attachment = requireAttachment(id);
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        if (!principal.root() && !attachment.getOwnerId().equals(principal.userId())) {
            throw ApiException.notFound("附件不存在");
        }
        if (noteRepository.existsByCoverAttachmentId(id)
                || noteRevisionRepository.existsByCoverAttachmentId(id)) {
            throw ApiException.conflict("附件仍被文章封面引用，无法删除");
        }
        attachment.markDeleted(SecurityUtils.currentUserId());
        attachmentRepository.save(attachment);

        AttachmentStorage storage = storageRouter.forRead(attachment.getStorageMode());
        if (attachment.getStorageMode() == StorageMode.DATABASE) {
            // 与元数据同事务：提交失败时 blob 删除一起回滚。
            storage.delete(attachment.getStorageKey());
        } else {
            registerDeleteAfterCommit(storage, attachment.getStorageKey());
        }
    }

    /** 校验封面附件是否存在、属于内容所有者且为图片（超管可跨用户）。 */
    @Transactional(readOnly = true)
    public String validateCover(String attachmentId, String ownerId) {
        if (attachmentId == null || attachmentId.isBlank()) {
            return null;
        }
        Attachment attachment = requireAttachment(attachmentId);
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        if (!principal.root() && !attachment.getOwnerId().equals(ownerId)) {
            throw ApiException.badRequest("封面附件不属于当前内容");
        }
        if (!FileTypeDetector.isImageMime(attachment.getMimeType())) {
            throw ApiException.badRequest("封面必须是图片");
        }
        return attachment.getId();
    }

    /**
     * 媒体读取解析：本地 / DB 返回流；OSS 按配置返回 Nginx 直链或预签名 302。
     */
    @Transactional(readOnly = true)
    public MediaResolution resolve(String id) {
        Attachment attachment = requireAttachment(id);
        if (attachment.getStorageMode() == StorageMode.OSS) {
            String edge = properties.getR2().getEdge();
            if ("backend".equalsIgnoreCase(edge)) {
                return stream(attachment);
            }
            String signing = properties.getR2().getSigning();
            if ("backend-302".equalsIgnoreCase(signing)) {
                return new MediaRedirect(presignService.presign(attachment.getStorageKey()));
            }
            return new MediaRedirect(urlResolver.ossUrl(attachment.getStorageKey()));
        }
        return stream(attachment);
    }

    @Transactional(readOnly = true)
    public Attachment requireByStorageKey(String storageKey) {
        return attachmentRepository.findByStorageKey(storageKey)
                .orElseThrow(() -> ApiException.notFound("附件不存在"));
    }

    private MediaResolution stream(Attachment attachment) {
        AttachmentStorage.StoredObject object = storageRouter.forRead(attachment.getStorageMode())
                .load(attachment.getStorageKey());
        return new MediaStream(object.content(), attachment.getSizeBytes(), attachment.getMimeType(),
                attachment.getOriginalName(), FileTypeDetector.isImageMime(attachment.getMimeType()));
    }

    private Attachment requireAttachment(String id) {
        return attachmentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("附件不存在"));
    }

    private AttachmentResponse toResponse(Attachment attachment) {
        return new AttachmentResponse(attachment.getId(), urlResolver.publicUrl(attachment),
                attachment.getOriginalName(), attachment.getMimeType(), attachment.getSizeBytes(),
                attachment.getWidth(), attachment.getHeight(), attachment.getAltText(),
                attachment.getCreateTime());
    }

    private void registerUploadCompensation(AttachmentStorage storage, String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        storage.delete(storageKey);
                    } catch (RuntimeException ex) {
                        log.warn("上传回滚后清理附件失败：{}", storageKey, ex);
                    }
                }
            }
        });
    }

    private void registerDeleteAfterCommit(AttachmentStorage storage, String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            storage.delete(storageKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.delete(storageKey);
                } catch (RuntimeException ex) {
                    log.warn("附件删除失败（孤儿清理 P1）：{}", storageKey, ex);
                }
            }
        });
    }

    private static String safeOriginalName(String name, String fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String normalized = name.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String value = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return truncate(value, 255);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public sealed interface MediaResolution permits MediaStream, MediaRedirect {
    }

    public record MediaStream(InputStream content, long size, String mimeType, String originalName,
            boolean inline) implements MediaResolution {
    }

    public record MediaRedirect(String location) implements MediaResolution {
    }
}
