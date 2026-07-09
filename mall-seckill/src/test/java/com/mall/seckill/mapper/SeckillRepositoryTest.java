package com.mall.seckill.mapper;

import com.mall.seckill.pojo.entity.SeckillStockSnapshotEntity;
import com.mall.seckill.pojo.entity.SeckillStockBucketEntity;
import com.mall.seckill.pojo.entity.SeckillBucketConfigEntity;
import com.mall.seckill.pojo.entity.SeckillStockChangeLogEntity;
import com.mall.seckill.pojo.entity.SeckillSkuEntity;
import com.mall.seckill.pojo.entity.SeckillResultEntity;
import com.mall.seckill.config.SeckillProperties;
import com.mall.seckill.pojo.vo.StockDeductProbeResponse;
import com.mall.seckill.pojo.vo.StockDeductionResult;
import com.mall.seckill.pojo.vo.StockReleaseResult;
import com.mall.seckill.pojo.vo.StockVersion;
import com.mall.seckill.service.impl.SeckillBucketService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillRepositoryTest {

    @Mock
    private SeckillActivityMapper activityMapper;

    @Mock
    private SeckillSkuMapper skuMapper;

    @Mock
    private SeckillResultMapper resultMapper;

    @Mock
    private SeckillStockSnapshotMapper snapshotMapper;

    @Mock
    private SeckillStockChangeLogMapper changeLogMapper;

    @Mock
    private SeckillBucketService bucketService;

    private SeckillRepository repository;

    @BeforeAll
    static void initMybatisPlusMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), SeckillStockSnapshotEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), SeckillSkuEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), SeckillStockBucketEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), SeckillBucketConfigEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), SeckillStockChangeLogEntity.class);
    }

    @BeforeEach
    void setUp() {
        repository = new SeckillRepository(activityMapper, skuMapper, resultMapper, snapshotMapper, new SimpleMeterRegistry());
    }

    @Test
    void shouldRecordDeductionAndDeductOceanBaseStockInOneTransaction() {
        when(snapshotMapper.selectCount(any())).thenReturn(0L);
        when(skuMapper.deductStockAndIncreaseVersionById(10L, 1)).thenReturn(1);
        when(skuMapper.selectStockVersionById(10L)).thenReturn(new StockVersion(49, 1L));

        StockDeductionResult result = repository.recordDeduction("r1", 10L, 1L, 1001L, 101L, 1);

        assertThat(result.code()).isZero();
        assertThat(result.stockVersion()).isEqualTo(new StockVersion(49, 1L));
        ArgumentCaptor<SeckillStockSnapshotEntity> snapshotCaptor = ArgumentCaptor.forClass(SeckillStockSnapshotEntity.class);
        verify(snapshotMapper).insert(snapshotCaptor.capture());
        SeckillStockSnapshotEntity inserted = snapshotCaptor.getValue();
        assertThat(inserted.getRequestId()).isEqualTo("r1");
        assertThat(inserted.getStockId()).isEqualTo(10L);
        assertThat(inserted.getActivityId()).isEqualTo(1L);
        assertThat(inserted.getSkuId()).isEqualTo(1001L);
        assertThat(inserted.getUserId()).isEqualTo(101L);
        assertThat(inserted.getActiveKey()).isEqualTo(101L);
        assertThat(inserted.getQuantity()).isEqualTo(1);
        assertThat(inserted.getStatus()).isEqualTo("DEDUCTED");
        verify(skuMapper).deductStockAndIncreaseVersionById(10L, 1);
        verify(skuMapper).selectStockVersionById(10L);
        ArgumentCaptor<SeckillResultEntity> resultCaptor = ArgumentCaptor.forClass(SeckillResultEntity.class);
        verify(resultMapper).insert(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getRequestId()).isEqualTo("r1");
        assertThat(resultCaptor.getValue().getStatus()).isEqualTo("PROCESSING");
    }

    @Test
    void shouldRunOutboxHookBeforeHotStockUpdate() {
        Runnable beforeStockUpdate = mock(Runnable.class);
        when(snapshotMapper.selectCount(any())).thenReturn(0L);
        when(skuMapper.deductStockAndIncreaseVersionById(10L, 1)).thenReturn(1);
        when(skuMapper.selectStockVersionById(10L)).thenReturn(new StockVersion(49, 1L));

        repository.recordDeduction("r1", 10L, 1L, 1001L, 101L, 1, beforeStockUpdate);

        InOrder order = inOrder(snapshotMapper, beforeStockUpdate, skuMapper);
        order.verify(snapshotMapper).insert(any(SeckillStockSnapshotEntity.class));
        order.verify(beforeStockUpdate).run();
        order.verify(skuMapper).deductStockAndIncreaseVersionById(10L, 1);
    }

    @Test
    void shouldReturnDuplicateWhenActiveDeductionExistsForActivityUser() {
        when(snapshotMapper.selectCount(any())).thenReturn(1L);

        StockDeductionResult result = repository.recordDeduction("r1", 10L, 1L, 1001L, 101L, 1);

        assertThat(result.code()).isEqualTo(2);
        verify(snapshotMapper, never()).insert(any(SeckillStockSnapshotEntity.class));
        verify(skuMapper, never()).deductStockAndIncreaseVersionById(anyLong(), any());
        verify(resultMapper, never()).insert(any(SeckillResultEntity.class));
    }

    @Test
    void shouldReturnDuplicateWhenSnapshotUniqueKeyRejectsConcurrentInsert() {
        when(snapshotMapper.selectCount(any())).thenReturn(0L);
        when(snapshotMapper.insert(any(SeckillStockSnapshotEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate active user"));

        StockDeductionResult result = repository.recordDeduction("r1", 10L, 1L, 1001L, 101L, 1);

        assertThat(result.code()).isEqualTo(2);
        verify(skuMapper, never()).deductStockAndIncreaseVersionById(anyLong(), any());
        verify(resultMapper, never()).insert(any(SeckillResultEntity.class));
    }

    @Test
    void shouldThrowWhenStockNotEnoughAfterSnapshotInsert() {
        when(snapshotMapper.selectCount(any())).thenReturn(0L);
        when(skuMapper.deductStockAndIncreaseVersionById(10L, 1)).thenReturn(0);

        assertThatThrownBy(() -> repository.recordDeduction("r1", 10L, 1L, 1001L, 101L, 1))
                .isInstanceOf(SeckillStockNotEnoughException.class);

        verify(snapshotMapper).insert(any(SeckillStockSnapshotEntity.class));
        verify(skuMapper, never()).selectStockVersionById(anyLong());
        verify(resultMapper, never()).insert(any(SeckillResultEntity.class));
    }

    @Test
    void shouldRecordBucketDeductionWhenBucketModeEnabled() {
        SeckillRepository bucketRepository = bucketRepository();
        StockVersion stockVersion = new StockVersion(48, 8L);
        SeckillBucketService.SelectedBucket selectedBucket = new SeckillBucketService.SelectedBucket(99L, 3, 7L, 1L);
        when(snapshotMapper.selectCount(any())).thenReturn(0L);
        when(bucketService.selectBucket(1L, 1001L)).thenReturn(selectedBucket);
        when(bucketService.deduct(selectedBucket, "r1", 1L, 1001L, 1))
                .thenReturn(new SeckillBucketService.BucketMutationResult(stockVersion, 1000L, selectedBucket));
        when(snapshotMapper.updateBucketDeductionByRequestAndShardKey(any(SeckillStockSnapshotEntity.class))).thenReturn(1);

        StockDeductionResult result = bucketRepository.recordDeduction("r1", 10L, 1L, 1001L, 101L, 1);

        assertThat(result.code()).isZero();
        assertThat(result.stockVersion()).isEqualTo(stockVersion);
        ArgumentCaptor<SeckillStockSnapshotEntity> snapshotCaptor = ArgumentCaptor.forClass(SeckillStockSnapshotEntity.class);
        verify(snapshotMapper).insert(snapshotCaptor.capture());
        SeckillStockSnapshotEntity snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.getStockId()).isEqualTo(10L);
        assertThat(snapshot.getBucketId()).isEqualTo(99L);
        assertThat(snapshot.getBucketNo()).isEqualTo(3);
        assertThat(snapshot.getBucketShardKey()).isEqualTo(3L);
        assertThat(snapshot.getStrategyVersion()).isEqualTo(7L);
        verify(bucketService).deduct(selectedBucket, "r1", 1L, 1001L, 1);
        verify(snapshotMapper).updateBucketDeductionByRequestAndShardKey(snapshot);
        verify(skuMapper, never()).deductStockAndIncreaseVersionById(anyLong(), any());
        ArgumentCaptor<SeckillResultEntity> resultCaptor = ArgumentCaptor.forClass(SeckillResultEntity.class);
        verify(resultMapper).insert(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getStatus()).isEqualTo("PROCESSING");
    }

    @Test
    void shouldUpdateBucketSnapshotToActualDeductedBucketWhenRetryMovesBucket() {
        SeckillRepository bucketRepository = bucketRepository();
        StockVersion stockVersion = new StockVersion(48, 8L);
        SeckillBucketService.SelectedBucket selectedBucket = new SeckillBucketService.SelectedBucket(99L, 3, 7L, 1L);
        SeckillBucketService.SelectedBucket actualBucket = new SeckillBucketService.SelectedBucket(100L, 4, 7L, 1L);
        when(snapshotMapper.selectCount(any())).thenReturn(0L);
        when(bucketService.selectBucket(1L, 1001L)).thenReturn(selectedBucket);
        when(bucketService.deduct(selectedBucket, "r1", 1L, 1001L, 1))
                .thenReturn(new SeckillBucketService.BucketMutationResult(stockVersion, 1000L, actualBucket));
        when(snapshotMapper.updateBucketDeductionByRequestAndShardKey(any(SeckillStockSnapshotEntity.class))).thenReturn(1);

        StockDeductionResult result = bucketRepository.recordDeduction("r1", 10L, 1L, 1001L, 101L, 1);

        assertThat(result.code()).isZero();
        ArgumentCaptor<SeckillStockSnapshotEntity> updateCaptor = ArgumentCaptor.forClass(SeckillStockSnapshotEntity.class);
        verify(snapshotMapper).updateBucketDeductionByRequestAndShardKey(updateCaptor.capture());
        SeckillStockSnapshotEntity updated = updateCaptor.getValue();
        assertThat(updated.getBucketId()).isEqualTo(100L);
        assertThat(updated.getBucketNo()).isEqualTo(4);
        assertThat(updated.getBucketShardKey()).isEqualTo(4L);
        assertThat(updated.getStrategyVersion()).isEqualTo(7L);
        assertThat(updated.getChangeId()).isEqualTo(1000L);
    }

    @Test
    void shouldRegisterBucketSnapshotAsRegisteredWithoutDeductingOrSavingResult() {
        SeckillRepository bucketRepository = bucketRepository();
        SeckillBucketService.SelectedBucket selectedBucket = new SeckillBucketService.SelectedBucket(99L, 3, 7L, 1L);

        SeckillRepository.SnapshotRegistration registration = bucketRepository.registerBucketSnapshot(
                "r1", 10L, 1L, 1001L, 101L, 1, selectedBucket);

        assertThat(registration.outcome()).isEqualTo(SeckillRepository.SnapshotRegistrationOutcome.CREATED);
        ArgumentCaptor<SeckillStockSnapshotEntity> snapshotCaptor = ArgumentCaptor.forClass(SeckillStockSnapshotEntity.class);
        verify(snapshotMapper).insert(snapshotCaptor.capture());
        SeckillStockSnapshotEntity snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.getRequestId()).isEqualTo("r1");
        assertThat(snapshot.getActiveKey()).isEqualTo(101L);
        assertThat(snapshot.getBucketId()).isEqualTo(99L);
        assertThat(snapshot.getBucketShardKey()).isEqualTo(3L);
        assertThat(snapshot.getStatus()).isEqualTo("REGISTERED");
        verify(bucketService, never()).deduct(any(), any(), any(), any(), anyInt());
        verify(bucketService, never()).deductSelected(any(), any(), any(), any(), anyInt());
        verify(resultMapper, never()).insert(any(SeckillResultEntity.class));
    }

    @Test
    void shouldReportRequestDuplicateWhenSnapshotRequestAlreadyExists() {
        SeckillRepository bucketRepository = bucketRepository();
        SeckillStockSnapshotEntity existing = snapshot("r1", "REGISTERED", 1);
        when(snapshotMapper.selectById("r1")).thenReturn(existing);

        SeckillRepository.SnapshotRegistration registration = bucketRepository.registerBucketSnapshot(
                "r1", 10L, 1L, 1001L, 101L, 1, new SeckillBucketService.SelectedBucket(99L, 3, 7L, 1L));

        assertThat(registration.outcome()).isEqualTo(SeckillRepository.SnapshotRegistrationOutcome.REQUEST_DUPLICATE);
        assertThat(registration.snapshot()).isNotNull();
        verify(snapshotMapper, never()).insert(any(SeckillStockSnapshotEntity.class));
    }

    @Test
    void shouldReportActiveDuplicateWhenSnapshotActiveKeyRejectsInsert() {
        SeckillRepository bucketRepository = bucketRepository();
        when(snapshotMapper.selectById("r2")).thenReturn(null);
        when(snapshotMapper.insert(any(SeckillStockSnapshotEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate active user"));

        SeckillRepository.SnapshotRegistration registration = bucketRepository.registerBucketSnapshot(
                "r2", 10L, 1L, 1001L, 101L, 1, new SeckillBucketService.SelectedBucket(99L, 3, 7L, 1L));

        assertThat(registration.outcome()).isEqualTo(SeckillRepository.SnapshotRegistrationOutcome.ACTIVE_DUPLICATE);
        assertThat(registration.snapshot()).isNull();
        verify(snapshotMapper).insert(any(SeckillStockSnapshotEntity.class));
    }

    @Test
    void shouldRecordBucketDeductionFactWithoutUpdatingSnapshotOrSavingResult() {
        SeckillRepository bucketRepository = bucketRepository();
        StockVersion stockVersion = new StockVersion(48, 8L);
        SeckillBucketService.SelectedBucket selectedBucket = new SeckillBucketService.SelectedBucket(99L, 3, 7L, 1L);
        when(changeLogMapper.countByRequestIdAndChangeType("r1", "DEDUCT")).thenReturn(0L);
        when(bucketService.deductSelected(selectedBucket, "r1", 1L, 1001L, 1))
                .thenReturn(new SeckillBucketService.BucketMutationResult(stockVersion, 1000L, selectedBucket));

        StockDeductionResult result = bucketRepository.recordBucketDeductionFact("r1", 1L, 1001L, selectedBucket, 1);

        assertThat(result.code()).isZero();
        assertThat(result.stockVersion()).isEqualTo(stockVersion);
        verify(snapshotMapper, never()).updateBucketDeductionByRequestAndShardKey(any(SeckillStockSnapshotEntity.class));
        verify(resultMapper, never()).insert(any(SeckillResultEntity.class));
    }

    @Test
    void shouldSkipBucketDeductionWhenDeductFactAlreadyExists() {
        SeckillRepository bucketRepository = bucketRepository();
        SeckillBucketService.SelectedBucket selectedBucket = new SeckillBucketService.SelectedBucket(99L, 3, 7L, 1L);
        when(changeLogMapper.countByRequestIdAndChangeType("r1", "DEDUCT")).thenReturn(1L);

        StockDeductionResult result = bucketRepository.recordBucketDeductionFact("r1", 1L, 1001L, selectedBucket, 1);

        assertThat(result.code()).isEqualTo(2);
        verify(bucketService, never()).deductSelected(any(), any(), any(), any(), anyInt());
        verify(snapshotMapper, never()).updateBucketDeductionByRequestAndShardKey(any(SeckillStockSnapshotEntity.class));
        verify(resultMapper, never()).insert(any(SeckillResultEntity.class));
    }

    @Test
    void shouldRollbackTransactionWhenDeductFactDuplicateRacesAfterBucketDeduct() {
        SeckillRepository bucketRepository = bucketRepository();
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        SeckillRepository transactionalRepository = transactionalProxy(bucketRepository, transactionManager);
        SeckillBucketService.SelectedBucket selectedBucket = new SeckillBucketService.SelectedBucket(99L, 3, 7L, 1L);
        when(changeLogMapper.countByRequestIdAndChangeType("r1", "DEDUCT")).thenReturn(0L);
        when(bucketService.deductSelected(selectedBucket, "r1", 1L, 1001L, 1))
                .thenThrow(new DuplicateKeyException("duplicate deduct fact"));

        StockDeductionResult result = transactionalRepository.recordBucketDeductionFact("r1", 1L, 1001L, selectedBucket, 1);

        assertThat(result.code()).isEqualTo(2);
        assertThat(transactionManager.rolledBack).isTrue();
        assertThat(transactionManager.committed).isFalse();
        verify(snapshotMapper, never()).updateBucketDeductionByRequestAndShardKey(any(SeckillStockSnapshotEntity.class));
        verify(resultMapper, never()).insert(any(SeckillResultEntity.class));
    }

    @Test
    void shouldMarkRegisteredSnapshotFailedAndReleaseActiveKey() {
        repository.markRegisteredSnapshotFailed("r1", "failed");

        verify(snapshotMapper).releaseActiveKeyIfRegistered("r1", "failed");
    }

    @Test
    void shouldDeductStockOnlyForLoadTestWithoutLedgerWrites() {
        StockVersion stockVersion = new StockVersion(999, 2L);
        when(skuMapper.deductStockAndIncreaseVersionById(10L, 1)).thenReturn(1);
        when(skuMapper.selectStockVersionById(10L)).thenReturn(stockVersion);

        StockDeductProbeResponse response = repository.deductStockOnly(10L, 1);

        assertThat(response.deducted()).isTrue();
        assertThat(response.stock()).isEqualTo(999);
        assertThat(response.version()).isEqualTo(2L);
        verify(skuMapper).deductStockAndIncreaseVersionById(10L, 1);
        verify(skuMapper).selectStockVersionById(10L);
        verify(snapshotMapper, never()).insert(any(SeckillStockSnapshotEntity.class));
        verify(resultMapper, never()).insert(any(SeckillResultEntity.class));
    }

    @Test
    void shouldReturnCurrentStockWhenStockOnlyDeductMisses() {
        StockVersion stockVersion = new StockVersion(0, 1000L);
        when(skuMapper.deductStockAndIncreaseVersionById(10L, 1)).thenReturn(0);
        when(skuMapper.selectStockVersionById(10L)).thenReturn(stockVersion);

        StockDeductProbeResponse response = repository.deductStockOnly(10L, 1);

        assertThat(response.deducted()).isFalse();
        assertThat(response.stock()).isZero();
        assertThat(response.version()).isEqualTo(1000L);
        verify(snapshotMapper, never()).insert(any(SeckillStockSnapshotEntity.class));
        verify(resultMapper, never()).insert(any(SeckillResultEntity.class));
    }

    @Test
    void shouldConfirmDeductionWithoutDeductingStockAgain() {
        SeckillStockSnapshotEntity snapshot = snapshot("r1", "DEDUCTED", 1);
        when(snapshotMapper.selectById("r1")).thenReturn(snapshot);
        when(snapshotMapper.update(isNull(), any())).thenReturn(1);

        SeckillRepository.StockSnapshot result = repository.confirmDeduction("r1", "S10001", "Order created");

        assertThat(result.status()).isEqualTo("CONFIRMED");
        verify(snapshotMapper).update(isNull(), any());
        verify(skuMapper, never()).deductStock(anyLong(), anyLong(), any());
        verify(skuMapper, never()).deductStockAndIncreaseVersion(anyLong(), anyLong(), any());
        verify(skuMapper, never()).deductStockAndIncreaseVersionById(anyLong(), any());
        verify(skuMapper, never()).selectStockVersion(anyLong(), anyLong());
        verify(skuMapper, never()).selectStockVersionById(anyLong());
    }

    @Test
    void shouldReleaseDeductionAndReturnStockVersion() {
        SeckillStockSnapshotEntity snapshot = snapshot("r1", "DEDUCTED", 1);
        StockVersion stockVersion = new StockVersion(50, 2L);
        when(snapshotMapper.selectById("r1")).thenReturn(snapshot);
        when(snapshotMapper.update(isNull(), any())).thenReturn(1);
        when(skuMapper.releaseStockAndIncreaseVersionById(10L, 1)).thenReturn(1);
        when(skuMapper.selectStockVersionById(10L)).thenReturn(stockVersion);

        StockReleaseResult result = repository.releaseDeduction("r1", "Order failed");

        assertThat(result.snapshot().status()).isEqualTo("RELEASED");
        assertThat(snapshot.getActiveKey()).isNull();
        assertThat(result.stockVersion()).isEqualTo(stockVersion);
        InOrder order = inOrder(snapshotMapper, skuMapper);
        order.verify(snapshotMapper).update(isNull(), any());
        order.verify(skuMapper).releaseStockAndIncreaseVersionById(10L, 1);
        order.verify(skuMapper).selectStockVersionById(10L);
        verify(skuMapper, never()).releaseStockAndIncreaseVersion(anyLong(), anyLong(), any());
        verify(skuMapper, never()).selectStockVersion(anyLong(), anyLong());
        verify(skuMapper, never()).releaseStock(anyLong(), anyLong(), any());
    }

    @Test
    void shouldReleaseBucketDeductionWhenBucketModeEnabled() {
        SeckillRepository bucketRepository = bucketRepository();
        SeckillStockSnapshotEntity snapshot = snapshot("r1", "DEDUCTED", 1);
        snapshot.setBucketId(99L);
        snapshot.setBucketNo(3);
        StockVersion stockVersion = new StockVersion(50, 9L);
        when(snapshotMapper.selectById("r1")).thenReturn(snapshot);
        when(snapshotMapper.update(isNull(), any())).thenReturn(1);
        when(bucketService.release(snapshot)).thenReturn(stockVersion);

        StockReleaseResult result = bucketRepository.releaseDeduction("r1", "Order failed");

        assertThat(result.snapshot().status()).isEqualTo("RELEASED");
        assertThat(result.stockVersion()).isEqualTo(stockVersion);
        verify(bucketService).release(snapshot);
        verify(skuMapper, never()).releaseStockAndIncreaseVersionById(anyLong(), any());
        verify(skuMapper, never()).selectStockVersionById(anyLong());
    }

    @Test
    void shouldMapSnapshotActiveKeyField() {
        TableInfo tableInfo = TableInfoHelper.getTableInfo(SeckillStockSnapshotEntity.class);

        assertThat(tableInfo.getFieldList())
                .anySatisfy(field -> {
                    assertThat(field.getProperty()).isEqualTo("activeKey");
                    assertThat(field.getColumn()).isEqualTo("active_key");
                });
    }

    @Test
    void shouldMapSnapshotBucketFields() {
        TableInfo tableInfo = TableInfoHelper.getTableInfo(SeckillStockSnapshotEntity.class);

        assertThat(tableInfo.getFieldList())
                .anySatisfy(field -> {
                    assertThat(field.getProperty()).isEqualTo("bucketId");
                    assertThat(field.getColumn()).isEqualTo("bucket_id");
                })
                .anySatisfy(field -> {
                    assertThat(field.getProperty()).isEqualTo("bucketNo");
                    assertThat(field.getColumn()).isEqualTo("bucket_no");
                })
                .anySatisfy(field -> {
                    assertThat(field.getProperty()).isEqualTo("bucketShardKey");
                    assertThat(field.getColumn()).isEqualTo("bucket_shard_key");
                })
                .anySatisfy(field -> {
                    assertThat(field.getProperty()).isEqualTo("strategyVersion");
                    assertThat(field.getColumn()).isEqualTo("strategy_version");
                });
    }

    @Test
    void shouldReturnDatabaseStockWithoutSubtractingDeductedLedgerAgain() {
        SeckillSkuEntity sku = new SeckillSkuEntity();
        sku.setActivityId(1L);
        sku.setSkuId(1001L);
        sku.setStock(10);
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));

        Map<String, Integer> stock = repository.stockSnapshot();

        assertThat(stock).containsEntry("1:1001", 10);
        verify(snapshotMapper, never()).selectList(any());
    }

    @Test
    void shouldMapSkuVersionField() {
        TableInfo tableInfo = TableInfoHelper.getTableInfo(SeckillSkuEntity.class);

        assertThat(tableInfo.getFieldList())
                .anySatisfy(field -> {
                    assertThat(field.getProperty()).isEqualTo("version");
                    assertThat(field.getColumn()).isEqualTo("version");
                });
    }

    @Test
    void shouldUseStockVersionValueObject() {
        StockVersion stockVersion = new StockVersion(9, 3L);
        StockDeductionResult result = StockDeductionResult.success(stockVersion);

        assertThat(result.code()).isZero();
        assertThat(result.stockVersion()).isEqualTo(stockVersion);
        assertThat(StockDeductionResult.duplicate().code()).isEqualTo(2);
    }

    private SeckillStockSnapshotEntity snapshot(String requestId, String status, int quantity) {
        SeckillStockSnapshotEntity snapshot = new SeckillStockSnapshotEntity();
        snapshot.setRequestId(requestId);
        snapshot.setStockId(10L);
        snapshot.setActivityId(1L);
        snapshot.setSkuId(1001L);
        snapshot.setUserId(101L);
        snapshot.setActiveKey(101L);
        snapshot.setQuantity(quantity);
        snapshot.setStatus(status);
        return snapshot;
    }

    private SeckillRepository bucketRepository() {
        SeckillProperties properties = new SeckillProperties();
        properties.getBucket().setEnabled(true);
        return new SeckillRepository(
                activityMapper,
                skuMapper,
                resultMapper,
                snapshotMapper,
                changeLogMapper,
                bucketService,
                properties,
                new SimpleMeterRegistry());
    }

    private SeckillRepository transactionalProxy(SeckillRepository target, RecordingTransactionManager transactionManager) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (SeckillRepository) proxyFactory.getProxy();
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private boolean committed;
        private boolean rolledBack;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack = true;
        }
    }
}
