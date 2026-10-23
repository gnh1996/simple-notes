package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/**
 * 标签（docs/content-model.md §3.3）：笔记与文章共用；名称大小写不敏感唯一。
 */
@Entity
@Table(name = "tag")
@SQLRestriction("delete_flag = 0")
@Getter
@Setter
public class Tag extends BaseEntity {

    @Column(name = "name", length = 32, nullable = false)
    private String name;
}
