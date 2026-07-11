package com.mall.seckill.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.seckill.config.SeckillProperties;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScript;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
public class SeckillEntryGuard {

    private static final String DEFAULT_KEY_PREFIX = "seckill:entry:";
    private static final String UNAVAILABLE_MESSAGE = "Seckill entry guard unavailable";
    private static final String RELEASE_BUYER_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final SeckillProperties properties;

    public SeckillEntryGuard(ObjectProvider<RedissonClient> redissonClientProvider,
                             SeckillProperties properties) {
        this.redissonClientProvider = redissonClientProvider;
        this.properties = properties == null ? new SeckillProperties() : properties;
    }

    /**
     * 用 request 级 Redis 键拦截同一幂等号的重试。
     *
     * <p>成功时只写入短 TTL 标记；失败时说明该 requestId 已进入过入口链路，
     * 调用方应改为读取结果投影，避免再次占用 buyer key 和分桶库存。</p>
     */
    public RequestDecision acquireRequest(String requestId) {
        if (!enabled()) {
            return new RequestDecision(RequestOutcome.ACQUIRED);
        }
        RBucket<String> bucket = redissonClient().getBucket(requestKey(requestId));
        boolean acquired = bucket.trySet("1", requestTtlSeconds(), TimeUnit.SECONDS);
        return new RequestDecision(acquired ? RequestOutcome.ACQUIRED : RequestOutcome.DUPLICATE);
    }

    /**
     * 用 buyer 级 Redis 键拦截同一买家对同一活动 SKU 的并发提交。
     *
     * <p>键值保存 requestId：如果已有值等于当前 requestId，按同一次请求重试处理；
     * 如果已有值不同，则说明同一买家已有另一笔请求在处理或已成功占位。</p>
     */
    public BuyerDecision acquireBuyer(Long activityId,
                                      Long skuId,
                                      Long userId,
                                      String requestId,
                                      Instant activityEndAt) {
        if (!enabled()) {
            return new BuyerDecision(BuyerOutcome.ACQUIRED, null);
        }
        RBucket<String> bucket = redissonClient().getBucket(buyerKey(activityId, skuId, userId));
        boolean acquired = bucket.trySet(requestId, buyerTtlSeconds(activityEndAt), TimeUnit.SECONDS);
        if (acquired) {
            return new BuyerDecision(BuyerOutcome.ACQUIRED, null);
        }

        String existingRequestId = bucket.get();
        if (Objects.equals(existingRequestId, requestId)) {
            return new BuyerDecision(BuyerOutcome.SAME_REQUEST, existingRequestId);
        }
        return new BuyerDecision(BuyerOutcome.DUPLICATE_BUYER, existingRequestId);
    }

    /**
     * 只释放当前 requestId 持有的 buyer key。
     *
     * <p>Lua 中先比对 value 再删除，避免失败分支误删同一买家后续请求刚写入的新占位。</p>
     */
    public void releaseBuyer(Long activityId, Long skuId, Long userId, String requestId) {
        if (!enabled()) {
            return;
        }
        String key = buyerKey(activityId, skuId, userId);
        redissonClient().getScript().eval(RScript.Mode.READ_WRITE,
                RELEASE_BUYER_SCRIPT,
                RScript.ReturnType.INTEGER,
                List.of((Object) key),
                requestId);
    }

    public boolean enabled() {
        return entryGuardProperties().isEnabled();
    }

    private RedissonClient redissonClient() {
        RedissonClient redissonClient = redissonClientProvider == null ? null : redissonClientProvider.getIfAvailable();
        if (redissonClient == null) {
            throw new BusinessException(UNAVAILABLE_MESSAGE);
        }
        return redissonClient;
    }

    private String requestKey(String requestId) {
        return keyPrefix() + "req:" + requestId;
    }

    private String buyerKey(Long activityId, Long skuId, Long userId) {
        return keyPrefix() + "buyer:" + activityId + ":" + skuId + ":" + userId;
    }

    private String keyPrefix() {
        String keyPrefix = entryGuardProperties().getKeyPrefix();
        return StringUtils.hasText(keyPrefix) ? keyPrefix : DEFAULT_KEY_PREFIX;
    }

    private long requestTtlSeconds() {
        return Math.max(1, entryGuardProperties().getRequestTtlSeconds());
    }

    private long buyerTtlSeconds(Instant activityEndAt) {
        long bufferSeconds = entryGuardProperties().getBuyerTtlBufferSeconds();
        long ttlSeconds = bufferSeconds;
        if (activityEndAt != null) {
            long secondsUntilEnd = Duration.between(Instant.now(), activityEndAt).getSeconds();
            ttlSeconds = secondsUntilEnd + bufferSeconds;
        }
        return Math.max(1, ttlSeconds);
    }

    private SeckillProperties.EntryGuard entryGuardProperties() {
        SeckillProperties.EntryGuard entryGuard = properties.getEntryGuard();
        return entryGuard == null ? new SeckillProperties.EntryGuard() : entryGuard;
    }

    public record RequestDecision(RequestOutcome outcome) {
    }

    public record BuyerDecision(BuyerOutcome outcome, String existingRequestId) {
    }

    public enum RequestOutcome {
        ACQUIRED,
        DUPLICATE
    }

    public enum BuyerOutcome {
        ACQUIRED,
        SAME_REQUEST,
        DUPLICATE_BUYER
    }
}
