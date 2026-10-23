package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.Attachment;
import cc.naihao.simplenotes.repository.AttachmentRepository;
import cc.naihao.simplenotes.repository.NoteRepository;
import cc.naihao.simplenotes.repository.NoteRevisionRepository;
import cc.naihao.simplenotes.security.AuthPrincipal;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.AttachmentResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件 / 媒体库（docs/content-model.md §3.5）。
 *
 * <p>v1 使用本地存储适配器；数据库只保存 storageKey，公开 URL 固定为 {@code /api/media/{id}}。
 */
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Map<String, String> IMAGE_EXTENSIONS = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/gif", "gif",
            "image/webp", "webp",
            "image/avif", "avif");

    private final AttachmentRepository attachmentRepository;
    private final NoteRepository noteRepository;
    private final NoteRevisionRepository noteRevisionRepository;
    private final AttachmentStorage storage;

    @Transactional
    public AttachmentResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择要上传的文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("图片不能超过 10MB");
        }
        String mimeType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String extension = IMAGE_EXTENSIONS.get(mimeType);
        if (extension == null) {
            throw ApiException.badRequest("仅支持 PNG / JPEG / GIF / WebP / AVIF 图片");
        }

        String storageKey = UUID.randomUUID() + "." + extension;
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("读取上传文件失败", ex);
        }
        storage.store(storageKey, bytes);

        Attachment attachment = new Attachment();
        attachment.setOwnerId(SecurityUtils.requirePrincipal().userId());
        attachment.setStorageKey(storageKey);
        attachment.setOriginalName(file.getOriginalFilename() == null ? "image." + extension
                : file.getOriginalFilename());
        attachment.setMimeType(mimeType);
        attachment.setSizeBytes(file.getSize());
        attachment.setCreateBy(SecurityUtils.currentUserId());
        attachmentRepository.save(attachment);
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
        storage.delete(attachment.getStorageKey());
    }

    /** 校验封面附件是否存在且属于内容所有者（超管可跨用户）。 */
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
        return attachment.getId();
    }

    /** 公开媒体读取：随机 UUID 不可枚举；草稿图片同为公开 URL（个人项目接受，见设计文档 §13）。 */
    @Transactional(readOnly = true)
    public MediaContent publicContent(String id) {
        Attachment attachment = requireAttachment(id);
        return new MediaContent(attachment.getMimeType(), attachment.getOriginalName(),
                storage.load(attachment.getStorageKey()));
    }

    private Attachment requireAttachment(String id) {
        return attachmentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("附件不存在"));
    }

    private AttachmentResponse toResponse(Attachment attachment) {
        return new AttachmentResponse(attachment.getId(), "/api/media/" + attachment.getId(),
                attachment.getOriginalName(), attachment.getMimeType(), attachment.getSizeBytes(),
                attachment.getWidth(), attachment.getHeight(), attachment.getAltText(),
                attachment.getCreateTime());
    }

    public record MediaContent(String mimeType, String originalName, byte[] bytes) {
    }
}
