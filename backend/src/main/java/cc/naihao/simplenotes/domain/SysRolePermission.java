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
 * 角色-权限节点授权（docs/auth-rbac.md §7.4 物理删除例外：仅 create_by / create_time，无软删）。
 *
 * <p>复合主键用 {@code @IdClass} + 扁平 {@code @Id} 字段：字段即实体属性，
 * 派生查询 / QueryDSL 路径均保持简明（{@code findByRoleIdIn}、{@code rp.menuId}）。
 * 保存 = 集合全量替换（事务内 delete + insert）。
 */
@Entity
@Table(name = "sys_role_permission")
@IdClass(SysRolePermissionId.class)
@Getter
@Setter
public class SysRolePermission implements Persistable<SysRolePermissionId> {

    @Id
    @Column(name = "role_id", length = 36, nullable = false, updatable = false)
    private String roleId;

    @Id
    @Column(name = "menu_id", length = 36, nullable = false, updatable = false)
    private String menuId;

    @Column(name = "create_by", length = 36, updatable = false)
    private String createBy;

    @Column(name = "create_time", nullable = false, updatable = false)
    private Instant createTime;

    @Transient
    private boolean isNew = true;

    public SysRolePermission() {
    }

    public SysRolePermission(String roleId, String menuId, String createBy) {
        this.roleId = roleId;
        this.menuId = menuId;
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
    public SysRolePermissionId getId() {
        return new SysRolePermissionId(roleId, menuId);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
