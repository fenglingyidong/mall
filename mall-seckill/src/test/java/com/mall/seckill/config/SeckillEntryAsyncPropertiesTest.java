package com.mall.seckill.config;

import com.mall.seckill.service.impl.SeckillStockChangeLogStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeckillEntryAsyncPropertiesTest {

    @Test
    void shouldExposeDefaultEntryAsyncProperties() {
        SeckillProperties properties = new SeckillProperties();

        assertThat(properties.getEntryGuard().isEnabled()).isFalse();
        assertThat(properties.getEntryGuard().getRequestTtlSeconds()).isEqualTo(300);
        assertThat(properties.getEntryGuard().getBuyerTtlBufferSeconds()).isEqualTo(600);
        assertThat(properties.getEntryGuard().getKeyPrefix()).isEqualTo("seckill:entry:");

        assertThat(properties.getOrderOutbox().isEnabled()).isFalse();
        assertThat(properties.getOrderOutbox().getFixedDelay()).isEqualTo(1000);
        assertThat(properties.getOrderOutbox().getBatchSize()).isEqualTo(500);

        assertThat(properties.getSnapshotRepair().isEnabled()).isFalse();
        assertThat(properties.getSnapshotRepair().getFixedDelay()).isEqualTo(1000);
        assertThat(properties.getSnapshotRepair().getRegisteredTimeoutSeconds()).isEqualTo(30);
        assertThat(properties.getSnapshotRepair().getBatchSize()).isEqualTo(200);
    }

    @Test
    void shouldExposeStockChangeLogStatusConstants() {
        assertThat(SeckillStockChangeLogStatus.NEW).isEqualTo("NEW");
        assertThat(SeckillStockChangeLogStatus.OUTBOXING).isEqualTo("OUTBOXING");
        assertThat(SeckillStockChangeLogStatus.OUTBOXED).isEqualTo("OUTBOXED");
        assertThat(SeckillStockChangeLogStatus.OUTBOX_FAILED).isEqualTo("OUTBOX_FAILED");
        assertThat(SeckillStockChangeLogStatus.LEDGER_PROCESSING).isEqualTo("LEDGER_PROCESSING");
        assertThat(SeckillStockChangeLogStatus.APPLIED).isEqualTo("APPLIED");
    }
}
