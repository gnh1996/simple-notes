package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotNull;

/** 发布 / 更新发布：只携带客户端版本号，发布内容来自已保存草稿。 */
public record PublishRequest(
        @NotNull(message = "缺少版本号")
        Long version) {
}
