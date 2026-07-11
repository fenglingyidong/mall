package com.mall.seckill.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.message.ReliableMessagePublisher;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.mapper.SeckillRepository;
import com.mall.seckill.mapper.SeckillStockChangeLogMapper;
import com.mall.seckill.pojo.entity.SeckillSku;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillOrderOutboxFromChangeLogServiceTest {

    @Mock
    private SeckillStockChangeLogMapper changeLogMapper;

    @Mock
    private SeckillRepository seckillRepository;

    @Mock
    private ReliableMessagePublisher messagePublisher;

    private SeckillProperties properties;
    private SeckillOrderOutboxFromChangeLogService service;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getOrderOutbox().setEnabled(true);
        service = new SeckillOrderOutboxFromChangeLogService(
                changeLogMapper,
                seckillRepository,
                messagePublisher,
                new ObjectMapper(),
                new TransactionTemplate(new NoopTransactionManager()),
                properties,
                null);
    }

    @Test
    void drainShardShouldBatchClaimBuildOutboxesAndMarkOutboxed() throws Exception {
        SeckillStockChangeLogEntity first = changeLog(11L, "req-1", 3L, "DEDUCT");
        SeckillStockChangeLogEntity second = changeLog(12L, "req-2", 3L, "RELEASE");
        when(changeLogMapper.selectByStatusForConsumeByShard(3L, SeckillStockChangeLogStatus.NEW, 200))
                .thenReturn(List.of(first, second));
        when(changeLogMapper.claimStatusByIdsAndShard(eq(List.of(11L, 12L)), eq(3L), any(), any(LocalDateTime.class)))
                .thenReturn(2);
        when(changeLogMapper.selectClaimedByTokenAndShard(eq(3L), any()))
                .thenReturn(List.of(first, second));
        when(seckillRepository.findStockSnapshots(List.of("req-1"), 3L))
                .thenReturn(Map.of("req-1", new SeckillRepository.StockSnapshot("req-1", 1L, 1001L, 2001L, 2, "DEDUCTED")));
        when(seckillRepository.findSkusByActivityAndSkuIds(Map.of(1L, Set.of(1001L))))
                .thenReturn(Map.of(new SeckillRepository.ActivitySkuKey(1L, 1001L),
                        new SeckillSku(10L, 1L, 1001L, "phone", BigDecimal.valueOf(99), 50)));
        when(changeLogMapper.updateStatusByIdsAndClaimToken(eq(List.of(11L, 12L)), eq(3L), any(), eq(SeckillStockChangeLogStatus.OUTBOXED)))
                .thenReturn(2);

        int drained = service.drainShard(3L, 1);

        assertThat(drained).isEqualTo(2);
        ArgumentCaptor<List<ReliableMessagePublisher.SeckillOrderCreateOutbox>> outboxCaptor = ArgumentCaptor.forClass(List.class);
        verify(messagePublisher).enqueueSeckillOrderCreateBatch(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue()).hasSize(1);
        ReliableMessagePublisher.SeckillOrderCreateOutbox outbox = outboxCaptor.getValue().getFirst();
        assertThat(outbox.requestId()).isEqualTo("req-1");
        assertThat(outbox.bucketShardKey()).isEqualTo(3L);
        JsonNode payload = new ObjectMapper().readTree(outbox.payload());
        assertThat(payload.get("requestId").asText()).isEqualTo("req-1");
        assertThat(payload.get("activityId").asLong()).isEqualTo(1L);
        assertThat(payload.get("userId").asLong()).isEqualTo(2001L);
        assertThat(payload.get("skuId").asLong()).isEqualTo(1001L);
        assertThat(payload.get("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void drainShardShouldReturnZeroWhenDisabled() {
        properties.getOrderOutbox().setEnabled(false);

        int drained = service.drainShard(3L, 1);

        assertThat(drained).isZero();
        verifyNoInteractions(changeLogMapper, seckillRepository, messagePublisher);
    }

    @Test
    void resetStaleOutboxingShouldUseConfiguredClaimTimeout() {
        properties.getOrderOutbox().setClaimTimeoutSeconds(7);
        when(changeLogMapper.resetStaleOutboxingByShard(eq(3L), any(LocalDateTime.class)))
                .thenReturn(2);
        LocalDateTime beforeCall = LocalDateTime.now().minusSeconds(8);

        int reset = service.resetStaleOutboxing(3L);

        assertThat(reset).isEqualTo(2);
        ArgumentCaptor<LocalDateTime> beforeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(changeLogMapper).resetStaleOutboxingByShard(eq(3L), beforeCaptor.capture());
        assertThat(beforeCaptor.getValue()).isAfter(beforeCall);
    }

    @Test
    void drainShardShouldSkipPublisherForReleaseOnlyBatch() {
        SeckillStockChangeLogEntity release = changeLog(12L, "req-2", 3L, "RELEASE");
        when(changeLogMapper.selectByStatusForConsumeByShard(3L, SeckillStockChangeLogStatus.NEW, 200))
                .thenReturn(List.of(release));
        when(changeLogMapper.claimStatusByIdsAndShard(eq(List.of(12L)), eq(3L), any(), any(LocalDateTime.class)))
                .thenReturn(1);
        when(changeLogMapper.selectClaimedByTokenAndShard(eq(3L), any()))
                .thenReturn(List.of(release));
        when(changeLogMapper.updateStatusByIdsAndClaimToken(eq(List.of(12L)), eq(3L), any(), eq(SeckillStockChangeLogStatus.OUTBOXED)))
                .thenReturn(1);

        int drained = service.drainShard(3L, 1);

        assertThat(drained).isEqualTo(1);
        verify(messagePublisher).enqueueSeckillOrderCreateBatch(List.of());
        verifyNoInteractions(seckillRepository);
    }

    private SeckillStockChangeLogEntity changeLog(Long id, String requestId, Long bucketShardKey, String changeType) {
        SeckillStockChangeLogEntity changeLog = new SeckillStockChangeLogEntity();
        changeLog.setId(id);
        changeLog.setRequestId(requestId);
        changeLog.setActivityId(1L);
        changeLog.setSkuId(1001L);
        changeLog.setBucketShardKey(bucketShardKey);
        changeLog.setChangeType(changeType);
        changeLog.setStatus(SeckillStockChangeLogStatus.NEW);
        return changeLog;
    }

    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
