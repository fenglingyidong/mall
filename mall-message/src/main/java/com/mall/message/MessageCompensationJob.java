package com.mall.message;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "mall.message.compensation", name = "enabled", havingValue = "true")
public class MessageCompensationJob {

    private final ReliableMessageRepository repository;
    private final ReliableMessagePublisher publisher;

    public MessageCompensationJob(ReliableMessageRepository repository, ReliableMessagePublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${mall.message.compensation.fixed-delay:60000}")
    public void compensate() {
        repository.findNeedCompensation(50).forEach(publisher::resend);
    }
}
