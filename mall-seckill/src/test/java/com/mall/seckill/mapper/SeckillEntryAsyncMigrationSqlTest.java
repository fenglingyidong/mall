package com.mall.seckill.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SeckillEntryAsyncMigrationSqlTest {

    @Test
    void migrationMustContainEntryAsyncIndexes() throws Exception {
        String sql = Files.readString(
                Path.of("..", "sql", "migration-v13-seckill-entry-async.sql"),
                StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("uk_change_log_request_type")
                .contains("ALTER TABLE seckill_stock_change_log ADD UNIQUE KEY uk_change_log_request_type (request_id, change_type)")
                .contains("idx_mq_message_route_business")
                .contains("ALTER TABLE mq_message ADD KEY idx_mq_message_route_business (routing_key, business_key)")
                .contains("idx_snapshot_status_id")
                .contains("ALTER TABLE seckill_stock_snapshot ADD KEY idx_snapshot_status_id (status, request_id)")
                .contains("idx_snapshot_created_at")
                .contains("ALTER TABLE seckill_stock_snapshot ADD KEY idx_snapshot_created_at (created_at)");
    }
}
