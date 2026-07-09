package com.mall.seckill.service.impl;

import com.mall.message.MessageNames;
import com.mall.message.ReliableMessageRepository;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.ReservationGuardRepository;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.entity.SeckillReservationGuardEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillReservationRepairJobTest {

    @Mock
    private ReservationGuardRepository guardRepository;

    @Mock
    private SeckillRepository seckillRepository;

    @Mock
    private ReliableMessageRepository messageRepository;

    @Mock
    private SeckillStockChangeLogMapper changeLogMapper;

    @Test
    void shouldMarkConfirmedFromConfirmedSnapshotWithoutDeductingAgain() {
        SeckillReservationRepairJob job = job();
        SeckillReservationGuardEntity guard = guard("r1", 3L);
        when(guardRepository.findStaleProcessing(org.mockito.Mockito.any(), org.mockito.Mockito.anyInt()))
                .thenReturn(List.of(guard));
        when(seckillRepository.findStockSnapshot("r1", 3L))
                .thenReturn(new SeckillRepository.StockSnapshot("r1", 1L, 1001L, 101L, 1, "CONFIRMED"));

        job.repair();

        verify(guardRepository).markConfirmed("r1");
        verify(guardRepository, never()).markDeducted("r1");
        verify(guardRepository, never()).markFailedIfProcessing(org.mockito.Mockito.eq("r1"), org.mockito.Mockito.anyString());
    }

    @Test
    void shouldKeepProcessingWhenDeductedSnapshotHasIncompleteFacts() {
        SeckillReservationRepairJob job = job();
        SeckillReservationGuardEntity guard = guard("r1", 3L);
        when(guardRepository.findStaleProcessing(org.mockito.Mockito.any(), org.mockito.Mockito.anyInt()))
                .thenReturn(List.of(guard));
        when(seckillRepository.findStockSnapshot("r1", 3L))
                .thenReturn(new SeckillRepository.StockSnapshot("r1", 1L, 1001L, 101L, 1, "DEDUCTED"));
        when(messageRepository.existsByBusinessKeyAndRoutingKey("r1", MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, 3L))
                .thenReturn(true);
        when(changeLogMapper.countByRequestIdAndBucketShardKey("r1", 3L)).thenReturn(0L);

        job.repair();

        verify(guardRepository, never()).markDeducted("r1");
        verify(guardRepository, never()).markFailedIfProcessing(org.mockito.Mockito.eq("r1"), org.mockito.Mockito.anyString());
    }

    @Test
    void shouldReleaseOnlyWhenNoFactsExistBeyondSafeWindow() {
        SeckillProperties properties = properties();
        properties.getReservationGuard().setSafeReleaseAfterSeconds(2);
        SeckillReservationRepairJob job = job(properties);
        SeckillReservationGuardEntity guard = guard("r1", 3L);
        guard.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        when(guardRepository.findStaleProcessing(org.mockito.Mockito.any(), org.mockito.Mockito.anyInt()))
                .thenReturn(List.of(guard));

        job.repair();

        verify(guardRepository).markFailedIfProcessing("r1", "No deduction facts found in safe window");
    }

    private SeckillReservationRepairJob job() {
        return job(properties());
    }

    private SeckillReservationRepairJob job(SeckillProperties properties) {
        return new SeckillReservationRepairJob(
                guardRepository,
                seckillRepository,
                messageRepository,
                changeLogMapper,
                properties);
    }

    private SeckillProperties properties() {
        SeckillProperties properties = new SeckillProperties();
        properties.getReservationGuard().setProcessingProbeAfterSeconds(1);
        properties.getReservationGuard().setSafeReleaseAfterSeconds(60);
        properties.getReservationGuard().setRepairBatchSize(10);
        return properties;
    }

    private SeckillReservationGuardEntity guard(String reservationId, Long bucketShardKey) {
        SeckillReservationGuardEntity guard = new SeckillReservationGuardEntity();
        guard.setReservationId(reservationId);
        guard.setBucketShardKey(bucketShardKey);
        guard.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        return guard;
    }
}
