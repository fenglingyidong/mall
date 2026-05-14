package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BusinessException;
import com.mall.common.util.JsonUtils;
import com.mall.message.ReliableMessagePublisher;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.RedisSeckillExecutor;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.pojo.dto.SeckillOrderRequest;
import com.mall.seckill.pojo.entity.SeckillActivity;
import com.mall.seckill.pojo.entity.SeckillSku;
import com.mall.seckill.pojo.vo.SeckillActivityView;
import com.mall.seckill.pojo.vo.SeckillResult;
import com.mall.seckill.pojo.vo.SeckillSubmitResponse;
import com.mall.seckill.service.SeckillService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Service
public class SeckillServiceImpl implements SeckillService {

    private final SeckillRepository repository;
    private final RedisSeckillExecutor redisExecutor;
    private final SentinelSeckillGuard sentinelGuard;
    private final ReliableMessagePublisher messagePublisher;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;
    private final SeckillProperties properties;

    public SeckillServiceImpl(SeckillRepository repository,
                              RedisSeckillExecutor redisExecutor,
                              SentinelSeckillGuard sentinelGuard,
                              ReliableMessagePublisher messagePublisher,
                              ObjectMapper objectMapper,
                              ObjectProvider<RedissonClient> redissonClient,
                              SeckillProperties properties) {
        this.repository = repository;
        this.redisExecutor = redisExecutor;
        this.sentinelGuard = sentinelGuard;
        this.messagePublisher = messagePublisher;
        this.objectMapper = objectMapper;
        this.redissonClient = redissonClient.getIfAvailable();
        this.properties = properties;
    }

    @Override
    public List<SeckillActivityView> activities() {
        return repository.activityViews();
    }

    @Override
    public SeckillSubmitResponse submit(Long activityId, Long skuId) {
        sentinelGuard.checkSubmit();
        Long userId = UserContext.currentUserIdOrDefault(1L);
        SeckillActivity activity = repository.requireActivity(activityId);
        if (!activity.activeAt(Instant.now())) {
            throw new BusinessException(400, "Seckill activity is not active");
        }
        SeckillSku sku = repository.requireSku(activityId, skuId);
        if (redissonClient == null || !properties.getLock().isEnabled()) {
            return doSubmit(activityId, skuId, userId, sku);
        }
        RLock lock = redissonClient.getLock(lockKey(activityId, skuId, userId));
        boolean locked;
        try {
            locked = lock.tryLock(properties.getLock().getWaitMillis(),
                    properties.getLock().getLeaseMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(429, "Seckill submit interrupted");
        }
        if (!locked) {
            throw new BusinessException(429, "Duplicate seckill submit");
        }
        try {
            return doSubmit(activityId, skuId, userId, sku);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private SeckillSubmitResponse doSubmit(Long activityId, Long skuId, Long userId, SeckillSku sku) {
        String requestId = UUID.randomUUID().toString();
        Integer redisResult = redisExecutor.tryDeduct(activityId, skuId, userId);
        int result = redisResult == null ? repository.tryDeduct(activityId, skuId, userId) : redisResult;
        if (result == 1) {
            repository.saveResult(new SeckillResult(requestId, "FAILED", null, "Stock not enough"));
            return new SeckillSubmitResponse(requestId, "FAILED", "Stock not enough");
        }
        if (result == 2) {
            repository.saveResult(new SeckillResult(requestId, "FAILED", null, "Duplicate purchase"));
            return new SeckillSubmitResponse(requestId, "FAILED", "Duplicate purchase");
        }

        SeckillOrderRequest orderRequest = new SeckillOrderRequest(
                requestId,
                activityId,
                userId,
                skuId,
                sku.skuName(),
                sku.price(),
                1
        );
        messagePublisher.publishSeckillOrderCreate(requestId, JsonUtils.toJson(objectMapper, orderRequest));
        repository.saveResult(new SeckillResult(requestId, "PROCESSING", null, "Order creating"));
        return new SeckillSubmitResponse(requestId, "ACCEPTED", "Accepted");
    }

    private String lockKey(Long activityId, Long skuId, Long userId) {
        return "seckill:submit:lock:" + activityId + ":" + skuId + ":" + userId;
    }

    @Override
    public SeckillResult result(String requestId) {
        return repository.result(requestId);
    }
}
