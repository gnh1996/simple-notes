package cc.naihao.simplenotes.domain;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * {@link SysRolePermission} 复合主键（{@code @IdClass}）：(role_id, menu_id)。
 *
 * <p>实体侧以扁平 {@code @Id} 字段映射（查询方法名保持简明，如 {@code findByRoleIdIn}），
 * 本类承担标识对象的等值与仓储泛型。
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class SysRolePermissionId implements Serializable {

    private String roleId;

    private String menuId;
}
