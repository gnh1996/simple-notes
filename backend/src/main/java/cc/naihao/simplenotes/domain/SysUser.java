package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/**
 * 用户（docs/auth-rbac.md §7.1）。
 *
 * <p>超管保护由"是否持有 SUPER_ADMIN 角色"推导，不设用户级 builtin 列。
 */
@Entity
@Table(name = "sys_user")
@SQLRestriction("delete_flag = 0")
@Getter
@Setter
public class SysUser extends BaseEntity {

    /** 登录名：小写存储，唯一（部分索引：delete_flag = 0） */
    @Column(name = "username", length = 32, nullable = false)
    private String username;

    /** 密码哈希：{bcrypt}$2a$...（DelegatingPasswordEncoder 格式） */
    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    /** 显示名 */
    @Column(name = "nickname", length = 32, nullable = false)
    private String nickname;

    @Column(name = "email", length = 128)
    private String email;

    /** 1 = 启用；0 = 禁用 */
    @Column(name = "status", nullable = false)
    private short status = 1;

    @Column(name = "last_login_time")
    private Instant lastLoginTime;
}
