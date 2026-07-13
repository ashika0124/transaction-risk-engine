package com.riskengine.service;

import com.riskengine.dto.TransactionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Deterministic rule engine that evaluates transaction risk based on
 * configurable thresholds. This is the fast, cheap path — no AI involved.
 */
@Service
@Slf4j
public class RuleEngineService {

    @Value("${risk.engine.rule.high-amount-threshold:10000}")
    private double highAmountThreshold;

    @Value("${risk.engine.rule.unusual-time-enabled:true}")
    private boolean unusualTimeEnabled;

    @Value("${risk.engine.rule.unusual-time-start-hour:0}")
    private int unusualTimeStartHour;

    @Value("${risk.engine.rule.unusual-time-end-hour:6}")
    private int unusualTimeEndHour;

    /**
     * Evaluate deterministic rules and return a rule score (0-100).
     * Only transactions scoring above the threshold will proceed to AI evaluation.
     */
    public RuleResult evaluate(TransactionEvent transaction, VelocityResult velocity) {
        int score = 0;
        StringBuilder reasons = new StringBuilder();

        // 1. Velocity check (from Redis)
        if (velocity.isUserFlagged()) {
            score += 30;
            reasons.append("High user velocity (").append(velocity.getUserCount()).append(" txns in window); ");
        }
        if (velocity.isDeviceFlagged()) {
            score += 20;
            reasons.append("High device velocity (").append(velocity.getDeviceCount()).append(" txns in window); ");
        }
        if (velocity.isMerchantFlagged()) {
            score += 15;
            reasons.append("High merchant velocity (").append(velocity.getMerchantCount()).append(" txns in window); ");
        }

        // 2. High amount check
        if (transaction.getAmount() > highAmountThreshold) {
            score += 25;
            reasons.append(String.format("High amount (%.2f exceeds threshold %.2f); ",
                    transaction.getAmount(), highAmountThreshold));
        }

        // 3. Unusual time check (e.g., transactions between midnight and 6 AM)
        if (unusualTimeEnabled) {
            LocalDateTime txnTime = transaction.getTimestamp() != null
                    ? transaction.getTimestamp()
                    : LocalDateTime.now();
            LocalTime time = txnTime.toLocalTime();
            if (time.getHour() >= unusualTimeStartHour && time.getHour() < unusualTimeEndHour) {
                score += 10;
                reasons.append("Unusual transaction time (").append(time).append("); ");
            }
        }

        // Cap at 100
        score = Math.min(score, 100);

        log.debug("Rule evaluation - transaction: {}, score: {}, reasons: {}",
                transaction.getTransactionId(), score, reasons);

        return RuleResult.builder()
                .ruleScore(score)
                .reasons(reasons.length() > 0 ? reasons.substring(0, reasons.length() - 2) : "No rule triggers")
                .needsAiReview(score >= 30) // Threshold to escalate to AI
                .build();
    }

    public boolean needsAiReview(int ruleScore) {
        return ruleScore >= 30;
    }
}