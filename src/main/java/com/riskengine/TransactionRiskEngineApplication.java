package com.riskengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TransactionRiskEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionRiskEngineApplication.class, args);
    }
}