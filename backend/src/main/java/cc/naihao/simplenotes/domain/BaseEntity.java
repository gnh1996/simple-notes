package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/**
 * 业务实体基类：UUID 主键（应用层生成、字符串存储）+ 审计七件套 + 软删除约定。
 *
 * <p>与 backend/AGENTS.md「数据模型约定」一致：
 *
 * <ul>
 *   <li>id 在实例化时即生成（不依赖数据库序列），因此必须实现 {@link Persistable} 显式表达
 *       "新实体"，否则 Spring Data 会把非空 id 判为已存在而走 merge；</li>
 *   <li>审计字段由实体生命周期回调维护：{@code create_time} / {@code update_time} 自动，
 *       {@code create_by} / {@code update_by} / 软删除三件套由服务层按当前操作者显式写入；</li>
 *   <li>软删除 = {@link #markDeleted(String)}；查询侧默认过滤由各实体上的
 *       {@code @SQLRestriction("delete_flag = 0")} 保证。</li>
 * </ul>
 */
@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity implements Persistable<String> {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "create_time", nullable = false, updatable = false)
    private Instant createTime;

    @Column(name = "update_time")
    private Instant updateTime;

    @Column(name = "delete_time")
    private Instant deleteTime;

    @Column(name = "create_by", length = 36, updatable = false)
    private String createBy;

    @Column(name = "update_by", length = 36)
    private String updateBy;

    @Column(name = "delete_by", length = 36)
    private String deleteBy;

    @Column(name = "delete_flag", nullable = false)
    private short deleteFlag = 0;

    /** 新实体标记：id 预生成，必须显式维护（见类注释） */
    @Transient
    private boolean isNew = true;

    @PrePersist
    void onCreate() {
        if (createTime == null) {
            createTime = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updateTime = Instant.now();
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

    /** 软删除：置 delete_flag = 1 并记录删除时间 / 删除人 */
    public void markDeleted(String operatorId) {
        this.deleteFlag = 1;
        this.deleteTime = Instant.now();
        this.deleteBy = operatorId;
    }
}
