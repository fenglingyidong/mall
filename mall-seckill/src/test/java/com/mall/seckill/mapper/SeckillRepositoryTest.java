package com.mall.seckill.mapper;

import com.mall.seckill.pojo.entity.SeckillStockSnapshotEntity;
import com.mall.seckill.pojo.entity.SeckillSkuEntity;
import com.mall.seckill.pojo.entity.SeckillResultEntity;
import com.mall.seckill.pojo.vo.StockDeductProbeResponse;
import com.mall.seckill.pojo.vo.StockDeductionResult;
import com.mall.seckill.pojo.vo.StockReleaseResult;
import com.mall.seckill.pojo.vo.StockVersion;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
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

    private SeckillRepository repository;

    @BeforeAll
    static void initMybatisPlusMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), SeckillStockSnapshotEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), SeckillSkuEntity.class);
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
        snapshot.setQuantity(quantity);
        snapshot.setStatus(status);
        return snapshot;
    }
}
