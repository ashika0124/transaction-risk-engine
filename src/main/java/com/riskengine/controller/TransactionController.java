package com.riskengine.controller;

import com.riskengine.dto.TransactionEvent;
import com.riskengine.dto.TransactionRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * REST endpoint for transaction ingestion.
 * Publishes transactions to Kafka for async processing instead of blocking.
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class TransactionController {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String incomingTopic;

    public TransactionController(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${spring.kafka.producer.properties.spring.json.type.mapping}") String typeMapping) {
        this.kafkaTemplate = kafkaTemplate;
        this.incomingTopic = "txn.incoming";
    }

    @PostMapping("/transactions")
    public ResponseEntity<Map<String, Object>> ingestTransaction(@Valid @RequestBody TransactionRequest request) {
        log.info("Received transaction: {}", request.getTransactionId());

        TransactionEvent event = TransactionEvent.builder()
                .transactionId(request.getTransactionId())
                .userId(request.getUserId())
                .merchantId(request.getMerchantId())
                .deviceId(request.getDeviceId())
                .amount(request.getAmount())
                .timestamp(request.getTimestamp() != null ? request.getTimestamp() : LocalDateTime.now())
                .build();

        kafkaTemplate.send(incomingTopic, event.getTransactionId(), event);

        log.debug("Published transaction {} to topic {}", event.getTransactionId(), incomingTopic);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of(
                        "status", "accepted",
                        "transactionId", event.getTransactionId(),
                        "message", "Transaction received and queued for processing"));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "transaction-risk-engine"));
    }
}