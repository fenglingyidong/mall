package com.mall.seckill.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SeckillOutboxDirectDrainMigrationSqlTest {

    @Test
    void migrationShouldContainRequiredOutboxDirectDrainSql() throws Exception {
        String sql = Files.readString(
                Path.of("..", "sql", "migration-v14-seckill-outbox-direct-drain.sql"),
                StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(sql).contains("outbox_claim_token VARCHAR(36)");
        assertThat(sql).contains("outbox_claimed_at DATETIME(3)");
        assertThat(sql).contains("idx_change_log_shard_status_id (bucket_shard_key, status, id)");
        assertThat(sql).contains("uk_mq_message_bucket_route_business");
        assertThat(sql).contains("seckill.order.create");
        assertThat(sql).contains("SIGNAL SQLSTATE ''45000''");
    }
}
