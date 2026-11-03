package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新建 / 编辑记事本文件夹；v2.0 只在新建时接受 parentId，编辑忽略父级变更。 */
public record NoteFolderRequest(
        @NotBlank(message = "文件夹名称不能为空")
        @Size(max = 64, message = "文件夹名称最长 64 个字符")
        String name,

        Integer sort,

        String parentId) {
}
