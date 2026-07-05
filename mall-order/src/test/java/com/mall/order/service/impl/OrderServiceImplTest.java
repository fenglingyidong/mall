package com.mall.order.service.impl;

import com.mall.order.pojo.dto.SeckillOrderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class OrderServiceImplTest {

    @Test
    void createSeckillOrderShouldUseSingleLocalTransaction() throws Exception {
        assertThat(AnnotatedElementUtils.hasAnnotation(
                OrderServiceImpl.class.getMethod("createSeckillOrder", SeckillOrderRequest.class),
                Transactional.class)).isTrue();
    }
}
