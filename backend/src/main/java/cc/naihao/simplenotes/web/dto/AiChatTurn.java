package cc.naihao.simplenotes.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** AI 对话轮次（会话态在前端，后端只收本轮上下文，无服务端记忆）。 */
@Schema(requiredProperties = { "role", "content" })
public record AiChatTurn(
        /** user / assistant（assistant 轮由前端回放上一轮 done 的说明文本） */
        @NotBlank(message = "缺少 role")
        @Size(max = 16, message = "role 过长")
        String role,

        @NotBlank(message = "缺少 content")
        @Size(max = 8000, message = "单轮 content 最长 8000 字符")
        String content) {
}
