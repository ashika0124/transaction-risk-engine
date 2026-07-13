package com.riskengine.service;

import com.riskengine.dto.DecisionEvent;
import com.riskengine.dto.RiskAssessment;
import com.riskengine.dto.TransactionEvent;
import com.riskengine.entity.OutboxEvent;
import com.riskengine.entity.RiskDecision;
import com.riskengine.repository.OutboxEventRepository;
import com.riskengine.repository.RiskDecisionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Kafka consumer that processes transactions through the risk-scoring pipeline:
 * 1. Redis velocity check
 * 2. Rule engine evaluation
 * 3. Mistral AI (only if rule engine flags it)
 * 4. Persist decision + outbox event in same DB transaction
 */
@Service
@Slf4j
public class RiskScoringConsumer {

    private final VelocityCheckService velocityCheckService;
    private final RuleEngineService ruleEngineService;
    private final MistralRiskClient mistralRiskClient;
    private final RiskDecisionRepository riskDecisionRepository;
    private final OutboxEventRepository outboxEventRepository;

    public RiskScoringConsumer(VelocityCheckService velocityCheckService,
            RuleEngineService ruleEngineService,
            MistralRiskClient mistralRiskClient,
            RiskDecisionRepository riskDecisionRepository,
            OutboxEventRepository outboxEventRepository) {
        this.velocityCheckService = velocityCheckService;
        this.ruleEngineService = ruleEngineService;
        this.mistralRiskClient = mistralRiskClient;
        this.riskDecisionRepository = riskDecisionRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @KafkaListener(topics = "txn.incoming", groupId = "risk-engine-group", containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void processTransaction(TransactionEvent transaction, Acknowledgment ack) {
        log.info("Processing transaction: {}", transaction.getTransactionId());

        try {
            // Idempotency check — skip if already processed
            if (riskDecisionRepository.existsByTransactionId(transaction.getTransactionId())) {
                log.info("Transaction {} already processed, skipping", transaction.getTransactionId());
                ack.acknowledge();
                return;
            }

            // Step 1: Redis velocity check
            VelocityResult velocity = velocityCheckService.check(
                    transaction.getUserId(),
                    transaction.getDeviceId(),
                    transaction.getMerchantId());

            // Step 2: Rule engine evaluation
            RuleResult ruleResult = ruleEngineService.evaluate(transaction, velocity);

            // Step 3: AI assessment (only if rule engine flags it)
            RiskAssessment aiAssessment = null;
            String decisionSource;

            if (ruleResult.isNeedsAiReview()) {
                try {
                    aiAssessment = mistralRiskClient.assessRisk(transaction, velocity, ruleResult);
                    decisionSource = "AI";
                } catch (Exception e) {
                    // Circuit breaker fallback already handled inside MistralRiskClient
                    // but if it throws unexpectedly, use rule-only
                    log.error("Unexpected error in AI assessment for transaction: {}", transaction.getTransactionId(),
                            e);
                    aiAssessment = createRuleOnlyAssessment(ruleResult);
                    decisionSource = "RULE_ONLY_FALLBACK";
                }
            } else {
                // Low risk — no AI needed
                aiAssessment = RiskAssessment.builder()
                        .riskScore(ruleResult.getRuleScore())
                        .rationale("Low risk transaction. " + ruleResult.getReasons())
                        .recommendedAction("ALLOW")
                        .riskFactors("none")
                        .build();
                decisionSource = "RULE_ONLY";
            }

            // Determine final action
            String finalAction = determineFinalAction(ruleResult, aiAssessment);

            // Step 4: Persist decision + outbox in same DB transaction
            RiskDecision decision = RiskDecision.builder()
                    .transactionId(transaction.getTransactionId())
                    .userId(transaction.getUserId())
                    .merchantId(transaction.getMerchantId())
                    .deviceId(transaction.getDeviceId())
                    .amount(transaction.getAmount())
                    .ruleScore(ruleResult.getRuleScore())
                    .aiRiskScore(aiAssessment.getRiskScore())
                    .aiRationale(aiAssessment.getRationale())
                    .finalAction(finalAction)
                    .decisionSource(decisionSource)
                    .build();

            riskDecisionRepository.save(decision);

            // Create outbox event for downstream consumers
            DecisionEvent decisionEvent = DecisionEvent.builder()
                    .transactionId(transaction.getTransactionId())
                    .userId(transaction.getUserId())
                    .finalAction(finalAction)
                    .ruleScore(ruleResult.getRuleScore())
                    .aiRiskScore(aiAssessment.getRiskScore())
                    .aiRationale(aiAssessment.getRationale())
                    .decisionSource(decisionSource)
                    .timestamp(LocalDateTime.now())
                    .build();

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateId(transaction.getTransactionId())
                    .eventType("RISK_DECISION")
                    .payload(toJson(decisionEvent))
                    .topic("txn.decision")
                    .published(false)
                    .build();

            outboxEventRepository.save(outboxEvent);

            log.info("Transaction {} processed — action: {}, source: {}, rule_score: {}, ai_score: {}",
                    transaction.getTransactionId(), finalAction, decisionSource,
                    ruleResult.getRuleScore(),
                    aiAssessment != null ? aiAssessment.getRiskScore() : "N/A");

            ack.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process transaction: {}", transaction.getTransactionId(), e);
            // Don't acknowledge — let Kafka redeliver
            // The idempotency key will prevent duplicates on retry
        }
    }

    private String determineFinalAction(RuleResult ruleResult, RiskAssessment aiAssessment) {
        // If AI is involved, use AI's recommendation
        if (aiAssessment != null && aiAssessment.getRecommendedAction() != null) {
            return aiAssessment.getRecommendedAction();
        }
        // Fallback to rule-based action
        if (ruleResult.getRuleScore() >= 70)
            return "BLOCK";
        if (ruleResult.getRuleScore() >= 40)
            return "REVIEW";
        return "ALLOW";
    }

    private RiskAssessment createRuleOnlyAssessment(RuleResult ruleResult) {
        String action;
        if (ruleResult.getRuleScore() >= 70)
            action = "BLOCK";
        else if (ruleResult.getRuleScore() >= 40)
            action = "REVIEW";
        else
            action = "ALLOW";

        return RiskAssessment.builder()
                .riskScore(ruleResult.getRuleScore())
                .rationale("AI unavailable — decision based on rule engine only. Score: " + ruleResult.getRuleScore())
                .recommendedAction(action)
                .riskFactors("rule-only-fallback")
                .build();
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Failed to serialize object to JSON", e);
            return "{}";
        }
    }
}