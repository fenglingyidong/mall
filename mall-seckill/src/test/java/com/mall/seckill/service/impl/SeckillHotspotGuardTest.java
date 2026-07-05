package com.mall.seckill.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.seckill.config.SeckillProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeckillHotspotGuardTest {

    @Test
    void shouldMatchConfiguredHotspotItems() {
        SeckillProperties properties = new SeckillProperties();
        properties.getHotspot().setEnabled(true);
        properties.getHotspot().setItems(List.of("1:1001", "bad", "2:x", "2:2001"));
        SeckillHotspotGuard guard = new SeckillHotspotGuard(properties);

        assertThat(guard.isHotspot(1L, 1001L)).isTrue();
        assertThat(guard.isHotspot(2L, 2001L)).isTrue();
        assertThat(guard.isHotspot(1L, 1002L)).isFalse();
        assertThat(guard.hotspotItems()).containsExactly(
                new SeckillHotspotGuard.HotspotItem(1L, 1001L),
                new SeckillHotspotGuard.HotspotItem(2L, 2001L));
    }

    @Test
    void shouldRejectWhenHotspotConcurrencyIsFull() {
        SeckillProperties properties = new SeckillProperties();
        properties.getHotspot().setEnabled(true);
        properties.getHotspot().setItems(List.of("1:1001"));
        properties.getHotspot().setMaxConcurrent(1);
        SeckillHotspotGuard guard = new SeckillHotspotGuard(properties);

        try (SeckillHotspotGuard.HotspotPermit ignored = guard.acquire(1L, 1001L)) {
            assertThatThrownBy(() -> guard.acquire(1L, 1001L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Hotspot seckill busy");
        }
    }
}
