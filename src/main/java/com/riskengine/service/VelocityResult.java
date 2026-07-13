package com.riskengine.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VelocityResult {
    private int userCount;
    private int deviceCount;
    private int merchantCount;
    private boolean userFlagged;
    private boolean deviceFlagged;
    private boolean merchantFlagged;
    private int velocityScore;
}