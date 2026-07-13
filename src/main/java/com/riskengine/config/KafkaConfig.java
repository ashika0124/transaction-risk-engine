package com.riskengine.config;

import com.riskengine.dto.DecisionEvent;
import com.riskengine.dto.TransactionEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.converter.JsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.mapping.DefaultJackson2JavaTypeMapper;
import org.springframework.kafka.support.mapping.Jackson2JavaTypeMapper;

import java.util.Map;

@Configuration
public class KafkaConfig {

    public static final String TOPIC_INCOMING = "txn.incoming";
    public static final String TOPIC_DECISION = "txn.decision";

    @Bean
    public NewTopic incomingTopic() {
        return TopicBuilder.name(TOPIC_INCOMING)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic decisionTopic() {
        return TopicBuilder.name(TOPIC_DECISION)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public RecordMessageConverter jsonMessageConverter() {
        JsonMessageConverter converter = new JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.TYPE_ID);
        typeMapper.setIdClassMapping(Map.of(
                "transaction", TransactionEvent.class,
                "decision", DecisionEvent.class));
        converter.setTypeMapper(typeMapper);
        return converter;
    }
}