package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.dto.*;
import com.architecture.memory.orkestify.model.CodeAnalysisResult;
import com.architecture.memory.orkestify.repository.CodeAnalysisResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to resolve Kafka producer-consumer relationships across services
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaProducerConsumerResolver {

    private final CodeAnalysisResultRepository analysisResultRepository;

    /**
     * Resolve producers for all Kafka listeners in the given project
     */
    public void resolveKafkaProducers(String userId) {
        log.info("Starting Kafka producer-consumer resolution for user: {}", userId);

        // Get all analysis results for this user
        List<CodeAnalysisResult> allResults = analysisResultRepository.findByUserId(userId);

        if (allResults.isEmpty()) {
            log.info("No analysis results found for user: {}", userId);
            return;
        }

        // Build Kafka topic registry (topic -> list of producers)
        Map<String, List<KafkaProducerInfo>> topicProducers = buildTopicProducerRegistry(allResults);

        log.info("Built Kafka topic registry with {} topics", topicProducers.size());
        topicProducers.forEach((topic, producers) ->
            log.debug("  Topic '{}' has {} producer(s)", topic, producers.size())
        );

        // Resolve producers for each consumer
        int totalConsumers = 0;
        int resolvedConsumers = 0;

        for (CodeAnalysisResult result : allResults) {
            if (result.getKafkaListeners() == null || result.getKafkaListeners().isEmpty()) {
                continue;
            }

            boolean updated = false;

            for (KafkaListenerInfo listenerInfo : result.getKafkaListeners()) {
                for (KafkaListenerMethod listener : listenerInfo.getListeners()) {

                    // Resolve producers for kafkaCalls in this listener
                    if (listener.getKafkaCalls() != null) {
                        for (KafkaCallInfo kafkaCall : listener.getKafkaCalls()) {
                            if ("CONSUMER".equals(kafkaCall.getDirection())) {
                                totalConsumers++;
                                String topic = kafkaCall.getTopic();

                                if (topic == null || topic.startsWith("<") || topic.contains("$")) {
                                    log.debug("Skipping unresolved topic: {}", topic);
                                    continue;
                                }

                                // Find producers for this topic
                                List<KafkaProducerInfo> producers = topicProducers.getOrDefault(topic, new ArrayList<>());

                                if (!producers.isEmpty()) {
                                    kafkaCall.setProducers(producers);
                                    kafkaCall.setResolved(true);
                                    resolvedConsumers++;
                                    updated = true;

                                    log.info("✅ Resolved consumer {}.{}() consuming '{}' -> {} producer(s)",
                                            listenerInfo.getClassName(), listener.getMethodName(), topic, producers.size());

                                    producers.forEach(p ->
                                        log.debug("    Producer: {}.{} in {}",
                                                p.getClassName(), p.getMethodName(), p.getServiceName())
                                    );
                                } else {
                                    kafkaCall.setProducers(new ArrayList<>());
                                    kafkaCall.setResolved(false);

                                    log.warn("❌ No producer found for consumer {}.{}() consuming topic '{}'",
                                            listenerInfo.getClassName(), listener.getMethodName(), topic);
                                }
                            }
                        }
                    }
                }
            }

            // Save updated result
            if (updated) {
                analysisResultRepository.save(result);
                log.debug("Updated analysis result for {}", result.getAppKey());
            }
        }

        log.info("Kafka producer-consumer resolution complete: {}/{} consumers resolved",
                resolvedConsumers, totalConsumers);
    }

    /**
     * Build a registry of all Kafka producers by topic
     */
    private Map<String, List<KafkaProducerInfo>> buildTopicProducerRegistry(List<CodeAnalysisResult> allResults) {
        Map<String, List<KafkaProducerInfo>> topicProducers = new HashMap<>();

        for (CodeAnalysisResult result : allResults) {
            String serviceName = extractServiceName(result);

            // Check controllers for Kafka producers
            if (result.getControllers() != null) {
                for (ControllerInfo controller : result.getControllers()) {
                    for (EndpointInfo endpoint : controller.getEndpoints()) {
                        extractProducersFromCalls(endpoint.getCalls(), topicProducers, result, serviceName,
                                controller.getClassName(), controller.getPackageName());

                        if (endpoint.getKafkaCalls() != null) {
                            for (KafkaCallInfo kafkaCall : endpoint.getKafkaCalls()) {
                                if ("PRODUCER".equals(kafkaCall.getDirection())) {
                                    addProducer(topicProducers, kafkaCall.getTopic(), result, serviceName,
                                            controller.getClassName(), endpoint.getHandlerMethod(),
                                            controller.getPackageName(), kafkaCall.getLine());
                                }
                            }
                        }
                    }
                }
            }

            // Check services for Kafka producers
            if (result.getServices() != null) {
                for (ServiceInfo service : result.getServices()) {
                    for (MethodInfo method : service.getMethods()) {
                        extractProducersFromCalls(method.getCalls(), topicProducers, result, serviceName,
                                service.getClassName(), service.getPackageName());

                        if (method.getKafkaCalls() != null) {
                            for (KafkaCallInfo kafkaCall : method.getKafkaCalls()) {
                                if ("PRODUCER".equals(kafkaCall.getDirection())) {
                                    addProducer(topicProducers, kafkaCall.getTopic(), result, serviceName,
                                            service.getClassName(), method.getMethodName(),
                                            service.getPackageName(), kafkaCall.getLine());
                                }
                            }
                        }
                    }
                }
            }

            // Check Kafka listeners for Kafka producers (listeners can also produce)
            if (result.getKafkaListeners() != null) {
                for (KafkaListenerInfo listenerInfo : result.getKafkaListeners()) {
                    for (KafkaListenerMethod listener : listenerInfo.getListeners()) {
                        extractProducersFromCalls(listener.getCalls(), topicProducers, result, serviceName,
                                listenerInfo.getClassName(), listenerInfo.getPackageName());

                        if (listener.getKafkaCalls() != null) {
                            for (KafkaCallInfo kafkaCall : listener.getKafkaCalls()) {
                                if ("PRODUCER".equals(kafkaCall.getDirection())) {
                                    addProducer(topicProducers, kafkaCall.getTopic(), result, serviceName,
                                            listenerInfo.getClassName(), listener.getMethodName(),
                                            listenerInfo.getPackageName(), kafkaCall.getLine());
                                }
                            }
                        }
                    }
                }
            }
        }

        return topicProducers;
    }

    /**
     * Recursively extract producers from method calls
     */
    private void extractProducersFromCalls(List<MethodCall> calls, Map<String, List<KafkaProducerInfo>> topicProducers,
                                            CodeAnalysisResult result, String serviceName,
                                            String className, String packageName) {
        if (calls == null) return;

        for (MethodCall call : calls) {
            // Check Kafka calls in this method call
            if (call.getKafkaCalls() != null) {
                for (KafkaCallInfo kafkaCall : call.getKafkaCalls()) {
                    if ("PRODUCER".equals(kafkaCall.getDirection())) {
                        addProducer(topicProducers, kafkaCall.getTopic(), result, serviceName,
                                className, call.getHandlerMethod(), packageName, kafkaCall.getLine());
                    }
                }
            }

            // Recurse into nested calls
            extractProducersFromCalls(call.getCalls(), topicProducers, result, serviceName, className, packageName);
        }
    }

    /**
     * Add a producer to the registry
     */
    private void addProducer(Map<String, List<KafkaProducerInfo>> topicProducers, String topic,
                             CodeAnalysisResult result, String serviceName, String className,
                             String methodName, String packageName, LineRange line) {

        if (topic == null || topic.startsWith("<") || topic.contains("$")) {
            return; // Skip unresolved topics
        }

        KafkaProducerInfo producer = KafkaProducerInfo.builder()
                .projectId(result.getProjectId())
                .repoUrl(result.getRepoUrl())
                .serviceName(serviceName)
                .className(className)
                .methodName(methodName)
                .packageName(packageName)
                .line(line)
                .build();

        topicProducers.computeIfAbsent(topic, k -> new ArrayList<>()).add(producer);
    }

    /**
     * Extract service name from analysis result
     */
    private String extractServiceName(CodeAnalysisResult result) {
        if (result.getApplicationInfo() != null && result.getApplicationInfo().getMainClassName() != null) {
            return result.getApplicationInfo().getMainClassName().replace("Application", "")
                    .replace("Service", "");
        }

        // Fallback: extract from repo URL
        String repoUrl = result.getRepoUrl();
        if (repoUrl != null) {
            String[] parts = repoUrl.split("/");
            String repoName = parts[parts.length - 1].replace(".git", "");
            return repoName;
        }

        return "UnknownService";
    }
}
