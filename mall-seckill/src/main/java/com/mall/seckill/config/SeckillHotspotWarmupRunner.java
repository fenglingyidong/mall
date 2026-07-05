package com.mall.seckill.config;

import com.mall.seckill.service.impl.SeckillHotspotGuard;
import com.mall.seckill.service.impl.SeckillServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class SeckillHotspotWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeckillHotspotWarmupRunner.class);

    private final SeckillProperties properties;
    private final SeckillHotspotGuard hotspotGuard;
    private final SeckillServiceImpl seckillService;

    public SeckillHotspotWarmupRunner(SeckillProperties properties,
                                      SeckillHotspotGuard hotspotGuard,
                                      SeckillServiceImpl seckillService) {
        this.properties = properties;
        this.hotspotGuard = hotspotGuard;
        this.seckillService = seckillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        SeckillProperties.Hotspot hotspot = properties.getHotspot();
        if (!hotspot.isEnabled() || !hotspot.isWarmupEnabled()) {
            return;
        }
        for (SeckillHotspotGuard.HotspotItem item : hotspotGuard.hotspotItems()) {
            warmupOne(item);
        }
    }

    private void warmupOne(SeckillHotspotGuard.HotspotItem item) {
        try {
            seckillService.prewarm(item.activityId(), item.skuId());
        } catch (RuntimeException exception) {
            log.warn("Failed to prewarm seckill hotspot activityId={}, skuId={}",
                    item.activityId(), item.skuId(), exception);
        }
    }
}
