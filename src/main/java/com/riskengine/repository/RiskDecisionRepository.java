package com.riskengine.repository;

import com.riskengine.entity.RiskDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RiskDecisionRepository extends JpaRepository<RiskDecision, Long> {
    Optional<RiskDecision> findByTransactionId(String transactionId);

    boolean existsByTransactionId(String transactionId);
}