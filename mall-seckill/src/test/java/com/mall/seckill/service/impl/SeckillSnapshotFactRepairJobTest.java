package com.mall.seckill.service.impl;

import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.mapper.SeckillStockSnapshotMapper;
import com.mall.seckill.pojo.entity.SeckillStockSnapshotEntity;
import com.mall.seckill.pojo.vo.SeckillResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillSnapshotFactRepairJobTest {

    private static final String MISSING_FACT_MESSAGE = "Deduction fact missing";

    @Mock
    private SeckillStockSnapshotMapper snapshotMapper;

    @Mock
    private SeckillStockChangeLogMapper changeLogMapper;

    @Mock
    private SeckillRepository repository;

    private SeckillProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getSnapshotRepair().setEnabled(true);
    }

    @Test
    void shouldSkipQueryWhenDisabled() {
        properties.getSnapshotRepair().setEnabled(false);

        job().repair();

        verifyNoInteractions(snapshotMapper, changeLogMapper, repository);
    }

    @Test
    void shouldClampBatchSizeAndTimeoutWhenQueryingStaleSnapshots() {
        properties.getSnapshotRepair().setBatchSize(0);
        properties.getSnapshotRepair().setRegisteredTimeoutSeconds(0);
        when(snapshotMapper.findRegisteredBefore(any(LocalDateTime.class), eq(1)))
                .thenReturn(List.of());
        LocalDateTime startedAt = LocalDateTime.now();

        job().repair();

        ArgumentCaptor<LocalDateTime> beforeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(snapshotMapper).findRegisteredBefore(beforeCaptor.capture(), eq(1));
        assertThat(beforeCaptor.getValue()).isBefore(startedAt);
        assertThat(beforeCaptor.getValue()).isAfter(startedAt.minusSeconds(2));
    }

    @Test
    void shouldClampBatchSizeToOneThousand() {
        properties.getSnapshotRepair().setBatchSize(1001);
        when(snapshotMapper.findRegisteredBefore(any(LocalDateTime.class), eq(1000)))
                .thenReturn(List.of());

        job().repair();

        verify(snapshotMapper).findRegisteredBefore(any(LocalDateTime.class), eq(1000));
    }

    @Test
    void shouldFailRegisteredSnapshotWhenCurrentShardHasNoDeductFact() {
        SeckillStockSnapshotEntity snapshot = snapshot("r1", 7L);
        when(snapshotMapper.findRegisteredBefore(any(LocalDateTime.class), eq(200)))
                .thenReturn(List.of(snapshot));
        when(changeLogMapper.countByRequestIdAndChangeTypeAndBucketShardKey("r1", "DEDUCT", 7L))
                .thenReturn(0L);

        job().repair();

        verify(changeLogMapper).countByRequestIdAndChangeTypeAndBucketShardKey("r1", "DEDUCT", 7L);
        verify(changeLogMapper, never()).countByRequestIdAndChangeType(anyString(), anyString());
        verify(repository).markRegisteredSnapshotFailed("r1", MISSING_FACT_MESSAGE);
        verify(repository).saveResult(new SeckillResult("r1", "FAILED", null, MISSING_FACT_MESSAGE));
    }

    @Test
    void shouldKeepRegisteredSnapshotWhenCurrentShardHasDeductFact() {
        SeckillStockSnapshotEntity snapshot = snapshot("r1", 7L);
        when(snapshotMapper.findRegisteredBefore(any(LocalDateTime.class), eq(200)))
                .thenReturn(List.of(snapshot));
        when(changeLogMapper.countByRequestIdAndChangeTypeAndBucketShardKey("r1", "DEDUCT", 7L))
                .thenReturn(1L);

        job().repair();

        verify(changeLogMapper).countByRequestIdAndChangeTypeAndBucketShardKey("r1", "DEDUCT", 7L);
        verify(changeLogMapper, never()).countByRequestIdAndChangeType(anyString(), anyString());
        verifyNoInteractions(repository);
    }

    @Test
    void shouldSkipSnapshotWithoutBucketShardKey() {
        SeckillStockSnapshotEntity snapshot = snapshot("r1", null);
        when(snapshotMapper.findRegisteredBefore(any(LocalDateTime.class), eq(200)))
                .thenReturn(List.of(snapshot));

        job().repair();

        verify(changeLogMapper, never()).countByRequestIdAndChangeType(anyString(), anyString());
        verify(changeLogMapper, never()).countByRequestIdAndChangeTypeAndBucketShardKey(anyString(), anyString(), any());
        verifyNoInteractions(repository);
    }

    @Test
    void shouldContinueAfterSingleSnapshotException() {
        SeckillStockSnapshotEntity first = snapshot("r1", 7L);
        SeckillStockSnapshotEntity second = snapshot("r2", 8L);
        when(snapshotMapper.findRegisteredBefore(any(LocalDateTime.class), eq(200)))
                .thenReturn(List.of(first, second));
        when(changeLogMapper.countByRequestIdAndChangeTypeAndBucketShardKey("r1", "DEDUCT", 7L))
                .thenThrow(new IllegalStateException("lookup failed"));
        when(changeLogMapper.countByRequestIdAndChangeTypeAndBucketShardKey("r2", "DEDUCT", 8L))
                .thenReturn(0L);

        assertThatCode(() -> job().repair())
                .doesNotThrowAnyException();

        verify(repository).markRegisteredSnapshotFailed("r2", MISSING_FACT_MESSAGE);
        verify(repository).saveResult(new SeckillResult("r2", "FAILED", null, MISSING_FACT_MESSAGE));
    }

    private SeckillSnapshotFactRepairJob job() {
        return new SeckillSnapshotFactRepairJob(snapshotMapper, changeLogMapper, repository, properties);
    }

    private SeckillStockSnapshotEntity snapshot(String requestId, Long bucketShardKey) {
        SeckillStockSnapshotEntity snapshot = new SeckillStockSnapshotEntity();
        snapshot.setRequestId(requestId);
        snapshot.setBucketShardKey(bucketShardKey);
        snapshot.setStatus("REGISTERED");
        snapshot.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        return snapshot;
    }
}
