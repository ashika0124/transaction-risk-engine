package com.riskengine.repository;

import com.riskengine.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findByPublishedFalseAndCreatedAtBefore(LocalDateTime cutoff);

    List<OutboxEvent> findByPublishedFalse();
}