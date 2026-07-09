package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.seckill.pojo.entity.SeckillReservationGuardEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationGuardRepositoryTest {

    @Mock
    private SeckillReservationGuardMapper mapper;

    @BeforeAll
    static void initMybatisPlusMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), SeckillReservationGuardEntity.class);
    }

    @Test
    void shouldCreateProcessingGuardWithActiveKey() {
        ReservationGuardRepository repository = new ReservationGuardRepository(mapper);

        ReservationGuardRepository.CreateGuardResult result = repository.createOrLoad(
                "r1",
                new ReservationGuardRepository.ReservationDraft("r1", 1L, 1001L, 101L));

        assertThat(result.outcome()).isEqualTo(ReservationGuardRepository.GuardCreateOutcome.CREATED);
        ArgumentCaptor<SeckillReservationGuardEntity> guardCaptor = ArgumentCaptor.forClass(SeckillReservationGuardEntity.class);
        org.mockito.Mockito.verify(mapper).insert(guardCaptor.capture());
        SeckillReservationGuardEntity guard = guardCaptor.getValue();
        assertThat(guard.getStatus()).isEqualTo(ReservationGuardRepository.STATUS_PROCESSING);
        assertThat(guard.getActiveKey()).isEqualTo("101");
        assertThat(guard.getGuardShardKey()).isEqualTo(101L);
    }

    @Test
    void shouldReportActiveDuplicateWhenUniqueGuardRejectsInsert() {
        ReservationGuardRepository repository = new ReservationGuardRepository(mapper);
        SeckillReservationGuardEntity duplicate = new SeckillReservationGuardEntity();
        duplicate.setReservationId("old-r1");
        duplicate.setRequestId("old-r1");
        duplicate.setStatus(ReservationGuardRepository.STATUS_DEDUCTED);
        when(mapper.insert(any(SeckillReservationGuardEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_guard_activity_active"));
        when(mapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(null)
                .thenReturn(duplicate);

        ReservationGuardRepository.CreateGuardResult result = repository.createOrLoad(
                "r1",
                new ReservationGuardRepository.ReservationDraft("r1", 1L, 1001L, 101L));

        assertThat(result.outcome()).isEqualTo(ReservationGuardRepository.GuardCreateOutcome.ACTIVE_DUPLICATE);
        assertThat(result.guard()).isSameAs(duplicate);
    }

    @Test
    void shouldMapGuardActiveKeyField() {
        TableInfo tableInfo = TableInfoHelper.getTableInfo(SeckillReservationGuardEntity.class);

        assertThat(tableInfo.getFieldList())
                .anySatisfy(field -> {
                    assertThat(field.getProperty()).isEqualTo("activeKey");
                    assertThat(field.getColumn()).isEqualTo("active_key");
                })
                .anySatisfy(field -> {
                    assertThat(field.getProperty()).isEqualTo("bucketShardKey");
                    assertThat(field.getColumn()).isEqualTo("bucket_shard_key");
                });
    }
}
