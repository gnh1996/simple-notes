package cc.naihao.simplenotes.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 切换新上传介质（docs/attachment-storage.md §7.5）。 */
public record UpdateStorageModeRequest(@NotBlank String mode) {
}
