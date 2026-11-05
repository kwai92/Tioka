package com.tioka.server.runtime;

import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V104 在空库上为 no-op（无 parts_json 行）；此处断言迁移后旧列已删除、新表可用。
 */
@SpringBootTest
@Tag("it")
class BackfillRuntimeItemsIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void legacyColumnsDroppedAndItemTableReady() {
        Integer parts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='runtime_session' AND column_name='parts_json'",
                Integer.class);
        assertTrue(parts != null && parts == 0);
        Integer items = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name='runtime_item'",
                Integer.class);
        assertTrue(items != null && items == 1);
    }
}
