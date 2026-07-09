package com.mall.seckill.service.impl;

import com.mall.message.MessageNames;
import com.mall.message.ReliableMessageRepository;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.ReservationGuardRepository;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.entity.SeckillReservationGuardEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "mall.seckill.reservation-guard", name = "enabled", havingValue = "true")
public class SeckillReservationRepairJob {

    private static final Logger log = LoggerFactory.getLogger(SeckillReservationRepairJob.class);
    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    private final ReservationGuardRepository guardRepository;
    private final SeckillRepository seckillRepository;
    private final ReliableMessageRepository messageRepository;
    private final SeckillStockChangeLogMapper changeLogMapper;
    private final SeckillProperties properties;

    public SeckillReservationRepairJob(ReservationGuardRepository guardRepository,
                                       SeckillRepository seckillRepository,
                                       ReliableMessageRepository messageRepository,
                                       SeckillStockChangeLogMapper changeLogMapper,
                                       SeckillProperties properties) {
        this.guardRepository = guardRepository;
        this.seckillRepository = seckillRepository;
        this.messageRepository = messageRepository;
        this.changeLogMapper = changeLogMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${mall.seckill.reservation-guard.repair-fixed-delay:60000}")
    public void repair() {
        SeckillProperties.ReservationGuard config = properties.getReservationGuard();
        Instant probeBefore = Instant.now().minusSeconds(Math.max(1, config.getProcessingProbeAfterSeconds()));
        Instant safeReleaseBefore = Instant.now().minusSeconds(Math.max(
                config.getProcessingProbeAfterSeconds(),
                config.getSafeReleaseAfterSeconds()));
        List<SeckillReservationGuardEntity> guards = guardRepository.findStaleProcessing(
                probeBefore,
                Math.max(1, config.getRepairBatchSize()));
        for (SeckillReservationGuardEntity guard : guards) {
            try {
                repairOne(guard, safeReleaseBefore);
            } catch (Exception exception) {
                log.warn("Failed to repair seckill reservation guard, reservationId={}", guard.getReservationId(), exception);
            }
        }
    }

    private void repairOne(SeckillReservationGuardEntity guard, Instant safeReleaseBefore) {
        if (guard.getBucketShardKey() == null) {
            releaseIfSafe(guard, safeReleaseBefore, "No bucket attached before deduction");
            return;
        }

        SeckillRepository.StockSnapshot snapshot = seckillRepository.findStockSnapshot(
                guard.getReservationId(),
                guard.getBucketShardKey());
        boolean outboxExists = messageRepository.existsByBusinessKeyAndRoutingKey(
                guard.getReservationId(),
                MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY,
                guard.getBucketShardKey());
        boolean changeLogExists = changeLogMapper.countByRequestIdAndBucketShardKey(
                guard.getReservationId(),
                guard.getBucketShardKey()) > 0;

        if (snapshot != null) {
            repairFromSnapshot(guard, snapshot, outboxExists, changeLogExists);
            return;
        }
        if (outboxExists || changeLogExists) {
            log.error("Seckill reservation facts exist but snapshot missing, reservationId={}, bucketShardKey={}, outboxExists={}, changeLogExists={}",
                    guard.getReservationId(), guard.getBucketShardKey(), outboxExists, changeLogExists);
            return;
        }

        releaseIfSafe(guard, safeReleaseBefore, "No deduction facts found in safe window");
    }

    private void repairFromSnapshot(SeckillReservationGuardEntity guard,
                                    SeckillRepository.StockSnapshot snapshot,
                                    boolean outboxExists,
                                    boolean changeLogExists) {
        if (ReservationGuardRepository.STATUS_CONFIRMED.equals(snapshot.status())) {
            guardRepository.markConfirmed(guard.getReservationId());
            return;
        }
        if (ReservationGuardRepository.STATUS_RELEASED.equals(snapshot.status())) {
            guardRepository.markReleased(guard.getReservationId(), "Snapshot already released");
            return;
        }
        if (ReservationGuardRepository.STATUS_DEDUCTED.equals(snapshot.status())) {
            if (outboxExists && changeLogExists) {
                guardRepository.markDeducted(guard.getReservationId());
                return;
            }
            log.error("Seckill reservation snapshot deducted but facts incomplete, reservationId={}, bucketShardKey={}, outboxExists={}, changeLogExists={}",
                    guard.getReservationId(), guard.getBucketShardKey(), outboxExists, changeLogExists);
            return;
        }
        log.error("Seckill reservation snapshot status is not repairable, reservationId={}, bucketShardKey={}, snapshotStatus={}",
                guard.getReservationId(), guard.getBucketShardKey(), snapshot.status());
    }

    private void releaseIfSafe(SeckillReservationGuardEntity guard, Instant safeReleaseBefore, String reason) {
        LocalDateTime safeBefore = safeReleaseBefore.atZone(ZONE_ID).toLocalDateTime();
        if (guard.getUpdatedAt() != null && guard.getUpdatedAt().isBefore(safeBefore)) {
            guardRepository.markFailedIfProcessing(guard.getReservationId(), reason);
        }
    }
}
