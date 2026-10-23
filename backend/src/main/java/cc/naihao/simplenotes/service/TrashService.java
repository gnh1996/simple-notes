package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.security.AuthPrincipal;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.TrashItemResponse;
import java.sql.Timestamp;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回收站（docs/content-model.md §4.5）。
 *
 * <p>{@code Note} 实体带 {@code @SQLRestriction("delete_flag = 0")}，普通仓储读不到回收站行；
 * 本服务用原生 SQL 作为明确例外（backend/AGENTS.md「软删除数据的例外读取」）。
 */
@Service
@RequiredArgsConstructor
public class TrashService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public List<TrashItemResponse> list(NoteKind kind) {
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        String baseSql = "select id, kind, title, publish_status, delete_time from note "
                + "where delete_flag = 1 and kind = ?";
        if (principal.root()) {
            return queryItems(baseSql + " order by delete_time desc", kind.name());
        }
        return queryItems(baseSql + " and owner_id = ? order by delete_time desc",
                kind.name(), principal.userId());
    }

    @Transactional
    public void restoreNote(String id) {
        restore(id, NoteKind.NOTE);
    }

    @Transactional
    public void restorePost(String id) {
        restore(id, NoteKind.POST);
    }

    private void restore(String id, NoteKind kind) {
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        String ownerId = ownerOfDeleted(id, kind);
        if (!principal.root() && !ownerId.equals(principal.userId())) {
            throw ApiException.notFound("回收站内容不存在");
        }

        int updated;
        if (kind == NoteKind.POST) {
            // 已发布过的文章恢复为 UNPUBLISHED；从未发布过的草稿恢复为 DRAFT。
            updated = jdbcTemplate.update("""
                    update note
                       set delete_flag = 0,
                           delete_time = null,
                           delete_by = null,
                           publish_status = case
                               when published_revision_id is null then 'DRAFT'
                               else 'UNPUBLISHED'
                           end,
                           update_by = ?,
                           update_time = now(),
                           version = version + 1
                     where id = ? and kind = 'POST' and delete_flag = 1
                    """, principal.userId(), id);
        } else {
            updated = jdbcTemplate.update("""
                    update note
                       set delete_flag = 0,
                           delete_time = null,
                           delete_by = null,
                           update_by = ?,
                           update_time = now(),
                           version = version + 1
                     where id = ? and kind = 'NOTE' and delete_flag = 1
                    """, principal.userId(), id);
        }
        if (updated == 0) {
            throw ApiException.notFound("回收站内容不存在");
        }
    }

    private String ownerOfDeleted(String id, NoteKind kind) {
        try {
            return jdbcTemplate.queryForObject(
                    "select owner_id from note where id = ? and kind = ? and delete_flag = 1",
                    String.class, id, kind.name());
        } catch (EmptyResultDataAccessException ex) {
            throw ApiException.notFound("回收站内容不存在");
        }
    }

    private List<TrashItemResponse> queryItems(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Timestamp deletedAt = rs.getTimestamp("delete_time");
            return new TrashItemResponse(
                    rs.getString("id"),
                    rs.getString("kind"),
                    rs.getString("title"),
                    rs.getString("publish_status"),
                    deletedAt == null ? null : deletedAt.toInstant());
        }, args);
    }
}
