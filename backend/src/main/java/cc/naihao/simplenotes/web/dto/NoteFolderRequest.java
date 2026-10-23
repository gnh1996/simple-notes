package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新建 / 编辑记事本文件夹。 */
public record NoteFolderRequest(
        @NotBlank(message = "文件夹名称不能为空")
        @Size(max = 64, message = "文件夹名称最长 64 个字符")
        String name,

        Integer sort) {
}
