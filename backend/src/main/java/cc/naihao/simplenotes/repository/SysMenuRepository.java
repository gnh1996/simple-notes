package cc.naihao.simplenotes.repository;

import cc.naihao.simplenotes.domain.ClientType;
import cc.naihao.simplenotes.domain.SysMenu;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 权限目录仓储。目录行不软删（废弃 = 停用），小表全量加载后在内存组树。
 */
public interface SysMenuRepository extends JpaRepository<SysMenu, String> {

    Optional<SysMenu> findByCode(String code);

    /** 完整目录（管理端树 / 启动对账）：按 sort、创建时间排序 */
    List<SysMenu> findAllByOrderBySortAscCreateTimeAsc();

    /** 指定端的启用节点（可见菜单树 / 授权有效性计算用） */
    List<SysMenu> findByClientAndStatusOrderBySortAscCreateTimeAsc(ClientType client, short status);
}
