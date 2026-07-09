package com.mall.common.util;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class OrderNoGeneratorTest {

    @Test
    void shouldKeepPrefixAndGenerateFixedLengthId() {
        String orderNo = OrderNoGenerator.next("S");

        assertThat(orderNo).startsWith("S");
        assertThat(orderNo).hasSize(33);
    }

    @Test
    void shouldGenerateUniqueOrderNosUnderConcurrency() {
        Set<String> orderNos = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 50000)
                .parallel()
                .mapToObj(ignored -> OrderNoGenerator.next("S"))
                .forEach(orderNos::add);

        assertThat(orderNos).hasSize(50000);
    }
}
