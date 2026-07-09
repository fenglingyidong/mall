package com.mall.message;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReliableMessageRepositoryTest {

    @Mock
    private MqMessageMapper messageMapper;

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

    private Wrapper<MqMessageEntity> capturedUpdateWrapper() {
        ArgumentCaptor<Wrapper<MqMessageEntity>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(messageMapper).update(isNull(), wrapperCaptor.capture());
        return wrapperCaptor.getValue();
    }
}
