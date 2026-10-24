package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/**
 * 不可变主题版本（docs/blog-runtime-theme.md §6.1）：保存 / 应用都只新增行。
 *
 * <p>与 {@link NoteRevision} 同构：只 INSERT，不 UPDATE / DELETE，不参与软删除。
 * 回滚 = 移动 {@link SiteTheme#getActiveVersionId()} 指针，不改历史行。
 */
@Entity
@Table(name = "site_theme_version")
@Getter
@Setter
public class SiteThemeVersion implements Persistable<String> {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "theme_id", length = 36, nullable = false, updatable = false)
    private String themeId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    /** 主题包形态：1 = html + css（后续形态新增编号，存量行不动） */
    @Column(name = "format", nullable = false, updatable = false)
    private short format = 1;

    @Column(name = "html", nullable = false, updatable = false, columnDefinition = "text")
    private String html;

    @Column(name = "css", nullable = false, updatable = false, columnDefinition = "text")
    private String css;

    /** 主题声明依赖的运行时契约版本 */
    @Column(name = "contract", nullable = false, updatable = false)
    private int contract;

    @Column(name = "create_by", length = 36, updatable = false)
    private String createBy;

    @Column(name = "create_time", nullable = false, updatable = false)
    private Instant createTime;

    @Transient
    private boolean isNew = true;

    @PrePersist
    void onCreate() {
        if (createTime == null) {
            createTime = Instant.now();
        }
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
