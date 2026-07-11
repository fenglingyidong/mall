package com.mall.seckill.service.impl;

import com.mall.seckill.config.SeckillProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillOrderOutboxCoordinatorTest {

    @Mock
    private SeckillOrderOutboxFromChangeLogService service;

    @Test
    void signalShouldCoalesceSameShardBeforeWorkerStarts() {
        SeckillProperties properties = new SeckillProperties();
        RecordingTaskExecutor executor = new RecordingTaskExecutor();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SeckillOrderOutboxCoordinator coordinator = new SeckillOrderOutboxCoordinator(service, executor, properties, registry);
        when(service.drainShard(3L, 5)).thenReturn(0);

        coordinator.signal(3L);
        coordinator.signal(3L);

        assertThat(executor.submitted()).hasSize(1);
        assertThat(registry.get("seckill.outbox.signal.accepted").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("seckill.outbox.signal.coalesced").counter().count()).isEqualTo(1.0);

        executor.runNext();
        verify(service).drainShard(3L, 5);
    }

    @Test
    void signalDuringRunningShouldDrainAgainWithoutSecondSubmission() {
        SeckillProperties properties = new SeckillProperties();
        RecordingTaskExecutor executor = new RecordingTaskExecutor();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SeckillOrderOutboxCoordinator coordinator = new SeckillOrderOutboxCoordinator(service, executor, properties, registry);
        AtomicInteger count = new AtomicInteger();
        when(service.drainShard(3L, 5)).thenAnswer(invocation -> {
            if (count.getAndIncrement() == 0) {
                coordinator.signal(3L);
                return 1;
            }
            return 0;
        });

        coordinator.signal(3L);
        executor.runNext();

        assertThat(count.get()).isEqualTo(2);
        assertThat(executor.submissionCount()).isEqualTo(1);
    }

    @Test
    void rejectedSubmissionShouldIncrementRejectedCounter() {
        SeckillProperties properties = new SeckillProperties();
        RecordingTaskExecutor executor = new RecordingTaskExecutor();
        executor.rejectNext();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SeckillOrderOutboxCoordinator coordinator = new SeckillOrderOutboxCoordinator(service, executor, properties, registry);

        coordinator.signal(3L);

        assertThat(registry.get("seckill.outbox.signal.rejected").counter().count()).isEqualTo(1.0);
        assertThat(executor.submitted()).isEmpty();
    }

    @Test
    void recoverConfiguredShardsShouldResetStaleAndSignalEachShard() {
        SeckillProperties properties = new SeckillProperties();
        properties.getBucket().getRouting().setBucketShardKeys(List.of(1L, 3L));
        RecordingTaskExecutor executor = new RecordingTaskExecutor();
        SeckillOrderOutboxCoordinator coordinator = new SeckillOrderOutboxCoordinator(service, executor, properties, new SimpleMeterRegistry());

        coordinator.recoverConfiguredShards();

        verify(service).resetStaleOutboxing(1L);
        verify(service).resetStaleOutboxing(3L);
        assertThat(executor.submitted()).hasSize(2);
    }

    private static final class RecordingTaskExecutor implements TaskExecutor {
        private final Queue<Runnable> submitted = new ArrayDeque<>();
        private boolean rejectNext;
        private int submissionCount;

        @Override
        public void execute(Runnable task) {
            if (rejectNext) {
                rejectNext = false;
                throw new TaskRejectedException("reject");
            }
            submissionCount++;
            submitted.add(task);
        }

        void rejectNext() {
            this.rejectNext = true;
        }

        List<Runnable> submitted() {
            return List.copyOf(submitted);
        }

        int submissionCount() {
            return submissionCount;
        }

        void runNext() {
            Runnable task = submitted.poll();
            if (task != null) {
                task.run();
            }
        }
    }
}
