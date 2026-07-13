package com.riskengine.service;

import com.riskengine.dto.RiskAssessment;
import com.riskengine.dto.TransactionEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Client for Mistral AI API that generates structured, explainable risk
 * reports.
 * Wrapped with Resilience4j circuit breaker for graceful degradation.
 */
@Service
@Slf4j
public class MistralRiskClient {

        private final WebClient webClient;
        private final String apiKey;
        private final String model;

        public MistralRiskClient(
                        @Value("${mistral.api.url}") String apiUrl,
                        @Value("${mistral.api.key}") String apiKey,
                        @Value("${mistral.api.model}") String model) {
                this.apiKey = apiKey;
                this.model = model;
                this.webClient = WebClient.builder()
                                .baseUrl(apiUrl)
                                .defaultHeader("Authorization", "Bearer " + apiKey)
                                .defaultHeader("Content-Type", "application/json")
                                .build();
        }

        /**
         * Call Mistral AI to assess transaction risk.
         * Only called for transactions the rule engine flagged as borderline.
         * Circuit breaker wraps this call — if AI is down, falls back to rule-only
         * decision.
         */
        @CircuitBreaker(name = "mistralAI", fallbackMethod = "ruleOnlyFallback")
        public RiskAssessment assessRisk(TransactionEvent transaction, VelocityResult velocity, RuleResult ruleResult) {
                String prompt = buildPrompt(transaction, velocity, ruleResult);

                Map<String, Object> requestBody = Map.of(
                                "model", model,
                                "messages", List.of(
                                                Map.of("role", "system", "content",
                                                                "You are a fraud detection AI. Analyze the transaction risk and respond ONLY with valid JSON. "
                                                                                +
                                                                                "Do not include any text outside the JSON object. "
                                                                                +
                                                                                "Use this exact schema: {\"risk_score\": 0-100, \"rationale\": \"string explaining risk factors\", "
                                                                                +
                                                                                "\"recommended_action\": \"ALLOW|REVIEW|BLOCK\", \"risk_factors\": \"comma-separated list\"}"),
                                                Map.of("role", "user", "content", prompt)),
                                "response_format", Map.of("type", "json_object"),
                                "temperature", 0.1,
                                "max_tokens", 500);

                try {
                        Map response = webClient.post()
                                        .bodyValue(requestBody)
                                        .retrieve()
                                        .bodyToMono(Map.class)
                                        .block();

                        if (response != null && response.containsKey("choices")) {
                                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                                if (!choices.isEmpty()) {
                                        Map<String, Object> message = (Map<String, Object>) choices.get(0)
                                                        .get("message");
                                        String content = (String) message.get("content");

                                        // Parse the JSON response into our RiskAssessment DTO
                                        try {
                                                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                                RiskAssessment assessment = mapper.readValue(content,
                                                                RiskAssessment.class);

                                                log.info("Mistral AI assessment - transaction: {}, risk_score: {}, action: {}",
                                                                transaction.getTransactionId(),
                                                                assessment.getRiskScore(),
                                                                assessment.getRecommendedAction());

                                                return assessment;
                                        } catch (Exception jsonEx) {
                                                log.error("Failed to parse Mistral AI response JSON for transaction: {}",
                                                                transaction.getTransactionId(), jsonEx);
                                                return ruleOnlyFallback(transaction, velocity, ruleResult,
                                                                new RuntimeException("Failed to parse AI response: "
                                                                                + jsonEx.getMessage()));
                                        }
                                }
                        }

                        log.warn("Unexpected Mistral API response format for transaction: {}",
                                        transaction.getTransactionId());
                        return ruleOnlyFallback(transaction, velocity, ruleResult,
                                        new RuntimeException("Unexpected response format"));

                } catch (Exception e) {
                        log.error("Mistral API call failed for transaction: {}", transaction.getTransactionId(), e);
                        throw e; // Let circuit breaker handle the exception
                }
        }

        /**
         * Fallback method when circuit breaker is open or AI call fails.
         * Returns a decision based purely on the rule engine's score.
         */
        public RiskAssessment ruleOnlyFallback(TransactionEvent transaction, VelocityResult velocity,
                        RuleResult ruleResult, Exception ex) {
                log.warn("AI unavailable for transaction: {} — using rule-only fallback. Reason: {}",
                                transaction.getTransactionId(), ex.getMessage());

                int ruleScore = ruleResult.getRuleScore();
                String action;
                if (ruleScore >= 70) {
                        action = "BLOCK";
                } else if (ruleScore >= 40) {
                        action = "REVIEW";
                } else {
                        action = "ALLOW";
                }

                return RiskAssessment.builder()
                                .riskScore(ruleScore)
                                .rationale("AI unavailable — decision based on rule engine only. Rule score: "
                                                + ruleScore
                                                + ". Reasons: " + ruleResult.getReasons())
                                .recommendedAction(action)
                                .riskFactors("rule-only-fallback")
                                .build();
        }

        private String buildPrompt(TransactionEvent transaction, VelocityResult velocity, RuleResult ruleResult) {
                return String.format(
                                "Transaction ID: %s\n" +
                                                "User ID: %s\n" +
                                                "Merchant ID: %s\n" +
                                                "Device ID: %s\n" +
                                                "Amount: $%.2f\n" +
                                                "Timestamp: %s\n\n" +
                                                "Velocity Check Results:\n" +
                                                "- User transactions in last 2 minutes: %d (threshold: 5, flagged: %b)\n"
                                                +
                                                "- Device transactions in last 2 minutes: %d (threshold: 10, flagged: %b)\n"
                                                +
                                                "- Merchant transactions in last 2 minutes: %d (threshold: 15, flagged: %b)\n\n"
                                                +
                                                "Rule Engine Results:\n" +
                                                "- Rule score: %d/100\n" +
                                                "- Rule reasons: %s\n\n" +
                                                "Assess the risk of this transaction. Consider the velocity patterns, amount, and timing. "
                                                +
                                                "Provide a risk score (0-100), a detailed rationale, a recommended action (ALLOW/REVIEW/BLOCK), "
                                                +
                                                "and a comma-separated list of risk factors.",
                                transaction.getTransactionId(),
                                transaction.getUserId(),
                                transaction.getMerchantId(),
                                transaction.getDeviceId(),
                                transaction.getAmount(),
                                transaction.getTimestamp() != null ? transaction.getTimestamp().toString() : "N/A",
                                velocity.getUserCount(), velocity.isUserFlagged(),
                                velocity.getDeviceCount(), velocity.isDeviceFlagged(),
                                velocity.getMerchantCount(), velocity.isMerchantFlagged(),
                                ruleResult.getRuleScore(),
                                ruleResult.getReasons());
        }
}