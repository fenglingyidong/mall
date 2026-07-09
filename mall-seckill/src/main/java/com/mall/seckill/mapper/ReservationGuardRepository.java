package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.seckill.pojo.entity.SeckillReservationGuardEntity;
import com.mall.seckill.service.impl.SeckillBucketService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Repository
public class ReservationGuardRepository {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DEDUCTED = "DEDUCTED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_RELEASED = "RELEASED";

    private static final ZoneId ZONE_ID = ZoneId.systemDefault();
    private static final int FAIL_REASON_MAX_LENGTH = 255;

    private final SeckillReservationGuardMapper mapper;

    public ReservationGuardRepository(SeckillReservationGuardMapper mapper) {
        this.mapper = mapper;
    }

    public CreateGuardResult createOrLoad(String requestId, ReservationDraft draft) {
        SeckillReservationGuardEntity existing = findByRequestId(requestId);
        if (existing != null) {
            return new CreateGuardResult(GuardCreateOutcome.REQUEST_DUPLICATE, existing);
        }

        SeckillReservationGuardEntity guard = new SeckillReservationGuardEntity();
        guard.setReservationId(draft.reservationId());
        guard.setRequestId(requestId);
        guard.setActivityId(draft.activityId());
        guard.setSkuId(draft.skuId());
        guard.setUserId(draft.userId());
        guard.setGuardShardKey(draft.userId());
        guard.setActiveKey(activeKey(draft.userId()));
        guard.setStatus(STATUS_PROCESSING);
        LocalDateTime now = LocalDateTime.now();
        guard.setCreatedAt(now);
        guard.setUpdatedAt(now);
        try {
            mapper.insert(guard);
            return new CreateGuardResult(GuardCreateOutcome.CREATED, guard);
        } catch (DuplicateKeyException exception) {
            SeckillReservationGuardEntity byRequest = findByRequestId(requestId);
            if (byRequest != null) {
                return new CreateGuardResult(GuardCreateOutcome.REQUEST_DUPLICATE, byRequest);
            }
            SeckillReservationGuardEntity byActiveKey = findByActivityAndActiveKey(draft.activityId(), activeKey(draft.userId()));
            if (byActiveKey != null) {
                return new CreateGuardResult(GuardCreateOutcome.ACTIVE_DUPLICATE, byActiveKey);
            }
            throw exception;
        }
    }

    public boolean attachBucket(String reservationId, SeckillBucketService.SelectedBucket bucket) {
        return mapper.update(null, Wrappers.<SeckillReservationGuardEntity>lambdaUpdate()
                .eq(SeckillReservationGuardEntity::getReservationId, reservationId)
                .eq(SeckillReservationGuardEntity::getStatus, STATUS_PROCESSING)
                .set(SeckillReservationGuardEntity::getBucketId, bucket.bucketId())
                .set(SeckillReservationGuardEntity::getBucketNo, bucket.bucketNo())
                .set(SeckillReservationGuardEntity::getBucketShardKey, bucket.bucketShardKey())
                .set(SeckillReservationGuardEntity::getUpdatedAt, LocalDateTime.now())) > 0;
    }

    public boolean markDeducted(String reservationId) {
        return mapper.update(null, Wrappers.<SeckillReservationGuardEntity>lambdaUpdate()
                .eq(SeckillReservationGuardEntity::getReservationId, reservationId)
                .eq(SeckillReservationGuardEntity::getStatus, STATUS_PROCESSING)
                .set(SeckillReservationGuardEntity::getStatus, STATUS_DEDUCTED)
                .set(SeckillReservationGuardEntity::getUpdatedAt, LocalDateTime.now())) > 0;
    }

    public boolean markFailedIfProcessing(String reservationId, String reason) {
        return mapper.update(null, Wrappers.<SeckillReservationGuardEntity>lambdaUpdate()
                .eq(SeckillReservationGuardEntity::getReservationId, reservationId)
                .eq(SeckillReservationGuardEntity::getStatus, STATUS_PROCESSING)
                .set(SeckillReservationGuardEntity::getStatus, STATUS_FAILED)
                .set(SeckillReservationGuardEntity::getActiveKey, null)
                .set(SeckillReservationGuardEntity::getFailReason, truncate(reason))
                .set(SeckillReservationGuardEntity::getUpdatedAt, LocalDateTime.now())) > 0;
    }

    public boolean markConfirmed(String reservationId) {
        return mapper.update(null, Wrappers.<SeckillReservationGuardEntity>lambdaUpdate()
                .eq(SeckillReservationGuardEntity::getReservationId, reservationId)
                .in(SeckillReservationGuardEntity::getStatus, STATUS_PROCESSING, STATUS_DEDUCTED)
                .set(SeckillReservationGuardEntity::getStatus, STATUS_CONFIRMED)
                .set(SeckillReservationGuardEntity::getUpdatedAt, LocalDateTime.now())) > 0;
    }

    public boolean markReleasedFromDeducted(String reservationId, String reason) {
        return mapper.update(null, Wrappers.<SeckillReservationGuardEntity>lambdaUpdate()
                .eq(SeckillReservationGuardEntity::getReservationId, reservationId)
                .eq(SeckillReservationGuardEntity::getStatus, STATUS_DEDUCTED)
                .set(SeckillReservationGuardEntity::getStatus, STATUS_RELEASED)
                .set(SeckillReservationGuardEntity::getActiveKey, null)
                .set(SeckillReservationGuardEntity::getFailReason, truncate(reason))
                .set(SeckillReservationGuardEntity::getUpdatedAt, LocalDateTime.now())) > 0;
    }

    public boolean markReleasedFromConfirmed(String reservationId, String reason) {
        return mapper.update(null, Wrappers.<SeckillReservationGuardEntity>lambdaUpdate()
                .eq(SeckillReservationGuardEntity::getReservationId, reservationId)
                .eq(SeckillReservationGuardEntity::getStatus, STATUS_CONFIRMED)
                .set(SeckillReservationGuardEntity::getStatus, STATUS_RELEASED)
                .set(SeckillReservationGuardEntity::getActiveKey, null)
                .set(SeckillReservationGuardEntity::getFailReason, truncate(reason))
                .set(SeckillReservationGuardEntity::getUpdatedAt, LocalDateTime.now())) > 0;
    }

    public boolean markReleased(String reservationId, String reason) {
        return mapper.update(null, Wrappers.<SeckillReservationGuardEntity>lambdaUpdate()
                .eq(SeckillReservationGuardEntity::getReservationId, reservationId)
                .in(SeckillReservationGuardEntity::getStatus, STATUS_PROCESSING, STATUS_DEDUCTED, STATUS_CONFIRMED)
                .set(SeckillReservationGuardEntity::getStatus, STATUS_RELEASED)
                .set(SeckillReservationGuardEntity::getActiveKey, null)
                .set(SeckillReservationGuardEntity::getFailReason, truncate(reason))
                .set(SeckillReservationGuardEntity::getUpdatedAt, LocalDateTime.now())) > 0;
    }

    public List<SeckillReservationGuardEntity> findStaleProcessing(Instant before, int limit) {
        return mapper.selectList(Wrappers.<SeckillReservationGuardEntity>lambdaQuery()
                .eq(SeckillReservationGuardEntity::getStatus, STATUS_PROCESSING)
                .lt(SeckillReservationGuardEntity::getUpdatedAt, toLocalDateTime(before))
                .orderByAsc(SeckillReservationGuardEntity::getUpdatedAt)
                .last("LIMIT " + Math.max(1, limit)));
    }

    public SeckillReservationGuardEntity findByReservationId(String reservationId) {
        return mapper.selectOne(Wrappers.<SeckillReservationGuardEntity>lambdaQuery()
                .eq(SeckillReservationGuardEntity::getReservationId, reservationId));
    }

    public SeckillReservationGuardEntity findByRequestId(String requestId) {
        return mapper.selectOne(Wrappers.<SeckillReservationGuardEntity>lambdaQuery()
                .eq(SeckillReservationGuardEntity::getRequestId, requestId));
    }

    public SeckillReservationGuardEntity findByActivityAndActiveKey(Long activityId, String activeKey) {
        return mapper.selectOne(Wrappers.<SeckillReservationGuardEntity>lambdaQuery()
                .eq(SeckillReservationGuardEntity::getActivityId, activityId)
                .eq(SeckillReservationGuardEntity::getActiveKey, activeKey));
    }

    public static String activeKey(Long userId) {
        return userId == null ? null : String.valueOf(userId);
    }

    private LocalDateTime toLocalDateTime(Instant value) {
        return (value == null ? Instant.now() : value).atZone(ZONE_ID).toLocalDateTime();
    }

    private String truncate(String value) {
        if (value == null || value.length() <= FAIL_REASON_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, FAIL_REASON_MAX_LENGTH);
    }

    public record ReservationDraft(String reservationId,
                                   Long activityId,
                                   Long skuId,
                                   Long userId) {
    }

    public record CreateGuardResult(GuardCreateOutcome outcome,
                                    SeckillReservationGuardEntity guard) {
    }

    public enum GuardCreateOutcome {
        CREATED,
        REQUEST_DUPLICATE,
        ACTIVE_DUPLICATE
    }
}
