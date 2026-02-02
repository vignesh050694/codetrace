package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.dto.KafkaCallInfo;
import com.architecture.memory.orkestify.dto.KafkaTopicRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class KafkaResolutionService {

    /**
     * Build a registry of all Kafka producers and consumers across all analyzed services
     */
    public List<KafkaTopicRegistry> buildKafkaTopicRegistry(Map<String, List<KafkaTopicRegistry>> serviceTopics) {
        log.info("Building Kafka topic registry from {} services", serviceTopics.size());

        return serviceTopics.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /**
     * Resolve a Kafka producer call to its consumer(s)
     */
    public KafkaCallInfo resolveKafkaCall(
            KafkaCallInfo kafkaCall,
            List<KafkaTopicRegistry> topicRegistry) {

        String topic = kafkaCall.getTopic();
        String direction = kafkaCall.getDirection();

        if (topic == null || topic.isEmpty() || "<dynamic>".equals(topic) || "<default-topic>".equals(topic)) {
            kafkaCall.setResolved(false);
            kafkaCall.setResolutionReason("Topic is dynamic or default, cannot resolve");
            return kafkaCall;
        }

        // Only resolve producers (find their consumers)
        if (!"PRODUCER".equals(direction)) {
            kafkaCall.setResolved(false);
            kafkaCall.setResolutionReason("Only producers are resolved to consumers");
            return kafkaCall;
        }

        // Try to find matching consumer
        Optional<KafkaTopicRegistry> match = findConsumerForTopic(topic, topicRegistry);

        if (match.isPresent()) {
            KafkaTopicRegistry consumer = match.get();
            kafkaCall.setResolved(true);
            kafkaCall.setTargetService(consumer.getServiceName());
            kafkaCall.setTargetConsumerClass(consumer.getClassName());
            kafkaCall.setTargetConsumerMethod(consumer.getMethodName());
            kafkaCall.setResolutionReason("Found consumer: " + consumer.getServiceName());
            log.debug("Resolved Kafka producer for topic {} to consumer in {}", topic, consumer.getServiceName());
        } else {
            kafkaCall.setResolved(false);
            kafkaCall.setResolutionReason("No consumer found for topic: " + topic);
            log.debug("Could not resolve Kafka producer for topic: {}", topic);
        }

        return kafkaCall;
    }

    /**
     * Find consumer matching the given topic
     */
    private Optional<KafkaTopicRegistry> findConsumerForTopic(
            String topic,
            List<KafkaTopicRegistry> topicRegistry) {

        return topicRegistry.stream()
                .filter(registry -> "CONSUMER".equals(registry.getDirection()))
                .filter(registry -> matchesTopic(topic, registry.getTopic()))
                .findFirst();
    }

    /**
     * Check if topics match (exact match or pattern match)
     */
    private boolean matchesTopic(String producerTopic, String consumerTopic) {
        // Exact match
        if (producerTopic.equals(consumerTopic)) {
            return true;
        }

        // Handle dynamic topics
        if ("<dynamic>".equals(consumerTopic) || "<dynamic>".equals(producerTopic)) {
            return false;
        }

        // Could add pattern matching here if needed (e.g., wildcard topics)
        return false;
    }

    /**
     * Get all consumers for a specific topic
     */
    public List<KafkaTopicRegistry> getConsumersForTopic(String topic, List<KafkaTopicRegistry> topicRegistry) {
        return topicRegistry.stream()
                .filter(registry -> "CONSUMER".equals(registry.getDirection()))
                .filter(registry -> matchesTopic(topic, registry.getTopic()))
                .collect(Collectors.toList());
    }

    /**
     * Get all producers for a specific topic
     */
    public List<KafkaTopicRegistry> getProducersForTopic(String topic, List<KafkaTopicRegistry> topicRegistry) {
        return topicRegistry.stream()
                .filter(registry -> "PRODUCER".equals(registry.getDirection()))
                .filter(registry -> matchesTopic(topic, registry.getTopic()))
                .collect(Collectors.toList());
    }
}
