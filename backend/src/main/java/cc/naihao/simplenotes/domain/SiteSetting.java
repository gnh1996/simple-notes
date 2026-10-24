package cc.naihao.simplenotes.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 站点配置 KV（docs/blog-runtime-theme.md §6.2）。
 *
 * <p>类型、分组、默认值、校验与是否公开都由代码侧注册表维护，本表只存键值。
 * 无软删除：写入即 upsert，移除即物理删除（审计例外，见 backend/AGENTS.md）。
 */
@Entity
@Table(name = "site_setting")
@Getter
@Setter
public class SiteSetting {

    @Id
    @Column(name = "setting_key", length = 100, nullable = false, updatable = false)
    private String settingKey;

    /** 标量存原文；复杂值存 JSON 字符串 */
    @Column(name = "setting_value", columnDefinition = "text")
    private String settingValue;

    @Column(name = "create_time", nullable = false, updatable = false)
    private Instant createTime;

    @Column(name = "update_time")
    private Instant updateTime;

    @Column(name = "create_by", length = 36, updatable = false)
    private String createBy;

    @Column(name = "update_by", length = 36)
    private String updateBy;
}
