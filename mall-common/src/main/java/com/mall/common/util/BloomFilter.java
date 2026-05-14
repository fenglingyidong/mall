package com.mall.common.util;

import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.Collection;

public class BloomFilter {

    private final BitSet bits;
    private final int size;

    public BloomFilter(int size) {
        this.size = size;
        this.bits = new BitSet(size);
    }

    public void add(String value) {
        for (int seed = 1; seed <= 3; seed++) {
            bits.set(hash(value, seed));
        }
    }

    public void addAll(Collection<String> values) {
        values.forEach(this::add);
    }

    public boolean mightContain(String value) {
        for (int seed = 1; seed <= 3; seed++) {
            if (!bits.get(hash(value, seed))) {
                return false;
            }
        }
        return true;
    }

    private int hash(String value, int seed) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int result = 0;
        for (byte b : bytes) {
            result = seed * result + b;
        }
        return Math.floorMod(result, size);
    }
}


