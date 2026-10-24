package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/**
 * 博客主题身份（docs/blog-runtime-theme.md §6.1）。
 *
 * <p>版本内容存放在不可变的 {@link SiteThemeVersion}；本表只维护名称与当前生效版本指针。
 * 站点当前使用哪个主题由 {@code site_setting} 的 {@code theme.id} 决定。
 */
@Entity
@Table(name = "site_theme")
@SQLRestriction("delete_flag = 0")
@Getter
@Setter
public class SiteTheme extends BaseEntity {

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** 当前生效版本；未应用过版本时为 null */
    @Column(name = "active_version_id", length = 36)
    private String activeVersionId;
}
