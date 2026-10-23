package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建 / 重命名标签。 */
public record TagNameRequest(
        @NotBlank(message = "标签名称不能为空")
        @Size(max = 32, message = "标签最长 32 个字符")
        String name) {
}
