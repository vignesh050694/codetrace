package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Helper class for common code extraction operations
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CodeExtractionHelper {

    private final PropertyResolver propertyResolver;

    private static final Set<String> REST_TEMPLATE_METHODS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity",
            "put", "delete", "exchange", "patchForObject", "execute"
    );

    private static final Set<String> WEBCLIENT_HTTP_METHODS = Set.of(
            "get", "post", "put", "delete", "patch", "head", "options"
    );

    /**
     * Create line range from Spoon element
     */
    public LineRange createLineRange(spoon.reflect.declaration.CtElement element) {
        try {
            int startLine = element.getPosition().getLine();
            int endLine = element.getPosition().getEndLine();
            return LineRange.builder()
                    .from(startLine)
                    .to(endLine)
                    .build();
        } catch (Exception e) {
            return LineRange.builder().from(0).to(0).build();
        }
    }

    /**
     * Extract method calls from a method
     */
    public List<MethodCall> extractMethodCalls(CtMethod<?> method, Map<String, String> properties,
                                                Map<String, String> valueFieldMapping) {
        List<MethodCall> calls = new ArrayList<>();
        Set<String> processedCalls = new HashSet<>();

        method.getElements(element -> element instanceof CtInvocation).forEach(element -> {
            CtInvocation<?> invocation = (CtInvocation<?>) element;
            CtExecutableReference<?> execRef = invocation.getExecutable();

            if (execRef.getDeclaringType() != null) {
                String className = execRef.getDeclaringType().getQualifiedName();
                String methodName = execRef.getSimpleName();
                String callKey = className + "." + methodName;

                // Avoid duplicate calls, standard libraries, and getters/setters
                if (!processedCalls.contains(callKey) && !isJavaStandardLibrary(className)
                        && !isGetterOrSetter(methodName)) {
                    processedCalls.add(callKey);

                    List<ExternalCallInfo> nestedExternalCalls = new ArrayList<>();
                    List<KafkaCallInfo> nestedKafkaCalls = new ArrayList<>();
                    try {
                        CtExecutable<?> declaration = execRef.getExecutableDeclaration();
                        if (declaration instanceof CtMethod) {
                            nestedExternalCalls = extractExternalCalls((CtMethod<?>) declaration, properties, valueFieldMapping);
                            // Note: Kafka calls would need KafkaAnalyzer, so we skip nested kafka calls here
                        }
                    } catch (Exception e) {
                        // ignore - declaration may be unavailable
                    }

                    MethodCall methodCall = MethodCall.builder()
                            .className(className)
                            .handlerMethod(methodName)
                            .signature(execRef.getSignature())
                            .line(createLineRange(invocation))
                            .calls(extractNestedCalls(execRef, properties, valueFieldMapping))
                            .externalCalls(nestedExternalCalls)
                            .kafkaCalls(nestedKafkaCalls)
                            .build();
                    calls.add(methodCall);
                }
            }
        });

        return calls;
    }

    /**
     * Extract nested method calls
     */
    public List<MethodCall> extractNestedCalls(CtExecutableReference<?> execRef, Map<String, String> properties,
                                                Map<String, String> valueFieldMapping) {
        try {
            CtExecutable<?> declaration = execRef.getExecutableDeclaration();
            if (declaration instanceof CtMethod) {
                return extractMethodCalls((CtMethod<?>) declaration, properties, valueFieldMapping);
            }
        } catch (Exception e) {
            // Declaration not available
        }
        return new ArrayList<>();
    }

    /**
     * Extract external API calls (REST Template, WebClient, Feign)
     */
    public List<ExternalCallInfo> extractExternalCalls(CtMethod<?> method, Map<String, String> properties,
                                                        Map<String, String> valueFieldMapping) {
        List<ExternalCallInfo> externalCalls = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Set<String> feignClients = findFeignClientTypes(method.getFactory().getModel());

        method.getElements(element -> element instanceof CtInvocation).forEach(element -> {
            CtInvocation<?> invocation = (CtInvocation<?>) element;
            CtExecutableReference<?> execRef = invocation.getExecutable();

            String declaringType = execRef.getDeclaringType() != null
                    ? execRef.getDeclaringType().getQualifiedName()
                    : "";
            String methodName = execRef.getSimpleName();

            ExternalCallInfo info = null;

            if (isRestTemplateCall(invocation, declaringType, methodName)) {
                info = buildRestTemplateCall(invocation, declaringType, methodName, properties, valueFieldMapping, method.getDeclaringType());
            } else if (isWebClientCall(invocation, declaringType, methodName)) {
                info = buildWebClientCall(invocation, declaringType, methodName, properties, valueFieldMapping, method.getDeclaringType());
            } else if (isFeignClientCall(declaringType, feignClients)) {
                info = buildFeignCall(invocation, execRef, declaringType);
            }

            if (info != null) {
                String key = info.getClientType() + ":" + info.getTargetClass() + ":" + info.getTargetMethod() + ":" + info.getUrl();
                if (seen.add(key)) {
                    externalCalls.add(info);
                }
            }
        });

        return externalCalls;
    }

    /**
     * Extract string value from Spoon expression (handles literals, field reads, static constants, concatenation)
     */
    public String extractStringFromExpression(spoon.reflect.code.CtExpression<?> expression, Map<String, String> properties,
                                               Map<String, String> valueFieldMapping, CtType<?> declaringClass) {
        if (expression == null) {
            return null;
        }

        if (expression instanceof CtLiteral) {
            Object value = ((CtLiteral<?>) expression).getValue();
            if (value instanceof String) {
                return (String) value;
            }
        }

        // Handle CtFieldRead (field access like marksTopic or TOPIC_CONSTANT)
        if (expression instanceof CtFieldRead) {
            CtFieldRead<?> fieldRead = (CtFieldRead<?>) expression;

            try {
                CtFieldReference<?> fieldRef = fieldRead.getVariable();
                if (fieldRef != null) {
                    String fieldName = fieldRef.getSimpleName();
                    CtTypeReference<?> declaringType = fieldRef.getDeclaringType();

                    if (declaringType != null) {
                        String fieldClassName = declaringType.getQualifiedName();
                        String qualifiedFieldName = fieldClassName + "." + fieldName;

                        // Try exact match
                        String resolvedValue = valueFieldMapping.get(qualifiedFieldName);
                        if (resolvedValue != null && !resolvedValue.startsWith("${")) {
                            return resolvedValue;
                        }

                        // Try suffix match
                        for (Map.Entry<String, String> entry : valueFieldMapping.entrySet()) {
                            if (entry.getKey().endsWith("." + fieldName)) {
                                return entry.getValue();
                            }
                        }

                        // Fallback: try to resolve static constant directly from AST
                        try {
                            CtField<?> fieldDecl = fieldRef.getFieldDeclaration();
                            if (fieldDecl != null && fieldDecl.isStatic() && fieldDecl.isFinal()) {
                                CtExpression<?> defaultExpr = fieldDecl.getDefaultExpression();
                                if (defaultExpr instanceof CtLiteral) {
                                    Object literalVal = ((CtLiteral<?>) defaultExpr).getValue();
                                    if (literalVal instanceof String) {
                                        String constValue = (String) literalVal;
                                        if (constValue.contains("${")) {
                                            constValue = propertyResolver.resolveProperty(constValue, properties);
                                        }
                                        return constValue;
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            log.debug("Could not resolve field declaration for: {}", qualifiedFieldName);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Error extracting field from CtFieldRead: {}", e.getMessage());
            }
        }

        if (expression instanceof CtBinaryOperator) {
            CtBinaryOperator<?> binary = (CtBinaryOperator<?>) expression;
            if (binary.getKind() == BinaryOperatorKind.PLUS) {
                String left = extractStringFromExpression(binary.getLeftHandOperand(), properties, valueFieldMapping, declaringClass);
                String right = extractStringFromExpression(binary.getRightHandOperand(), properties, valueFieldMapping, declaringClass);
                return joinUrlPieces(left, right);
            }
        }

        // Fallback: try generic field reference by text
        String text = expression.toString();
        if (text != null && !text.isBlank()) {
            String fieldName = text.trim();
            String qualifiedFieldName = declaringClass != null ?
                    declaringClass.getQualifiedName() + "." + fieldName : fieldName;

            String resolvedValue = valueFieldMapping.get(qualifiedFieldName);
            if (resolvedValue != null && !resolvedValue.startsWith("${")) {
                return resolvedValue;
            }

            // Try suffix match
            for (Map.Entry<String, String> entry : valueFieldMapping.entrySet()) {
                if (entry.getKey().endsWith("." + fieldName)) {
                    return entry.getValue();
                }
            }

            return "<dynamic>";
        }
        return null;
    }

    /**
     * Merge external call lists
     */
    public List<ExternalCallInfo> mergeExternalCalls(List<ExternalCallInfo> list1, List<ExternalCallInfo> list2) {
        Map<String, ExternalCallInfo> merged = new LinkedHashMap<>();

        for (ExternalCallInfo call : list1) {
            String key = call.getClientType() + ":" + call.getUrl() + ":" + call.getHttpMethod();
            merged.putIfAbsent(key, call);
        }

        for (ExternalCallInfo call : list2) {
            String key = call.getClientType() + ":" + call.getUrl() + ":" + call.getHttpMethod();
            merged.putIfAbsent(key, call);
        }

        return new ArrayList<>(merged.values());
    }

    /**
     * Collect external calls from method calls recursively
     */
    public List<ExternalCallInfo> collectExternalCallsFromCalls(List<MethodCall> calls) {
        List<ExternalCallInfo> allCalls = new ArrayList<>();
        for (MethodCall call : calls) {
            allCalls.addAll(call.getExternalCalls());
            allCalls.addAll(collectExternalCallsFromCalls(call.getCalls()));
        }
        return allCalls;
    }

    /**
     * Collect Kafka calls from method calls recursively
     */
    public List<KafkaCallInfo> collectKafkaCallsFromCalls(List<MethodCall> calls) {
        List<KafkaCallInfo> allCalls = new ArrayList<>();
        for (MethodCall call : calls) {
            allCalls.addAll(call.getKafkaCalls());
            allCalls.addAll(collectKafkaCallsFromCalls(call.getCalls()));
        }
        return allCalls;
    }

    // Private helper methods

    private boolean isRestTemplateCall(CtInvocation<?> invocation, String declaringType, String methodName) {
        if (declaringType.endsWith("RestTemplate") && REST_TEMPLATE_METHODS.contains(methodName)) {
            return true;
        }
        var target = invocation.getTarget();
        return target != null && target.toString().toLowerCase().contains("resttemplate");
    }

    private boolean isWebClientCall(CtInvocation<?> invocation, String declaringType, String methodName) {
        return declaringType.contains("WebClient") || WEBCLIENT_HTTP_METHODS.contains(methodName.toLowerCase());
    }

    private boolean isFeignClientCall(String declaringType, Set<String> feignClients) {
        return feignClients.contains(declaringType);
    }

    private ExternalCallInfo buildRestTemplateCall(CtInvocation<?> invocation, String declaringType, String methodName,
                                                    Map<String, String> properties, Map<String, String> valueFieldMapping, CtType<?> declaringClass) {
        String httpMethod = restTemplateHttpMethod(methodName, invocation);
        String url = extractUrlLiteral(invocation.getArguments(), properties, valueFieldMapping, declaringClass);

        return ExternalCallInfo.builder()
                .clientType("RestTemplate")
                .httpMethod(httpMethod)
                .url(url)
                .targetClass(declaringType)
                .targetMethod(methodName)
                .line(createLineRange(invocation))
                .resolved(false)
                .build();
    }

    private ExternalCallInfo buildWebClientCall(CtInvocation<?> invocation, String declaringType, String methodName,
                                                 Map<String, String> properties, Map<String, String> valueFieldMapping, CtType<?> declaringClass) {
        String httpMethod = methodName.toUpperCase(Locale.ROOT);
        if (!WEBCLIENT_HTTP_METHODS.contains(methodName)) {
            httpMethod = "UNKNOWN";
        }
        String url = extractUrlFromWebClientChain(invocation, properties, valueFieldMapping, declaringClass);

        return ExternalCallInfo.builder()
                .clientType("WebClient")
                .httpMethod(httpMethod)
                .url(url)
                .targetClass(declaringType)
                .targetMethod(methodName)
                .line(createLineRange(invocation))
                .resolved(false)
                .build();
    }

    private ExternalCallInfo buildFeignCall(CtInvocation<?> invocation, CtExecutableReference<?> execRef, String declaringType) {
        String httpMethod = "REQUEST";
        String url = "<dynamic>";

        CtExecutable<?> decl = execRef.getExecutableDeclaration();
        if (decl instanceof CtMethod) {
            CtMethod<?> feignMethod = (CtMethod<?>) decl;
            // Extract mapping annotation if available
            // (simplified - would need full mapping extraction logic)
        }

        return ExternalCallInfo.builder()
                .clientType("Feign")
                .httpMethod(httpMethod)
                .url(url)
                .targetClass(declaringType)
                .targetMethod(execRef.getSimpleName())
                .line(createLineRange(invocation))
                .resolved(false)
                .build();
    }

    private String restTemplateHttpMethod(String methodName, CtInvocation<?> invocation) {
        if (methodName.startsWith("get")) return "GET";
        if (methodName.startsWith("post")) return "POST";
        if (methodName.startsWith("put")) return "PUT";
        if (methodName.startsWith("delete")) return "DELETE";
        if (methodName.startsWith("patch")) return "PATCH";
        return "REQUEST";
    }

    private String extractUrlLiteral(List<? extends spoon.reflect.code.CtExpression<?>> arguments,
                                      Map<String, String> properties,
                                      Map<String, String> valueFieldMapping, CtType<?> declaringClass) {
        for (var arg : arguments) {
            String extracted = extractStringFromExpression(arg, properties, valueFieldMapping, declaringClass);
            if (extracted != null && !extracted.isBlank()) {
                return normalizeUrl(extracted);
            }
        }
        return "<dynamic>";
    }

    private String extractUrlFromWebClientChain(CtInvocation<?> invocation, Map<String, String> properties,
                                                 Map<String, String> valueFieldMapping, CtType<?> declaringClass) {
        var target = invocation.getTarget();
        while (target instanceof CtInvocation) {
            CtInvocation<?> targetInvocation = (CtInvocation<?>) target;
            if ("uri".equals(targetInvocation.getExecutable().getSimpleName())) {
                return extractUrlLiteral(targetInvocation.getArguments(), properties, valueFieldMapping, declaringClass);
            }
            target = targetInvocation.getTarget();
        }
        return "<dynamic>";
    }

    private String joinUrlPieces(String left, String right) {
        String safeLeft = left == null ? "<dynamic>" : left;
        String safeRight = right == null ? "<dynamic>" : right;

        if ("<dynamic>".equals(safeLeft) && safeRight.startsWith("/")) {
            return safeRight;
        }
        if ("<dynamic>".equals(safeRight) && safeLeft.contains("/")) {
            return safeLeft + "<dynamic>";
        }
        return safeLeft + safeRight;
    }

    private String normalizeUrl(String url) {
        if (url == null || url.equals("<dynamic>")) {
            return "<dynamic>";
        }
        return url.replaceAll("\"", "").trim();
    }

    private Set<String> findFeignClientTypes(spoon.reflect.CtModel model) {
        return model.getAllTypes().stream()
                .filter(type -> type.getAnnotations().stream()
                        .anyMatch(ann -> "FeignClient".equals(ann.getAnnotationType().getSimpleName())))
                .map(type -> type.getQualifiedName())
                .collect(Collectors.toSet());
    }

    private boolean isJavaStandardLibrary(String className) {
        return className.startsWith("java.") ||
                className.startsWith("javax.") ||
                className.startsWith("jakarta.");
    }

    /**
     * Checks if a method name follows getter/setter/builder/common-object patterns.
     * These are boilerplate methods that add noise to the call graph.
     */
    private boolean isGetterOrSetter(String methodName) {
        if (methodName == null || methodName.isEmpty()) return false;

        // Standard getter: getName(), getXxx()
        if (methodName.startsWith("get") && methodName.length() > 3
                && Character.isUpperCase(methodName.charAt(3))) {
            return true;
        }

        // Boolean getter: isActive(), isXxx()
        if (methodName.startsWith("is") && methodName.length() > 2
                && Character.isUpperCase(methodName.charAt(2))) {
            return true;
        }

        // Setter: setName(), setXxx()
        if (methodName.startsWith("set") && methodName.length() > 3
                && Character.isUpperCase(methodName.charAt(3))) {
            return true;
        }

        // Builder pattern and common Object methods
        switch (methodName) {
            case "builder":
            case "build":
            case "toBuilder":
            case "toString":
            case "hashCode":
            case "equals":
            case "canEqual":
            case "of":
            case "valueOf":
                return true;
            default:
                return false;
        }
    }
}
