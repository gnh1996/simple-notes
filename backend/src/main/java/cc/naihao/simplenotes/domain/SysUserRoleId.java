package cc.naihao.simplenotes.domain;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * {@link SysUserRole} 复合主键（{@code @IdClass}）：(user_id, role_id)。
 *
 * <p>实体侧以扁平 {@code @Id} 字段映射（查询方法名保持简明，如 {@code findByUserId}），
 * 本类承担标识对象的等值与仓储泛型。
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class SysUserRoleId implements Serializable {

    private String userId;

    private String roleId;
}
