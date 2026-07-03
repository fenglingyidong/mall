package com.mall.seckill.cache;

import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.pojo.vo.StockVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeckillStockCacheTest {

    private FakeTairStringCommands tairStringCommands;
    private SeckillProperties properties;
    private SeckillStockCache stockCache;

    @BeforeEach
    void setUp() {
        tairStringCommands = new FakeTairStringCommands();
        properties = new SeckillProperties();
        properties.getStockCache().setEnabled(true);
        stockCache = new SeckillStockCache(tairStringCommands, properties);
    }

    @Test
    void shouldSkipLowerVersionRefresh() {
        tairStringCommands.set("seckill:stock-cache:1:1001", "8", 10L);

        stockCache.refresh(1L, 1001L, new StockVersion(9, 9L));

        assertThat(tairStringCommands.get("seckill:stock-cache:1:1001"))
                .isEqualTo(new VersionedCacheValue("8", 10L));
    }

    @Test
    void shouldWriteHigherVersionRefresh() {
        tairStringCommands.set("seckill:stock-cache:1:1001", "8", 10L);

        stockCache.refresh(1L, 1001L, new StockVersion(7, 11L));

        assertThat(tairStringCommands.get("seckill:stock-cache:1:1001"))
                .isEqualTo(new VersionedCacheValue("7", 11L));
    }

    @Test
    void shouldWriteWhenCurrentCacheMissing() {
        stockCache.refresh(1L, 1001L, new StockVersion(7, 1L));

        assertThat(tairStringCommands.get("seckill:stock-cache:1:1001"))
                .isEqualTo(new VersionedCacheValue("7", 1L));
    }

    @Test
    void shouldIgnoreRefreshFailureBecauseDatabaseIsStockSource() {
        tairStringCommands.failOnSet = true;

        stockCache.refresh(1L, 1001L, new StockVersion(7, 1L));

        assertThat(tairStringCommands.get("seckill:stock-cache:1:1001")).isNull();
    }

    @Test
    void shouldReturnSoldOutOnlyWhenFailFastEnabledAndStockIsZero() {
        tairStringCommands.set("seckill:stock-cache:1:1001", "0", 3L);

        assertThat(stockCache.isSoldOut(1L, 1001L)).isTrue();

        properties.getStockCache().setFailFast(false);

        assertThat(stockCache.isSoldOut(1L, 1001L)).isFalse();
    }

    @Test
    void shouldNotFailFastWhenCacheReadFails() {
        tairStringCommands.failOnGet = true;

        assertThat(stockCache.isSoldOut(1L, 1001L)).isFalse();
    }

    @Test
    void shouldNoopWhenCacheDisabled() {
        properties.getStockCache().setEnabled(false);

        stockCache.refresh(1L, 1001L, new StockVersion(7, 1L));

        assertThat(tairStringCommands.get("seckill:stock-cache:1:1001")).isNull();
        assertThat(stockCache.isSoldOut(1L, 1001L)).isFalse();
    }

    private static class FakeTairStringCommands implements TairStringCommands {

        private final Map<String, VersionedCacheValue> values = new HashMap<>();
        private boolean failOnGet;
        private boolean failOnSet;

        @Override
        public VersionedCacheValue get(String key) {
            if (failOnGet) {
                throw new IllegalStateException("cache read failed");
            }
            return values.get(key);
        }

        @Override
        public void set(String key, String value, long version) {
            if (failOnSet) {
                throw new IllegalStateException("cache write failed");
            }
            VersionedCacheValue current = values.get(key);
            if (current != null && current.version() != null && current.version() >= version) {
                return;
            }
            values.put(key, new VersionedCacheValue(value, version));
        }
    }
}
