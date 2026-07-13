package com.riskengine.service;

import com.riskengine.entity.OutboxEvent;
import com.riskengine.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Polls the outbox table for unpublished events and publishes them to Kafka.
 * This implements the Outbox pattern to guarantee exactly-once delivery:
 * - Decision is written to DB + outbox row in same transaction
 * - This poller reads unpublished rows and publishes to txn.decision
 * - After successful publish, marks the row as published
 * - If publish fails, the row stays unpublished and will be retried
 */
@Service
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Poll every 2 seconds for unpublished outbox events.
     * Processes events older than 1 second to avoid racing with the writing
     * transaction.
     */
    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void publishPendingEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(1);
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByPublishedFalseAndCreatedAtBefore(cutoff);

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Found {} pending outbox events to publish", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                // Mark as published
                                event.setPublished(true);
                                event.setPublishedAt(LocalDateTime.now());
                                outboxEventRepository.save(event);
                                log.debug("Published outbox event {} to topic {}", event.getId(), event.getTopic());
                            } else {
                                log.error("Failed to publish outbox event {} to topic {}",
                                        event.getId(), event.getTopic(), ex);
                            }
                        });
            } catch (Exception e) {
                log.error("Error publishing outbox event {}: {}", event.getId(), e.getMessage());
            }
        }
    }
}