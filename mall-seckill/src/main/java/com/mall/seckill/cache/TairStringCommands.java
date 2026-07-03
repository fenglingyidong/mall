package com.mall.seckill.cache;

public interface TairStringCommands {

    VersionedCacheValue get(String key);

    void set(String key, String value, long version);
}
