package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/**
 * 角色（docs/auth-rbac.md §7.2）：权限码的命名集合；用户权限 = 多角色并集。
 */
@Entity
@Table(name = "sys_role")
@SQLRestriction("delete_flag = 0")
@Getter
@Setter
public class SysRole extends BaseEntity {

    /** 角色码：[A-Z][A-Z0-9_]*，唯一（部分索引），创建后稳定 */
    @Column(name = "code", length = 64, nullable = false)
    private String code;

    @Column(name = "name", length = 32, nullable = false)
    private String name;

    @Column(name = "description", length = 200)
    private String description;

    /** 1 = 内置角色（不可编辑 / 删除 / 分配）；0 = 普通角色 */
    @Column(name = "builtin", nullable = false)
    private short builtin = 0;

    @Column(name = "sort", nullable = false)
    private int sort = 0;

    /** 1 = 启用；0 = 停用（停用 = 授权不生效） */
    @Column(name = "status", nullable = false)
    private short status = 1;
}
