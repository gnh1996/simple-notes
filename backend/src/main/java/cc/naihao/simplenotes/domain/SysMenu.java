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
 * 权限目录节点（docs/auth-rbac.md §7.3）：每行 = 一个权限节点（ENTRY / MENU / ACTION）。
 *
 * <p>结构性字段（code / client / type / parentId）由迁移 INSERT 落定，界面不提供修改；
 * 权限码一经发布不可更改，废弃 = 停用（不软删、行不删除）。
 */
@Entity
@Table(name = "sys_menu")
@SQLRestriction("delete_flag = 0")
@Getter
@Setter
public class SysMenu extends BaseEntity {

    /** 父节点：ENTRY 端根为 null；FK 自引用 */
    @Column(name = "parent_id", length = 36)
    private String parentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "client", length = 16, nullable = false)
    private ClientType client;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 16, nullable = false)
    private MenuType type;

    /** 权限码：{端}:{模块}[:{资源}][:{动作}]，全局唯一且不复用 */
    @Column(name = "code", length = 128, nullable = false)
    private String code;

    /** 显示名（界面可改） */
    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** 同父下排序（界面可改） */
    @Column(name = "sort", nullable = false)
    private int sort = 0;

    /** 1 = 启用；0 = 停用（不可勾选 + 授权运行时视同未授予；界面可改） */
    @Column(name = "status", nullable = false)
    private short status = 1;
}
