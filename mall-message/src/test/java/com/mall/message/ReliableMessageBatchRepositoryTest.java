package com.mall.message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReliableMessageBatchRepositoryTest {

    @Mock
    private MqMessageMapper messageMapper;

    @Test
    void saveIgnoreDuplicatesShouldInsertNewMessagesInBatch() {
        ReliableMessageRepository repository = new ReliableMessageRepository(messageMapper);
        ReliableMessage first = ReliableMessage.of("mall.exchange", MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, "r1", "{}", 1L, null);
        ReliableMessage second = ReliableMessage.of("mall.exchange", MessageNames.SECKILL_ORDER_CREATE_ROUTING_KEY, "r2", "{}", 1L, null);

        repository.saveIgnoreDuplicates(List.of(first, second));

        ArgumentCaptor<List<MqMessageEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageMapper).insertIgnoreBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).allSatisfy(entity -> {
            assertThat(entity.getStatus()).isEqualTo("NEW");
            assertThat(entity.getBucketShardKey()).isEqualTo(1L);
            assertThat(entity.getCreatedAt()).isNotNull();
            assertThat(entity.getUpdatedAt()).isNotNull();
        });
    }

    @Test
    void saveIgnoreDuplicatesShouldSkipNullOrEmptyBatch() {
        ReliableMessageRepository repository = new ReliableMessageRepository(messageMapper);

        repository.saveIgnoreDuplicates(null);
        repository.saveIgnoreDuplicates(List.of());

        verifyNoInteractions(messageMapper);
    }
}
