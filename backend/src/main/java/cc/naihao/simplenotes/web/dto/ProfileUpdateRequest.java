package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改自己的昵称（个人中心，登录即可，不设权限码）。
 */
public record ProfileUpdateRequest(
        @NotBlank(message = "昵称不能为空")
        @Size(max = 32, message = "昵称最长 32 个字符")
        String nickname) {
}
