package com.mall.seckill.mapper;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class RedisSeckillExecutor {

    private static final String SCRIPT = """
            local stock = tonumber(redis.call('get', KEYS[1]) or '-1')
            if stock <= 0 then
                return 1
            end
            if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then
                return 2
            end
            redis.call('decr', KEYS[1])
            redis.call('sadd', KEYS[2], ARGV[1])
            return 0
            """;

    private final StringRedisTemplate redisTemplate;
    private final SeckillRepository repository;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(SCRIPT, Long.class);

    public RedisSeckillExecutor(ObjectProvider<StringRedisTemplate> redisTemplate, SeckillRepository repository) {
        this.redisTemplate = redisTemplate.getIfAvailable();
        this.repository = repository;
    }

    @PostConstruct
    public void warmup() {
        if (redisTemplate == null) {
            return;
        }
        try {
            for (Map.Entry<String, Integer> entry : repository.stockSnapshot().entrySet()) {
                redisTemplate.opsForValue().set(stockKey(entry.getKey()), String.valueOf(entry.getValue()));
                redisTemplate.delete(userKey(entry.getKey()));
            }
        } catch (Exception ignored) {
            // Redis is optional for local demos.
        }
    }

    public Integer tryDeduct(Long activityId, Long skuId, Long userId) {
        if (redisTemplate == null) {
            return null;
        }
        String key = activityId + ":" + skuId;
        try {
            Long result = redisTemplate.execute(script, List.of(stockKey(key), userKey(key)), String.valueOf(userId));
            return result == null ? null : result.intValue();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stockKey(String key) {
        return "seckill:stock:" + key;
    }

    private String userKey(String key) {
        return "seckill:user:" + key;
    }
}
