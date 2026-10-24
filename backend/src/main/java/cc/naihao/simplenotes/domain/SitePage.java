package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/**
 * 独立页面（docs/blog-runtime-theme.md §6.3）：关于等站点家具。
 *
 * <p>保存即线上，不做发布快照；{@code about} 为预置 slug。
 */
@Entity
@Table(name = "site_page")
@SQLRestriction("delete_flag = 0")
@Getter
@Setter
public class SitePage extends BaseEntity {

    @Column(name = "slug", length = 64, nullable = false)
    private String slug;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /** 1 = 在导航 / 页面列表中可见；0 = 仅直链 */
    @Column(name = "visible", nullable = false)
    private short visible = 1;

    @Column(name = "sort", nullable = false)
    private int sort = 0;
}
