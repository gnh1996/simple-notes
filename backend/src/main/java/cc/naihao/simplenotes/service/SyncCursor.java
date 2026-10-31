package cc.naihao.simplenotes.service;

import cc.naihao.simplenotes.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 同步索引的 keyset 游标（docs/desktop-sync.md §3.2）：{@code (updateTime, id)}。
 *
 * <p>对客户端不透明，编码为 Base64URL（无 padding）的 {@code <instant>|<id>}。
 * 使用时间撮合 id 作为复合游标，避免同一毫秒多条记录在分页时被跳过。
 */
public record SyncCursor(Instant time, String id) {

    public String encode() {
        String raw = time + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 空 / null 返回 null（首次拉取）；非法游标按参数错误处理。 */
    public static SyncCursor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(raw),
                    StandardCharsets.UTF_8);
            int split = decoded.lastIndexOf('|');
            if (split <= 0 || split == decoded.length() - 1) {
                throw ApiException.badRequest("非法游标");
            }
            return new SyncCursor(
                    Instant.parse(decoded.substring(0, split)),
                    decoded.substring(split + 1));
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw ApiException.badRequest("非法游标");
        }
    }
}
