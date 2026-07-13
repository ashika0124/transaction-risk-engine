package com.riskengine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionEvent {
    private String transactionId;
    private String userId;
    private String finalAction;
    private Integer ruleScore;
    private Integer aiRiskScore;
    private String aiRationale;
    private String decisionSource;
    private LocalDateTime timestamp;
}