package com.mall.seckill;

import com.mall.seckill.pojo.entity.SeckillActivity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SeckillDomainTest {

    @Test
    void shouldDetectActiveWindow() {
        Instant now = Instant.now();
        SeckillActivity activity = new SeckillActivity(1L, "test", now.minusSeconds(1), now.plusSeconds(1));

        assertThat(activity.activeAt(now)).isTrue();
        assertThat(activity.activeAt(now.minusSeconds(10))).isFalse();
    }
}
