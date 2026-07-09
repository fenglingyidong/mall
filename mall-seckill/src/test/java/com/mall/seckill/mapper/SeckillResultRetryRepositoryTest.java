package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.seckill.pojo.entity.SeckillResultRetryEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillResultRetryRepositoryTest {

    @Mock
    private SeckillResultRetryMapper mapper;

    @BeforeAll
    static void initMybatisPlusMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), SeckillResultRetryEntity.class);
    }

    @Test
    void shouldInsertFirstRetryRecord() {
        SeckillResultRetryRepository repository = new SeckillResultRetryRepository(mapper);

        SeckillResultRetryRepository.RetryDecision decision = repository.recordFailure(
                "m1", "r1", "FAILED", "{}", 3L, "db down", 4, List.of(5000L));

        assertThat(decision.shouldRetry()).isTrue();
        assertThat(decision.retryCount()).isEqualTo(1);
        assertThat(decision.delayMillis()).isEqualTo(5000L);
        ArgumentCaptor<SeckillResultRetryEntity> retryCaptor = ArgumentCaptor.forClass(SeckillResultRetryEntity.class);
        verify(mapper).insert(retryCaptor.capture());
        assertThat(retryCaptor.getValue().getStatus()).isEqualTo(SeckillResultRetryRepository.STATUS_RETRYING);
        assertThat(retryCaptor.getValue().getRetryCount()).isEqualTo(1);
    }

    @Test
    void shouldMoveToDlqWhenRetryExceeded() {
        SeckillResultRetryRepository repository = new SeckillResultRetryRepository(mapper);
        SeckillResultRetryEntity existing = new SeckillResultRetryEntity();
        existing.setId(1L);
        existing.setRetryCount(4);
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        SeckillResultRetryRepository.RetryDecision decision = repository.recordFailure(
                "m2", "r1", "FAILED", "{}", 3L, "still down", 4, List.of(5000L));

        assertThat(decision.shouldRetry()).isFalse();
        assertThat(decision.retryCount()).isEqualTo(5);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<SeckillResultRetryEntity>> wrapperCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).update(isNull(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSet())
                .contains("status")
                .contains("next_retry_at");
    }
}
