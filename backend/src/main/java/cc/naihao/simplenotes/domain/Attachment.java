package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/**
 * 附件元数据（docs/content-model.md §3.5 / docs/attachment-storage.md）：
 * storageKey 与实际存储解耦，storageMode 记录该行二进制实际所在介质。
 */
@Entity
@Table(name = "attachment")
@SQLRestriction("delete_flag = 0")
@Getter
@Setter
public class Attachment extends BaseEntity {

    @Column(name = "owner_id", length = 36, nullable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_mode", length = 16, nullable = false)
    private StorageMode storageMode = StorageMode.LOCAL;

    @Column(name = "storage_key", length = 255, nullable = false)
    private String storageKey;

    @Column(name = "original_name", length = 255, nullable = false)
    private String originalName;

    @Column(name = "mime_type", length = 128, nullable = false)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "alt_text", length = 255)
    private String altText;
}
