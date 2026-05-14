package com.mall.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BloomFilterTest {

    @Test
    void shouldRememberInsertedValuesAndRejectObviousMisses() {
        BloomFilter bloomFilter = new BloomFilter(1 << 16);
        bloomFilter.add("1001");
        bloomFilter.add("1002");

        assertThat(bloomFilter.mightContain("1001")).isTrue();
        assertThat(bloomFilter.mightContain("999999")).isFalse();
    }
}


