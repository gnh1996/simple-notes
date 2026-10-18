package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/**
 * 用户-角色关联（docs/auth-rbac.md §7.4 物理删除例外：仅 create_by / create_time，无软删）。
 *
 * <p>复合主键用 {@code @IdClass} + 扁平 {@code @Id} 字段：字段即实体属性，
 * 派生查询 / QueryDSL 路径均保持简明（{@code findByUserId}、{@code ur.roleId}）。
 * 分配关系全量替换式更新：删除 + 重建。
 */
@Entity
@Table(name = "sys_user_role")
@IdClass(SysUserRoleId.class)
@Getter
@Setter
public class SysUserRole implements Persistable<SysUserRoleId> {

    @Id
    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private String userId;

    @Id
    @Column(name = "role_id", length = 36, nullable = false, updatable = false)
    private String roleId;

    @Column(name = "create_by", length = 36, updatable = false)
    private String createBy;

    @Column(name = "create_time", nullable = false, updatable = false)
    private Instant createTime;

    @Transient
    private boolean isNew = true;

    public SysUserRole() {
    }

    public SysUserRole(String userId, String roleId, String createBy) {
        this.userId = userId;
        this.roleId = roleId;
        this.createBy = createBy;
    }

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
    public SysUserRoleId getId() {
        return new SysUserRoleId(userId, roleId);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
