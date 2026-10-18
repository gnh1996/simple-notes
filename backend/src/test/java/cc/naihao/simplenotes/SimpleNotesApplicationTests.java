package cc.naihao.simplenotes;

import cc.naihao.simplenotes.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * 上下文加载冒烟测试：Testcontainers 起库 → Flyway V1 迁移 → JPA validate → 权限目录启动对账，
 * 全部通过才视为上下文可用（对账失败会直接让本测试失败）。
 */
class SimpleNotesApplicationTests extends IntegrationTestBase {

    @Test
    void contextLoads() {
    }
}
