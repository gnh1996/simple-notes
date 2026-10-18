package cc.naihao.simplenotes.web.dto;

import java.util.List;

/**
 * 分页响应（自建结构，不直接序列化 Spring Data Page，避免其 JSON 结构不稳定）。
 */
public record PageResponse<T>(List<T> items, long total, int page, int size) {
}
