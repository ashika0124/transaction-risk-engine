package com.riskengine.service;

import com.riskengine.dto.RiskAssessment;
import com.riskengine.dto.TransactionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MistralRiskClientFallbackTest {

    private MistralRiskClient mistralRiskClient;

    @BeforeEach
    void setUp() throws Exception {
        // Use reflection to create instance with placeholder values
        var constructor = MistralRiskClient.class.getDeclaredConstructor(
                String.class, String.class, String.class);
        constructor.setAccessible(true);
        mistralRiskClient = constructor.newInstance(
                "https://api.mistral.ai/v1/chat/completions",
                "test-key",
                "mistral-small-latest");
    }

    @Test
    void fallbackShouldReturnBlockForHighScore() {
        TransactionEvent txn = TransactionEvent.builder()
                .transactionId("txn-001")
                .userId("user-1")
                .merchantId("merchant-1")
                .deviceId("device-1")
                .amount(50000.0)
                .build();

        VelocityResult velocity = VelocityResult.builder().build();
        RuleResult ruleResult = RuleResult.builder()
                .ruleScore(85)
                .reasons("High amount; High velocity")
                .needsAiReview(true)
                .build();

        RiskAssessment assessment = mistralRiskClient.ruleOnlyFallback(
                txn, velocity, ruleResult, new RuntimeException("AI service down"));

        assertNotNull(assessment);
        assertEquals(85, assessment.getRiskScore());
        assertEquals("BLOCK", assessment.getRecommendedAction());
        assertTrue(assessment.getRationale().contains("AI unavailable"));
        assertTrue(assessment.getRationale().contains("rule engine only"));
    }

    @Test
    void fallbackShouldReturnReviewForMediumScore() {
        TransactionEvent txn = TransactionEvent.builder()
                .transactionId("txn-002")
                .userId("user-1")
                .merchantId("merchant-1")
                .deviceId("device-1")
                .amount(5000.0)
                .build();

        VelocityResult velocity = VelocityResult.builder().build();
        RuleResult ruleResult = RuleResult.builder()
                .ruleScore(55)
                .reasons("High device velocity")
                .needsAiReview(true)
                .build();

        RiskAssessment assessment = mistralRiskClient.ruleOnlyFallback(
                txn, velocity, ruleResult, new RuntimeException("Circuit breaker open"));

        assertNotNull(assessment);
        assertEquals(55, assessment.getRiskScore());
        assertEquals("REVIEW", assessment.getRecommendedAction());
        assertTrue(assessment.getRationale().contains("AI unavailable"));
    }

    @Test
    void fallbackShouldReturnAllowForLowScore() {
        TransactionEvent txn = TransactionEvent.builder()
                .transactionId("txn-003")
                .userId("user-1")
                .merchantId("merchant-1")
                .deviceId("device-1")
                .amount(50.0)
                .build();

        VelocityResult velocity = VelocityResult.builder().build();
        RuleResult ruleResult = RuleResult.builder()
                .ruleScore(15)
                .reasons("No rule triggers")
                .needsAiReview(false)
                .build();

        RiskAssessment assessment = mistralRiskClient.ruleOnlyFallback(
                txn, velocity, ruleResult, new RuntimeException("Timeout"));

        assertNotNull(assessment);
        assertEquals(15, assessment.getRiskScore());
        assertEquals("ALLOW", assessment.getRecommendedAction());
        assertEquals("rule-only-fallback", assessment.getRiskFactors());
    }
}