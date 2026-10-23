package cc.naihao.simplenotes.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * slug 占用检查（docs/content-model.md §3.7 / §4.6）。
 *
 * <p>{@code note} 实体带 {@code @SQLRestriction("delete_flag = 0")}，但 slug 唯一索引
 * **不带**软删除条件（公开 URL 永久保留）。因此这里必须用原生 SQL 检查包含软删除行的 slug，
 * 否则“删除文章后复用 slug”会绕过服务层校验，最终撞数据库唯一索引返回 500。
 */
@Service
@RequiredArgsConstructor
public class SlugService {

    private final JdbcTemplate jdbcTemplate;

    public boolean isTaken(String slug, String excludeNoteId) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        String sql = "select count(*) from note where kind = 'POST' and lower(slug) = lower(?)";
        Long count;
        if (excludeNoteId == null) {
            count = jdbcTemplate.queryForObject(sql, Long.class, slug);
        } else {
            count = jdbcTemplate.queryForObject(sql + " and id <> ?", Long.class, slug, excludeNoteId);
        }
        return count != null && count > 0;
    }
}
