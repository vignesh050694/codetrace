package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;

import java.util.*;

/**
 * Service responsible for extracting and analyzing Kafka-related code elements
 * including listeners, producers, and topics.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaAnalyzer {

    private final PropertyResolver propertyResolver;
    private final CodeExtractionHelper extractionHelper;

    private static final Set<String> KAFKA_PRODUCER_METHODS = Set.of(
            "send", "sendDefault"
    );

    private static final Set<String> KAFKA_PRODUCER_TYPES = Set.of(
            "KafkaTemplate", "ReactiveKafkaProducerTemplate"
    );

    private static final Set<String> KAFKA_LISTENER_ANNOTATIONS = Set.of(
            "KafkaListener", "KafkaHandler"
    );

    /**
     * Extract Kafka listeners for a specific package
     */
    public List<KafkaListenerInfo> extractKafkaListenersForPackage(CtModel model, String basePackage,
                                                                     Map<String, String> properties,
                                                                     Map<String, String> valueFieldMapping) {
        List<KafkaListenerInfo> kafkaListeners = new ArrayList<>();

        model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::hasKafkaListenerMethods)
                .filter(ctClass -> belongsToPackage(ctClass, basePackage))
                .forEach(ctClass -> {
                    List<KafkaListenerMethod> listeners = extractKafkaListenerMethods(ctClass, properties, valueFieldMapping);
                    if (!listeners.isEmpty()) {
                        KafkaListenerInfo listenerInfo = KafkaListenerInfo.builder()
                                .className(ctClass.getSimpleName())
                                .packageName(ctClass.getPackage().getQualifiedName())
                                .line(extractionHelper.createLineRange(ctClass))
                                .listeners(listeners)
                                .build();
                        kafkaListeners.add(listenerInfo);
                        log.info("Found Kafka Listener class: {} with {} listener methods",
                                ctClass.getQualifiedName(), listeners.size());
                    }
                });

        return kafkaListeners;
    }

    /**
     * Extract Kafka listeners from all packages
     */
    public List<KafkaListenerInfo> extractKafkaListeners(CtModel model, Map<String, String> properties,
                                                          Map<String, String> valueFieldMapping) {
        List<KafkaListenerInfo> kafkaListeners = new ArrayList<>();

        model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::hasKafkaListenerMethods)
                .forEach(ctClass -> {
                    List<KafkaListenerMethod> listeners = extractKafkaListenerMethods(ctClass, properties, valueFieldMapping);
                    if (!listeners.isEmpty()) {
                        KafkaListenerInfo listenerInfo = KafkaListenerInfo.builder()
                                .className(ctClass.getSimpleName())
                                .packageName(ctClass.getPackage().getQualifiedName())
                                .line(extractionHelper.createLineRange(ctClass))
                                .listeners(listeners)
                                .build();
                        kafkaListeners.add(listenerInfo);
                    }
                });

        return kafkaListeners;
    }

    /**
     * Check if a class has Kafka listener methods
     */
    public boolean hasKafkaListenerMethods(CtType<?> ctClass) {
        return ctClass.getMethods().stream()
                .anyMatch(method -> method.getAnnotations().stream()
                        .anyMatch(ann -> KAFKA_LISTENER_ANNOTATIONS.contains(ann.getAnnotationType().getSimpleName())));
    }

    /**
     * Extract individual Kafka listener methods from a class
     */
    public List<KafkaListenerMethod> extractKafkaListenerMethods(CtType<?> ctClass,
                                                                   Map<String, String> properties,
                                                                   Map<String, String> valueFieldMapping) {
        List<KafkaListenerMethod> listeners = new ArrayList<>();

        ctClass.getMethods().forEach(method -> {
            for (CtAnnotation<?> annotation : method.getAnnotations()) {
                String annotationName = annotation.getAnnotationType().getSimpleName();
                if (KAFKA_LISTENER_ANNOTATIONS.contains(annotationName)) {
                    String topic = extractTopicFromListenerAnnotation(annotation, properties);
                    String groupId = extractGroupIdFromListenerAnnotation(annotation, properties);

                    // Extract method calls and external calls from the listener
                    List<MethodCall> calls = extractionHelper.extractMethodCalls(method, properties, valueFieldMapping);
                    List<ExternalCallInfo> externalCalls = extractionHelper.mergeExternalCalls(
                            extractionHelper.extractExternalCalls(method, properties, valueFieldMapping),
                            extractionHelper.collectExternalCallsFromCalls(calls)
                    );
                    List<KafkaCallInfo> kafkaCalls = mergeKafkaCalls(
                            extractKafkaCalls(method, properties, valueFieldMapping),
                            extractionHelper.collectKafkaCallsFromCalls(calls)
                    );

                    KafkaListenerMethod listener = KafkaListenerMethod.builder()
                            .methodName(method.getSimpleName())
                            .topic(topic)
                            .groupId(groupId)
                            .line(extractionHelper.createLineRange(method))
                            .signature(method.getSignature())
                            .calls(calls)
                            .externalCalls(externalCalls)
                            .kafkaCalls(kafkaCalls)
                            .build();
                    listeners.add(listener);

                    log.info("✓ Found Kafka Listener: {}.{}() consuming topic: {}",
                            ctClass.getSimpleName(), method.getSimpleName(), topic);
                }
            }
        });

        return listeners;
    }

    /**
     * Extract Kafka calls (producer calls) from a method
     */
    public List<KafkaCallInfo> extractKafkaCalls(CtMethod<?> method, Map<String, String> properties,
                                                  Map<String, String> valueFieldMapping) {
        List<KafkaCallInfo> kafkaCalls = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. Check if the method itself is a Kafka listener (consumer)
        for (CtAnnotation<?> annotation : method.getAnnotations()) {
            String annotationName = annotation.getAnnotationType().getSimpleName();
            if (KAFKA_LISTENER_ANNOTATIONS.contains(annotationName)) {
                TopicResolution resolution = resolveTopic(extractTopicFromListenerAnnotation(annotation, properties), properties);
                String topic = resolution.effectiveTopic();
                if (topic != null && !topic.isEmpty()) {
                    String key = "CONSUMER:" + topic + ":" + method.getSimpleName();
                    if (seen.add(key)) {
                        KafkaCallInfo consumer = KafkaCallInfo.builder()
                                .direction("CONSUMER")
                                .rawTopic(resolution.rawTopic())
                                .resolvedTopic(resolution.resolvedTopic())
                                .topic(topic)
                                .topicResolved(resolution.resolved())
                                .clientType(annotationName)
                                .className(method.getDeclaringType().getQualifiedName())
                                .methodName(method.getSimpleName())
                                .signature(method.getSignature())
                                .line(extractionHelper.createLineRange(method))
                                .resolved(false)
                                .build();
                        kafkaCalls.add(consumer);
                    }
                }
            }
        }

        // 2. Check for Kafka producer calls within the method
        method.getElements(element -> element instanceof CtInvocation).forEach(element -> {
            CtInvocation<?> invocation = (CtInvocation<?>) element;
            CtExecutableReference<?> execRef = invocation.getExecutable();

            String declaringType = execRef.getDeclaringType() != null
                    ? execRef.getDeclaringType().getQualifiedName()
                    : "";
            String methodName = execRef.getSimpleName();

            if (isKafkaProducerCall(invocation, declaringType, methodName)) {
                TopicResolution resolution = extractTopicResolutionFromProducerCall(invocation, properties, valueFieldMapping, method.getDeclaringType());
                String topic = resolution.effectiveTopic();
                String key = "PRODUCER:" + topic + ":" + methodName;
                if (seen.add(key)) {
                    KafkaCallInfo producer = KafkaCallInfo.builder()
                            .direction("PRODUCER")
                            .rawTopic(resolution.rawTopic())
                            .resolvedTopic(resolution.resolvedTopic())
                            .topic(topic)
                            .topicResolved(resolution.resolved())
                            .clientType(determineKafkaClientType(declaringType))
                            .className(method.getDeclaringType().getQualifiedName())
                            .methodName(methodName)
                            .signature(method.getSignature())
                            .line(extractionHelper.createLineRange(invocation))
                            .resolved(false)
                            .build();
                    kafkaCalls.add(producer);
                }
            }
        });

        return kafkaCalls;
    }

    private TopicResolution extractTopicResolutionFromProducerCall(CtInvocation<?> invocation, Map<String, String> properties,
                                                                   Map<String, String> valueFieldMapping, CtType<?> declaringClass) {
        var arguments = invocation.getArguments();

        if (!arguments.isEmpty()) {
            var firstArg = arguments.get(0);
            String extracted = extractionHelper.extractStringFromExpression(firstArg, properties, valueFieldMapping, declaringClass);
            if (extracted != null && !extracted.equals("<dynamic>")) {
                return resolveTopic(extracted, properties);
            }
        }

        String methodName = invocation.getExecutable().getSimpleName();
        if ("sendDefault".equals(methodName)) {
            return new TopicResolution("<default-topic>", null, "<default-topic>", true);
        }

        // Could not resolve; use textual argument or placeholder to avoid <dynamic>
        String fallback = arguments.isEmpty() ? "<unknown-topic>" : arguments.get(0).toString();
        return resolveTopic(fallback, properties);
    }

    /**
     * Check if an invocation is a Kafka producer call
     */
    public boolean isKafkaProducerCall(CtInvocation<?> invocation, String declaringType, String methodName) {
        // Check if method is a known Kafka producer method
        if (!KAFKA_PRODUCER_METHODS.contains(methodName)) {
            return false;
        }

        // Check the target expression type
        var target = invocation.getTarget();
        if (target != null && target.getType() != null) {
            String typeName = target.getType().getQualifiedName();
            for (String kafkaType : KAFKA_PRODUCER_TYPES) {
                if (typeName.contains(kafkaType)) {
                    return true;
                }
            }
        }

        // Fallback: check variable name
        if (target != null) {
            String targetText = target.toString().toLowerCase(Locale.ROOT);
            return targetText.contains("kafka") && KAFKA_PRODUCER_METHODS.contains(methodName);
        }

        return false;
    }

    /**
     * Extract topic from Kafka producer call
     */
    public String extractTopicFromProducerCall(CtInvocation<?> invocation, Map<String, String> properties,
                                                Map<String, String> valueFieldMapping, CtType<?> declaringClass) {
        var arguments = invocation.getArguments();

        // For send(topic, data) or send(topic, key, data)
        if (!arguments.isEmpty()) {
            var firstArg = arguments.get(0);

            String extracted = extractionHelper.extractStringFromExpression(firstArg, properties, valueFieldMapping, declaringClass);
            if (extracted != null && !extracted.equals("<dynamic>")) {
                return extracted;
            }
        }

        // For sendDefault() - topic comes from default configuration
        String methodName = invocation.getExecutable().getSimpleName();
        if ("sendDefault".equals(methodName)) {
            return "<default-topic>";
        }

        return "<dynamic>";
    }

    /**
     * Extract topic from @KafkaListener annotation
     */
    public String extractTopicFromListenerAnnotation(CtAnnotation<?> annotation, Map<String, String> properties) {
        try {
            // Try to get 'topics' attribute
            Object topicsValue = annotation.getValue("topics");
            if (topicsValue != null) {
                String topicsStr = topicsValue.toString();
                // Remove quotes and brackets but keep ${} for placeholder detection
                topicsStr = topicsStr.replaceAll("^\"|\"$", "").trim();
                topicsStr = topicsStr.replaceAll("^\\[|\\]$", "").trim();

                if (!topicsStr.isEmpty()) {
                    // If multiple topics, take the first one
                    String[] topics = topicsStr.split(",");
                    String topic = topics[0].trim();

                    log.debug("Extracted topic from @KafkaListener: {}", topic);

                    // Handle different placeholder formats
                    String resolvedTopic = resolveTopicPlaceholder(topic, properties);
                    if (resolvedTopic != null) {
                        return resolvedTopic;
                    }

                    // Topic is a literal value, return as-is
                    return topic;
                }
            }

            // Try to get 'value' attribute
            Object valueAttr = annotation.getValue("value");
            if (valueAttr != null) {
                String valueStr = valueAttr.toString();
                // Remove quotes and brackets but keep ${} for placeholder detection
                valueStr = valueStr.replaceAll("^\"|\"$", "").trim();
                valueStr = valueStr.replaceAll("^\\[|\\]$", "").trim();

                if (!valueStr.isEmpty()) {
                    String[] topics = valueStr.split(",");
                    String topic = topics[0].trim();

                    log.debug("Extracted topic from @KafkaListener value: {}", topic);

                    // Handle different placeholder formats
                    String resolvedTopic = resolveTopicPlaceholder(topic, properties);
                    if (resolvedTopic != null) {
                        return resolvedTopic;
                    }

                    // Topic is a literal value, return as-is
                    return topic;
                }
            }
        } catch (Exception e) {
            log.error("Error extracting topic from @KafkaListener: {}", e.getMessage(), e);
        }

        return "<dynamic>";
    }

    /**
     * Resolve topic placeholder - handles ${...}, #{...}, $..., and literal formats
     */
    private String resolveTopicPlaceholder(String topic, Map<String, String> properties) {
        if (topic == null || topic.isEmpty()) {
            return null;
        }

        // Case 1: Standard ${property.name} format
        if (topic.contains("${") && topic.contains("}")) {
            String resolvedTopic = propertyResolver.resolveProperty(topic, properties);

            if (resolvedTopic != null && !resolvedTopic.contains("$")) {
                log.debug("Resolved Kafka topic: {} -> {}", topic, resolvedTopic);
                return resolvedTopic;
            } else {
                log.debug("Failed to resolve Kafka topic placeholder: {}", topic);
                return topic; // keep placeholder as-is
            }
        }

        // Case 2: SpEL #{...} expression (e.g., #{@topicConfig['user-topic']}, #{'${kafka.topic}'}
        if (topic.contains("#{") && topic.contains("}")) {
            // Try to extract ${...} inside SpEL
            java.util.regex.Matcher dollarMatcher = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}").matcher(topic);
            if (dollarMatcher.find()) {
                String placeholder = dollarMatcher.group(0);
                String resolvedTopic = propertyResolver.resolveProperty(placeholder, properties);
                if (resolvedTopic != null && !resolvedTopic.contains("$")) {
                    return resolvedTopic;
                }
            }

            // Try to extract quoted keys like ['key-name']
            java.util.regex.Matcher keyMatcher = java.util.regex.Pattern.compile("\\['([^']+)'\\]").matcher(topic);
            if (keyMatcher.find()) {
                String key = keyMatcher.group(1);
                String value = properties.get(key);
                if (value != null) {
                    return value;
                }
            }

            return topic; // unresolved, return raw placeholder
        }

        // Case 3: Malformed $property.name format (missing braces)
        if (topic.startsWith("$") && !topic.startsWith("${")) {
            String properPlaceholder = "${" + topic.substring(1) + "}";
            String resolvedTopic = propertyResolver.resolveProperty(properPlaceholder, properties);

            if (resolvedTopic != null && !resolvedTopic.contains("$")) {
                return resolvedTopic;
            }

            // Try direct property lookup as fallback
            String propertyKey = topic.substring(1);
            String directValue = properties.get(propertyKey);
            if (directValue != null) {
                return directValue;
            }

            return topic; // unresolved, return raw placeholder
        }

        // Case 4: Plain string that might be a property key (no $ prefix but has dots)
        if (topic.contains(".") && !topic.contains("/") && !topic.contains(" ")) {
            String value = properties.get(topic);
            if (value != null) {
                return value;
            }
        }

        // Case 5: Not a placeholder, return null to indicate no resolution needed
        return null;
    }

    /**
     * Extract group ID from @KafkaListener annotation
     */
    public String extractGroupIdFromListenerAnnotation(CtAnnotation<?> annotation, Map<String, String> properties) {
        try {
            Object groupIdValue = annotation.getValue("groupId");
            if (groupIdValue != null) {
                String groupId = groupIdValue.toString();
                // Remove quotes but keep ${} for placeholder detection
                groupId = groupId.replaceAll("^\"|\"$", "").trim();

                log.debug("Extracted groupId from @KafkaListener: {}", groupId);

                // Use the same resolution logic as topics
                String resolvedGroupId = resolveTopicPlaceholder(groupId, properties);
                if (resolvedGroupId != null) {
                    return resolvedGroupId;
                }

                // GroupId is a literal value, return as-is
                return groupId;
            }
        } catch (Exception e) {
            log.error("Error extracting groupId from @KafkaListener: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * Determine Kafka client type from declaring type
     */
    public String determineKafkaClientType(String declaringType) {
        if (declaringType.contains("ReactiveKafkaProducerTemplate")) {
            return "ReactiveKafkaProducerTemplate";
        }
        if (declaringType.contains("KafkaTemplate")) {
            return "KafkaTemplate";
        }
        return "UnknownKafkaClient";
    }

    /**
     * Merge Kafka call lists removing duplicates
     */
    public List<KafkaCallInfo> mergeKafkaCalls(List<KafkaCallInfo> list1, List<KafkaCallInfo> list2) {
        Map<String, KafkaCallInfo> merged = new LinkedHashMap<>();

        for (KafkaCallInfo call : list1) {
            String key = call.getDirection() + ":" + call.getTopic() + ":" + call.getMethodName();
            merged.putIfAbsent(key, call);
        }

        for (KafkaCallInfo call : list2) {
            String key = call.getDirection() + ":" + call.getTopic() + ":" + call.getMethodName();
            merged.putIfAbsent(key, call);
        }

        return new ArrayList<>(merged.values());
    }

    /**
     * Check if a type belongs to a specific package
     */
    private boolean belongsToPackage(CtType<?> ctType, String basePackage) {
        String packageName = ctType.getPackage().getQualifiedName();
        return packageName.startsWith(basePackage);
    }

    private TopicResolution resolveTopic(String topicCandidate, Map<String, String> properties) {
        String raw = topicCandidate;
        if (raw == null) raw = "";

        String resolved = resolveTopicPlaceholder(raw, properties);
        String effective = resolved != null ? resolved : raw;
        boolean resolvedFlag = resolved != null && !containsPlaceholder(effective);
        if (resolved == null && !containsPlaceholder(raw)) {
            resolvedFlag = true;
        }
        return new TopicResolution(raw, resolved, effective, resolvedFlag);
    }

    private boolean containsPlaceholder(String topic) {
        if (topic == null) return false;
        return topic.contains("${") || topic.contains("#{") || topic.startsWith("<unresolved:");
    }

    private record TopicResolution(String rawTopic, String resolvedTopic, String effectiveTopic, boolean resolved) {}
}
