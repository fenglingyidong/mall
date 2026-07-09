package com.mall.message;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReliableMessageRepositoryTest {

    @Mock
    private MqMessageMapper messageMapper;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MqMessageEntity.class);
    }

    @Test
    void markSentShouldOnlyUpdateDispatchingMessages() {
        ReliableMessageRepository repository = new ReliableMessageRepository(messageMapper);

        repository.markSent("m1");

        Wrapper<MqMessageEntity> wrapper = capturedUpdateWrapper();
        assertThat(wrapper.getSqlSegment())
                .contains("message_id")
                .contains("status")
                .contains("IN");
    }

    @Test
    void markSentByShardShouldOnlyUpdateDispatchingMessages() {
        ReliableMessageRepository repository = new ReliableMessageRepository(messageMapper);

        repository.markSent("m1", 3L);

        Wrapper<MqMessageEntity> wrapper = capturedUpdateWrapper();
        assertThat(wrapper.getSqlSegment())
                .contains("message_id")
                .contains("bucket_shard_key")
                .contains("status")
                .contains("IN");
    }

    @Test
    void markFailedShouldOnlyUpdateDispatchingMessages() {
        ReliableMessageRepository repository = new ReliableMessageRepository(messageMapper);

        repository.markFailed("m1", "failed");

        Wrapper<MqMessageEntity> wrapper = capturedUpdateWrapper();
        assertThat(wrapper.getSqlSegment())
                .contains("message_id")
                .contains("status")
                .contains("IN");
    }

    @Test
    void markConsumedByShardShouldRouteWithBucketShardKey() {
        ReliableMessageRepository repository = new ReliableMessageRepository(messageMapper);

        repository.markConsumed("m1", 3L);

        Wrapper<MqMessageEntity> wrapper = capturedUpdateWrapper();
        assertThat(wrapper.getSqlSegment())
                .contains("message_id")
                .contains("bucket_shard_key");
    }

    @Test
    void existsByBusinessKeyAndRoutingKeyShouldFilterBusinessRoutingAndShard() {
        ReliableMessageRepository repository = new ReliableMessageRepository(messageMapper);
        when(messageMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);

        boolean exists = repository.existsByBusinessKeyAndRoutingKey("req-1", MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, 3L);

        assertThat(exists).isTrue();
        Wrapper<MqMessageEntity> wrapper = capturedSelectCountWrapper();
        assertThat(wrapper.getSqlSegment())
                .contains("business_key")
                .contains("routing_key")
                .contains("bucket_shard_key");
    }

    @Test
    void existsByBusinessKeyAndRoutingKeyShouldFilterNullShardExactly() {
        ReliableMessageRepository repository = new ReliableMessageRepository(messageMapper);
        when(messageMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);

        boolean exists = repository.existsByBusinessKeyAndRoutingKey("req-1", MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, null);

        assertThat(exists).isTrue();
        Wrapper<MqMessageEntity> wrapper = capturedSelectCountWrapper();
        assertThat(wrapper.getSqlSegment())
                .contains("business_key")
                .contains("routing_key")
                .contains("bucket_shard_key")
                .contains("IS NULL");
    }

    private Wrapper<MqMessageEntity> capturedUpdateWrapper() {
        ArgumentCaptor<Wrapper<MqMessageEntity>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(messageMapper).update(isNull(), wrapperCaptor.capture());
        return wrapperCaptor.getValue();
    }

    private Wrapper<MqMessageEntity> capturedSelectCountWrapper() {
        ArgumentCaptor<Wrapper<MqMessageEntity>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(messageMapper).selectCount(wrapperCaptor.capture());
        return wrapperCaptor.getValue();
    }
}
