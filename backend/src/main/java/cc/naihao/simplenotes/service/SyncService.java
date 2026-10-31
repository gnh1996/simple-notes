package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.domain.NoteKind;
import cc.naihao.simplenotes.security.AuthPrincipal;
import cc.naihao.simplenotes.security.SecurityUtils;
import cc.naihao.simplenotes.web.dto.SyncItem;
import cc.naihao.simplenotes.web.dto.SyncPage;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 桌面端同步索引（docs/desktop-sync.md §3.2）。
 *
 * <p>{@code Note} 实体带 {@code @SQLRestriction("delete_flag = 0")}，同步索引必须同时下发墓碑
 * （{@code deleted = true}），因此这里用原生 SQL 读取软删除行，属 backend/AGENTS.md
 * 「软删除数据的例外读取」的明确用例。
 */
@Service
@RequiredArgsConstructor
public class SyncService {

    /** 单页上限：索引体量小，500 条足以让 1000+ 篇内容在 1–3 个请求内完成首次同步。 */
    private static final int MAX_LIMIT = 500;

    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public SyncPage page(NoteKind kind, String cursor, int limit) {
        int safeLimit = Math.clamp(limit, 1, MAX_LIMIT);
        AuthPrincipal principal = SecurityUtils.requirePrincipal();

        StringBuilder sql = new StringBuilder("""
                select id, kind, title, folder_id, pinned, publish_status, version,
                       coalesce(update_time, create_time) as sync_time, delete_flag
                  from note
                 where kind = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(kind.name());

        // 随手记按所有者隔离（与 NoteService.list 一致）；文章沿用后台列表的单作者语义。
        if (kind == NoteKind.NOTE && !principal.root()) {
            sql.append(" and owner_id = ?");
            args.add(principal.userId());
        }

        SyncCursor decoded = SyncCursor.decode(cursor);
        if (decoded != null) {
            sql.append(" and (coalesce(update_time, create_time), id) > (?, ?)");
            args.add(Timestamp.from(decoded.time()));
            args.add(decoded.id());
        }

        sql.append(" order by coalesce(update_time, create_time), id limit ?");
        args.add(safeLimit + 1);

        List<SyncItem> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Timestamp syncTime = rs.getTimestamp("sync_time");
            return new SyncItem(
                    rs.getString("id"),
                    rs.getString("kind"),
                    rs.getString("title"),
                    rs.getString("folder_id"),
                    rs.getInt("pinned") == 1,
                    rs.getString("publish_status"),
                    rs.getLong("version"),
                    syncTime == null ? null : syncTime.toInstant(),
                    rs.getInt("delete_flag") == 1);
        }, args.toArray());

        boolean hasMore = rows.size() > safeLimit;
        List<SyncItem> items = hasMore ? rows.subList(0, safeLimit) : rows;
        String nextCursor = items.isEmpty()
                ? null
                : new SyncCursor(items.get(items.size() - 1).updateTime(),
                        items.get(items.size() - 1).id()).encode();
        return new SyncPage(List.copyOf(items), nextCursor, hasMore);
    }
}
