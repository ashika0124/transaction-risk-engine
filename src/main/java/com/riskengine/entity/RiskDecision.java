package com.riskengine.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "risk_decisions", uniqueConstraints = {
        @UniqueConstraint(columnNames = "transaction_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, unique = true, length = 64)
    private String transactionId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "merchant_id", length = 64)
    private String merchantId;

    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "amount", nullable = false)
    private Double amount;

    @Column(name = "rule_score", nullable = false)
    private Integer ruleScore;

    @Column(name = "ai_risk_score")
    private Integer aiRiskScore;

    @Column(name = "ai_rationale", columnDefinition = "TEXT")
    private String aiRationale;

    @Column(name = "final_action", nullable = false, length = 20)
    private String finalAction;

    @Column(name = "decision_source", length = 30)
    private String decisionSource;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}