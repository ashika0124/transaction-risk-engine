package com.riskengine.service;

import com.riskengine.dto.TransactionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RuleEngineServiceTest {

    private RuleEngineService ruleEngineService;

    @BeforeEach
    void setUp() {
        ruleEngineService = new RuleEngineService();
        ReflectionTestUtils.setField(ruleEngineService, "highAmountThreshold", 10000.0);
        ReflectionTestUtils.setField(ruleEngineService, "unusualTimeEnabled", true);
        ReflectionTestUtils.setField(ruleEngineService, "unusualTimeStartHour", 0);
        ReflectionTestUtils.setField(ruleEngineService, "unusualTimeEndHour", 6);
    }

    @Test
    void shouldReturnLowScoreForNormalTransaction() {
        TransactionEvent txn = TransactionEvent.builder()
                .transactionId("txn-001")
                .userId("user-1")
                .merchantId("merchant-1")
                .deviceId("device-1")
                .amount(50.0)
                .timestamp(LocalDateTime.of(2026, 7, 12, 14, 0))
                .build();

        VelocityResult velocity = VelocityResult.builder()
                .userCount(1)
                .deviceCount(1)
                .merchantCount(1)
                .userFlagged(false)
                .deviceFlagged(false)
                .merchantFlagged(false)
                .velocityScore(0)
                .build();

        RuleResult result = ruleEngineService.evaluate(txn, velocity);

        assertEquals(0, result.getRuleScore());
        assertFalse(result.isNeedsAiReview());
        assertEquals("No rule triggers", result.getReasons());
    }

    @Test
    void shouldFlagHighUserVelocity() {
        TransactionEvent txn = TransactionEvent.builder()
                .transactionId("txn-002")
                .userId("user-1")
                .merchantId("merchant-1")
                .deviceId("device-1")
                .amount(50.0)
                .timestamp(LocalDateTime.of(2026, 7, 12, 14, 0))
                .build();

        VelocityResult velocity = VelocityResult.builder()
                .userCount(8)
                .deviceCount(1)
                .merchantCount(1)
                .userFlagged(true)
                .deviceFlagged(false)
                .merchantFlagged(false)
                .velocityScore(40)
                .build();

        RuleResult result = ruleEngineService.evaluate(txn, velocity);

        assertTrue(result.getRuleScore() >= 30);
        assertTrue(result.isNeedsAiReview());
        assertTrue(result.getReasons().contains("High user velocity"));
    }

    @Test
    void shouldFlagHighAmountTransaction() {
        TransactionEvent txn = TransactionEvent.builder()
                .transactionId("txn-003")
                .userId("user-1")
                .merchantId("merchant-1")
                .deviceId("device-1")
                .amount(25000.0)
                .timestamp(LocalDateTime.of(2026, 7, 12, 14, 0))
                .build();

        VelocityResult velocity = VelocityResult.builder()
                .userCount(1)
                .deviceCount(1)
                .merchantCount(1)
                .userFlagged(false)
                .deviceFlagged(false)
                .merchantFlagged(false)
                .velocityScore(0)
                .build();

        RuleResult result = ruleEngineService.evaluate(txn, velocity);

        assertTrue(result.getRuleScore() >= 25);
        assertTrue(result.getReasons().contains("High amount"));
    }

    @Test
    void shouldFlagUnusualTimeTransaction() {
        TransactionEvent txn = TransactionEvent.builder()
                .transactionId("txn-004")
                .userId("user-1")
                .merchantId("merchant-1")
                .deviceId("device-1")
                .amount(50.0)
                .timestamp(LocalDateTime.of(2026, 7, 12, 3, 0)) // 3 AM
                .build();

        VelocityResult velocity = VelocityResult.builder()
                .userCount(1)
                .deviceCount(1)
                .merchantCount(1)
                .userFlagged(false)
                .deviceFlagged(false)
                .merchantFlagged(false)
                .velocityScore(0)
                .build();

        RuleResult result = ruleEngineService.evaluate(txn, velocity);

        assertTrue(result.getRuleScore() >= 10);
        assertTrue(result.getReasons().contains("Unusual transaction time"));
    }

    @Test
    void shouldCapScoreAt100() {
        TransactionEvent txn = TransactionEvent.builder()
                .transactionId("txn-005")
                .userId("user-1")
                .merchantId("merchant-1")
                .deviceId("device-1")
                .amount(50000.0)
                .timestamp(LocalDateTime.of(2026, 7, 12, 3, 0))
                .build();

        VelocityResult velocity = VelocityResult.builder()
                .userCount(20)
                .deviceCount(20)
                .merchantCount(20)
                .userFlagged(true)
                .deviceFlagged(true)
                .merchantFlagged(true)
                .velocityScore(100)
                .build();

        RuleResult result = ruleEngineService.evaluate(txn, velocity);

        assertEquals(100, result.getRuleScore());
        assertTrue(result.isNeedsAiReview());
    }

    @Test
    void shouldCombineMultipleRiskFactors() {
        TransactionEvent txn = TransactionEvent.builder()
                .transactionId("txn-006")
                .userId("user-1")
                .merchantId("merchant-1")
                .deviceId("device-1")
                .amount(25000.0)
                .timestamp(LocalDateTime.of(2026, 7, 12, 3, 0))
                .build();

        VelocityResult velocity = VelocityResult.builder()
                .userCount(8)
                .deviceCount(12)
                .merchantCount(1)
                .userFlagged(true)
                .deviceFlagged(true)
                .merchantFlagged(false)
                .velocityScore(70)
                .build();

        RuleResult result = ruleEngineService.evaluate(txn, velocity);

        assertTrue(result.getRuleScore() >= 75); // 30 + 20 + 25 + 10 = 85
        assertTrue(result.isNeedsAiReview());
        assertTrue(result.getReasons().contains("High user velocity"));
        assertTrue(result.getReasons().contains("High device velocity"));
        assertTrue(result.getReasons().contains("High amount"));
        assertTrue(result.getReasons().contains("Unusual transaction time"));
    }
}