package com.riskengine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessment {

    @JsonProperty("risk_score")
    private Integer riskScore;

    @JsonProperty("rationale")
    private String rationale;

    @JsonProperty("recommended_action")
    private String recommendedAction;

    @JsonProperty("risk_factors")
    private String riskFactors;
}