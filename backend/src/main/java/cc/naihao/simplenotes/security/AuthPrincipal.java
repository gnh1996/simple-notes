package cc.naihao.simplenotes.security;

import cc.naihao.simplenotes.domain.ClientType;
import java.util.Set;

/**
 * 请求级认证主体：会话 + 授权快照的轻量投影（不加载用户实体，避免每请求额外查询）。
 *
 * @param userId    用户 id
 * @param client    会话端（与请求路径域一致性已由过滤器校验）
 * @param sessionId 会话 id（JWT jti）
 * @param root      是否持有超管角色（授权短路依据）
 * @param codes     有效权限码集合（多角色并集、过滤停用角色与停用节点）
 */
public record AuthPrincipal(String userId, ClientType client, String sessionId, boolean root, Set<String> codes) {

    /** 权限判断：超管直通（含未来新增权限），否则查有效码集合 */
    public boolean can(String code) {
        return root || codes.contains(code);
    }
}
