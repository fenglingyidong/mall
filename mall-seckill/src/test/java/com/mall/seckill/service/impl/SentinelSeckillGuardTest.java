package com.mall.seckill.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SentinelSeckillGuardTest {

    @Test
    void shouldRouteDefaultAndHotspotResourcesSeparately() {
        SentinelSeckillGuard guard = new SentinelSeckillGuard();

        assertThat(guard.resourceName(false)).isEqualTo(SentinelSeckillGuard.SUBMIT_RESOURCE);
        assertThat(guard.resourceName(true)).isEqualTo(SentinelSeckillGuard.HOTSPOT_SUBMIT_RESOURCE);
    }
}
