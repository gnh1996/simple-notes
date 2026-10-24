package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新建主题身份（版本内容随后通过版本接口提交）。 */
public record CreateThemeRequest(
        @NotBlank(message = "主题名称不能为空")
        @Size(max = 64, message = "主题名称最长 64 个字符")
        String name) {
}
