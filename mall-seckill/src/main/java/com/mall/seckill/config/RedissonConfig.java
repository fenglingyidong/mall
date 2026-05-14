package com.mall.seckill.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "mall.seckill.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedissonClient redissonClient(RedisProperties properties) {
        Config config = new Config();
        String host = properties.getHost() == null ? "localhost" : properties.getHost();
        int port = properties.getPort();
        var server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(properties.getDatabase());
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            server.setPassword(properties.getPassword());
        }
        Duration timeout = properties.getTimeout();
        if (timeout != null) {
            server.setTimeout((int) timeout.toMillis());
        }
        return Redisson.create(config);
    }
}
