package com.mall.seckill.config;

import com.mall.seckill.service.impl.SeckillHotspotGuard;
import com.mall.seckill.service.impl.SeckillServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillHotspotWarmupRunnerTest {

    @Test
    void shouldPrewarmConfiguredHotspotItems() {
        SeckillProperties properties = new SeckillProperties();
        properties.getHotspot().setEnabled(true);
        properties.getHotspot().setWarmupEnabled(true);
        SeckillHotspotGuard hotspotGuard = mock(SeckillHotspotGuard.class);
        SeckillServiceImpl seckillService = mock(SeckillServiceImpl.class);
        when(hotspotGuard.hotspotItems()).thenReturn(List.of(
                new SeckillHotspotGuard.HotspotItem(1L, 1001L),
                new SeckillHotspotGuard.HotspotItem(2L, 2001L)));
        SeckillHotspotWarmupRunner runner = new SeckillHotspotWarmupRunner(properties, hotspotGuard, seckillService);

        runner.run(null);

        verify(seckillService).prewarm(1L, 1001L);
        verify(seckillService).prewarm(2L, 2001L);
    }

    @Test
    void shouldSkipWarmupWhenDisabled() {
        SeckillProperties properties = new SeckillProperties();
        SeckillHotspotGuard hotspotGuard = mock(SeckillHotspotGuard.class);
        SeckillServiceImpl seckillService = mock(SeckillServiceImpl.class);
        SeckillHotspotWarmupRunner runner = new SeckillHotspotWarmupRunner(properties, hotspotGuard, seckillService);

        runner.run(null);

        verify(hotspotGuard, never()).hotspotItems();
        verify(seckillService, never()).prewarm(1L, 1001L);
    }
}
