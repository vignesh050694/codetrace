package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Code analyzer using Spoon framework.
 *
 * Traces the flow: Controller -> Service -> Repository -> Database
 * Flags: External HTTP calls (RestTemplate, WebClient, Feign)
 * Flags: Kafka producers/consumers with topic names
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SpoonCodeAnalyzer {

    private final PropertyResolver propertyResolver;
    private final KafkaAnalyzer kafkaAnalyzer;

    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping"
    );

    private static final Map<String, String> ANNOTATION_TO_HTTP_METHOD = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH",
            "RequestMapping", "REQUEST"
    );

    private static final Set<String> REST_TEMPLATE_METHODS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity", "put", "delete", "exchange"
    );

    private static final Set<String> WEBCLIENT_HTTP_METHODS = Set.of(
            "get", "post", "put", "delete", "patch", "method"
    );

    /**
     * Call trace depth from endpoint methods.
     * depth=1 means: Endpoint -> Service method (and service method's direct calls are captured too)
     * This gives us: Controller -> Service -> Repository chain
     */
    private static final int ENDPOINT_CALL_DEPTH = 1;

    /**
     * Call trace depth from service methods.
     * depth=0 means: only direct calls (repository calls, external calls, kafka calls)
     */
    private static final int SERVICE_CALL_DEPTH = 0;

    // ========================= PUBLIC API =========================

    public List<CodeAnalysisResponse> analyzeCode(Path repositoryPath, String projectId, String repoUrl) {
        log.info("Starting code analysis for: {}", repositoryPath);

        Map<String, String> properties = propertyResolver.loadProperties(repositoryPath);
        log.info("Loaded {} configuration properties", properties.size());

        CtModel model = buildSpoonModel(repositoryPath);

        List<CtType<?>> springBootApps = findSpringBootApplications(model);

        if (springBootApps.isEmpty()) {
            log.warn("No Spring Boot application found in repository");
            return List.of(analyzeWithoutSpringBoot(model, projectId, repoUrl, properties));
        }

        log.info("Found {} Spring Boot application(s)", springBootApps.size());

        return springBootApps.stream()
                .map(app -> analyzeApplication(model, app, projectId, repoUrl, properties))
                .collect(Collectors.toList());
    }

    // ========================= SPOON MODEL =========================

    private CtModel buildSpoonModel(Path repositoryPath) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(repositoryPath.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(21);
        launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        launcher.getEnvironment().setCommentEnabled(false);
        return launcher.buildModel();
    }

    // ========================= APPLICATION ANALYSIS =========================

    private List<CtType<?>> findSpringBootApplications(CtModel model) {
        return model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::isSpringBootApplication)
                .collect(Collectors.toList());
    }

    private CodeAnalysisResponse analyzeApplication(CtModel model, CtType<?> app,
                                                    String projectId, String repoUrl,
                                                    Map<String, String> properties) {
        String basePackage = app.getPackage().getQualifiedName();
        log.info("Analyzing application: {} (package: {})", app.getSimpleName(), basePackage);

        Map<String, String> valueFieldMapping = buildValueFieldMapping(model, basePackage, properties);

        List<ControllerInfo> controllers = extractControllers(model, basePackage, properties, valueFieldMapping);
        List<ServiceInfo> services = extractServices(model, basePackage, properties, valueFieldMapping);
        List<RepositoryInfo> repositories = extractRepositories(model, basePackage, properties, valueFieldMapping);
        List<KafkaListenerInfo> kafkaListeners = kafkaAnalyzer.extractKafkaListenersForPackage(
                model, basePackage, properties, valueFieldMapping);
        List<ConfigurationInfo> configurations = extractConfigurations(model, basePackage);

        log.info("Analysis complete for {}. Controllers={}, Services={}, Repositories={}, Kafka={}, Config={}",
                app.getSimpleName(), controllers.size(), services.size(), repositories.size(),
                kafkaListeners.size(), configurations.size());

        return buildResponse(projectId, repoUrl, buildApplicationInfo(app),
                controllers, services, repositories, kafkaListeners, configurations);
    }

    private CodeAnalysisResponse analyzeWithoutSpringBoot(CtModel model, String projectId,
                                                          String repoUrl, Map<String, String> properties) {
        Map<String, String> valueFieldMapping = buildValueFieldMapping(model, "", properties);

        List<ControllerInfo> controllers = extractControllers(model, "", properties, valueFieldMapping);
        List<ServiceInfo> services = extractServices(model, "", properties, valueFieldMapping);
        List<RepositoryInfo> repositories = extractRepositories(model, "", properties, valueFieldMapping);
        List<KafkaListenerInfo> kafkaListeners = kafkaAnalyzer.extractKafkaListeners(
                model, properties, valueFieldMapping);
        List<ConfigurationInfo> configurations = extractConfigurations(model, "");

        ApplicationInfo appInfo = ApplicationInfo.builder().isSpringBootApplication(false).build();

        return buildResponse(projectId, repoUrl, appInfo,
                controllers, services, repositories, kafkaListeners, configurations);
    }

    private CodeAnalysisResponse buildResponse(String projectId, String repoUrl, ApplicationInfo appInfo,
                                               List<ControllerInfo> controllers, List<ServiceInfo> services,
                                               List<RepositoryInfo> repositories,
                                               List<KafkaListenerInfo> kafkaListeners,
                                               List<ConfigurationInfo> configurations) {
        int totalClasses = controllers.size() + services.size() + repositories.size()
                + kafkaListeners.size() + configurations.size();
        int totalMethods = controllers.stream().mapToInt(c -> c.getEndpoints().size()).sum()
                + services.stream().mapToInt(s -> s.getMethods().size()).sum()
                + repositories.stream().mapToInt(r -> r.getMethods().size()).sum()
                + kafkaListeners.stream().mapToInt(k -> k.getListeners().size()).sum()
                + configurations.stream().mapToInt(c -> c.getBeans().size()).sum();

        return CodeAnalysisResponse.builder()
                .projectId(projectId)
                .repoUrl(repoUrl)
                .analyzedAt(LocalDateTime.now())
                .applicationInfo(appInfo)
                .controllers(controllers)
                .services(services)
                .repositories(repositories)
                .kafkaListeners(kafkaListeners)
                .configurations(configurations)
                .totalClasses(totalClasses)
                .totalMethods(totalMethods)
                .status("COMPLETED")
                .build();
    }

    private ApplicationInfo buildApplicationInfo(CtType<?> springBootApp) {
        return ApplicationInfo.builder()
                .isSpringBootApplication(true)
                .mainClassName(springBootApp.getSimpleName())
                .mainClassPackage(springBootApp.getPackage().getQualifiedName())
                .rootPath(springBootApp.getPosition().getFile().getAbsolutePath())
                .line(createLineRange(springBootApp))
                .build();
    }

    // ========================= CONTROLLER EXTRACTION =========================

    private List<ControllerInfo> extractControllers(CtModel model, String basePackage,
                                                    Map<String, String> properties,
                                                    Map<String, String> valueFieldMapping) {
        return model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::isController)
                .filter(t -> matchesPackage(t, basePackage))
                .map(ctClass -> ControllerInfo.builder()
                        .className(ctClass.getSimpleName())
                        .packageName(ctClass.getPackage().getQualifiedName())
                        .line(createLineRange(ctClass))
                        .endpoints(extractEndpoints(ctClass, properties, valueFieldMapping))
                        .build())
                .collect(Collectors.toList());
    }

    private List<EndpointInfo> extractEndpoints(CtType<?> controller,
                                                Map<String, String> properties,
                                                Map<String, String> valueFieldMapping) {
        List<EndpointInfo> endpoints = new ArrayList<>();
        String basePath = extractBasePath(controller);

        for (CtMethod<?> method : controller.getMethods()) {
            for (CtAnnotation<?> ann : method.getAnnotations()) {
                String annName = ann.getAnnotationType().getSimpleName();
                if (!MAPPING_ANNOTATIONS.contains(annName)) continue;

                String httpMethod = ANNOTATION_TO_HTTP_METHOD.getOrDefault(annName, "REQUEST");
                String path = extractPath(ann, basePath);

                // Trace calls with depth=1: captures service method calls and their internal calls
                List<MethodCall> calls = extractMethodCalls(method, properties, valueFieldMapping, ENDPOINT_CALL_DEPTH);
                List<ExternalCallInfo> externalCalls = mergeExternalCalls(
                        extractExternalCalls(method, properties, valueFieldMapping),
                        collectExternalCallsFromCalls(calls));
                List<KafkaCallInfo> kafkaCalls = kafkaAnalyzer.mergeKafkaCalls(
                        kafkaAnalyzer.extractKafkaCalls(method, properties, valueFieldMapping),
                        collectKafkaCallsFromCalls(calls));

                endpoints.add(EndpointInfo.builder()
                        .method(httpMethod)
                        .path(path)
                        .handlerMethod(method.getSimpleName())
                        .line(createLineRange(method))
                        .signature(method.getSignature())
                        .calls(calls)
                        .externalCalls(externalCalls)
                        .kafkaCalls(kafkaCalls)
                        .requestBody(extractRequestBody(method))
                        .response(extractResponseType(method, httpMethod))
                        .build());
            }
        }
        return endpoints;
    }

    // ========================= SERVICE EXTRACTION =========================

    private List<ServiceInfo> extractServices(CtModel model, String basePackage,
                                              Map<String, String> properties,
                                              Map<String, String> valueFieldMapping) {
        return model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::isService)
                .filter(t -> matchesPackage(t, basePackage))
                .map(ctClass -> ServiceInfo.builder()
                        .className(ctClass.getSimpleName())
                        .packageName(ctClass.getPackage().getQualifiedName())
                        .line(createLineRange(ctClass))
                        .methods(extractMethods(ctClass, properties, valueFieldMapping, true))
                        .implementedInterfaces(extractImplementedInterfaces(ctClass))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Extract the list of interfaces implemented by a class.
     * This is used to resolve method calls through interfaces to their implementations.
     */
    private List<String> extractImplementedInterfaces(CtType<?> ctClass) {
        List<String> interfaces = new ArrayList<>();
        try {
            Set<CtTypeReference<?>> superInterfaces = ctClass.getSuperInterfaces();
            if (superInterfaces != null) {
                for (CtTypeReference<?> iface : superInterfaces) {
                    // Add both simple name and qualified name for flexible resolution
                    String simpleName = iface.getSimpleName();
                    String qualifiedName = iface.getQualifiedName();

                    if (simpleName != null && !simpleName.isEmpty()) {
                        interfaces.add(simpleName);
                    }
                    if (qualifiedName != null && !qualifiedName.isEmpty() && !qualifiedName.equals(simpleName)) {
                        interfaces.add(qualifiedName);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract interfaces for class: {}", ctClass.getSimpleName());
        }
        return interfaces;
    }

    private List<MethodInfo> extractMethods(CtType<?> clazz,
                                            Map<String, String> properties,
                                            Map<String, String> valueFieldMapping) {
        return extractMethods(clazz, properties, valueFieldMapping, false);
    }

    private List<MethodInfo> extractMethods(CtType<?> clazz,
                                            Map<String, String> properties,
                                            Map<String, String> valueFieldMapping,
                                            boolean includePrivate) {
        return clazz.getMethods().stream()
                .filter(m -> includePrivate || !m.isPrivate())
                .map(method -> {
                    List<MethodCall> calls = extractMethodCalls(
                            method, properties, valueFieldMapping, SERVICE_CALL_DEPTH);
                    List<ExternalCallInfo> externalCalls = mergeExternalCalls(
                            extractExternalCalls(method, properties, valueFieldMapping),
                            collectExternalCallsFromCalls(calls));
                    List<KafkaCallInfo> kafkaCalls = kafkaAnalyzer.mergeKafkaCalls(
                            kafkaAnalyzer.extractKafkaCalls(method, properties, valueFieldMapping),
                            collectKafkaCallsFromCalls(calls));

                    return MethodInfo.builder()
                            .methodName(method.getSimpleName())
                            .signature(method.getSignature())
                            .line(createLineRange(method))
                            .calls(calls)
                            .externalCalls(externalCalls)
                            .kafkaCalls(kafkaCalls)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ========================= REPOSITORY EXTRACTION =========================

    private List<RepositoryInfo> extractRepositories(CtModel model, String basePackage,
                                                     Map<String, String> properties,
                                                     Map<String, String> valueFieldMapping) {
        return model.getAllTypes().stream()
                .filter(t -> t.isInterface() || t.isClass())
                .filter(this::isRepository)
                .filter(t -> matchesPackage(t, basePackage))
                .map(ctType -> {
                    String extendsClass = extractExtendsClass(ctType);
                    String repoType = determineRepositoryType(extendsClass);

                    return RepositoryInfo.builder()
                            .className(ctType.getSimpleName())
                            .packageName(ctType.getPackage().getQualifiedName())
                            .repositoryType(repoType)
                            .extendsClass(extendsClass)
                            .line(createLineRange(ctType))
                            .methods(extractMethods(ctType, properties, valueFieldMapping))
                            .databaseOperations(extractDatabaseOperations(ctType, model, repoType))
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ========================= CONFIGURATION EXTRACTION =========================

    private List<ConfigurationInfo> extractConfigurations(CtModel model, String basePackage) {
        return model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(this::isConfiguration)
                .filter(t -> matchesPackage(t, basePackage))
                .map(ctClass -> ConfigurationInfo.builder()
                        .className(ctClass.getSimpleName())
                        .packageName(ctClass.getPackage().getQualifiedName())
                        .line(createLineRange(ctClass))
                        .beans(extractBeans(ctClass))
                        .build())
                .collect(Collectors.toList());
    }

    private List<BeanInfo> extractBeans(CtType<?> configClass) {
        List<BeanInfo> beans = new ArrayList<>();
        for (CtMethod<?> method : configClass.getMethods()) {
            boolean isBean = method.getAnnotations().stream()
                    .anyMatch(a -> "Bean".equals(a.getAnnotationType().getSimpleName()));
            if (isBean) {
                beans.add(BeanInfo.builder()
                        .beanName(method.getSimpleName())
                        .returnType(method.getType().getQualifiedName())
                        .methodName(method.getSimpleName())
                        .line(createLineRange(method))
                        .build());
            }
        }
        return beans;
    }

    // ========================= METHOD CALL EXTRACTION =========================

    /**
     * Extract method calls from a method body with depth-limited tracing.
     *
     * depth=1: Trace one level deep (endpoint -> service methods, including their direct calls)
     * depth=0: Direct calls only (service -> repository/external calls)
     *
     * This gives us the chain: Controller -> Service -> Repository
     */
    private List<MethodCall> extractMethodCalls(CtMethod<?> method,
                                                Map<String, String> properties,
                                                Map<String, String> valueFieldMapping,
                                                int depth) {
        List<MethodCall> calls = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        method.getElements(e -> e instanceof CtInvocation).forEach(element -> {
            CtInvocation<?> invocation = (CtInvocation<?>) element;
            CtExecutableReference<?> execRef = invocation.getExecutable();

            if (execRef.getDeclaringType() == null) return;

            String className = execRef.getDeclaringType().getQualifiedName();
            String methodName = execRef.getSimpleName();
            String key = className + "." + methodName;

            if (seen.contains(key) || isJavaStandardLibrary(className)) return;
            seen.add(key);

            // Extract nested information if we have depth remaining
            List<MethodCall> nestedCalls = Collections.emptyList();
            List<ExternalCallInfo> nestedExtCalls = Collections.emptyList();
            List<KafkaCallInfo> nestedKafkaCalls = Collections.emptyList();

            if (depth > 0) {
                try {
                    CtExecutable<?> decl = execRef.getExecutableDeclaration();
                    if (decl instanceof CtMethod) {
                        CtMethod<?> declMethod = (CtMethod<?>) decl;
                        nestedCalls = extractMethodCalls(declMethod, properties, valueFieldMapping, depth - 1);
                        nestedExtCalls = extractExternalCalls(declMethod, properties, valueFieldMapping);
                        nestedKafkaCalls = kafkaAnalyzer.extractKafkaCalls(declMethod, properties, valueFieldMapping);
                    }
                } catch (Exception e) {
                    log.debug("Could not resolve declaration for: {}", key);
                }
            }

            calls.add(MethodCall.builder()
                    .className(className)
                    .handlerMethod(methodName)
                    .signature(execRef.getSignature())
                    .line(createLineRange(invocation))
                    .calls(nestedCalls)
                    .externalCalls(nestedExtCalls)
                    .kafkaCalls(nestedKafkaCalls)
                    .build());
        });

        return calls;
    }

    // ========================= EXTERNAL CALL DETECTION =========================

    /**
     * Detect external HTTP calls (RestTemplate, WebClient, Feign).
     * Flags the call with client type, HTTP method, and URL/path.
     * Resolution of target service happens later.
     */
    private List<ExternalCallInfo> extractExternalCalls(CtMethod<?> method,
                                                        Map<String, String> properties,
                                                        Map<String, String> valueFieldMapping) {
        List<ExternalCallInfo> externalCalls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> feignClients = findFeignClientTypes(method.getFactory().getModel());

        method.getElements(e -> e instanceof CtInvocation).forEach(element -> {
            CtInvocation<?> invocation = (CtInvocation<?>) element;
            CtExecutableReference<?> execRef = invocation.getExecutable();

            String declaringType = execRef.getDeclaringType() != null
                    ? execRef.getDeclaringType().getQualifiedName() : "";
            String methodName = execRef.getSimpleName();

            ExternalCallInfo info = null;

            if (isRestTemplateCall(invocation, declaringType, methodName)) {
                info = buildRestTemplateCall(invocation, declaringType, methodName,
                        properties, valueFieldMapping, method.getDeclaringType());
            } else if (isWebClientCall(invocation, declaringType, methodName)) {
                info = buildWebClientCall(invocation, declaringType, methodName,
                        properties, valueFieldMapping, method.getDeclaringType());
            } else if (isFeignClientCall(declaringType, feignClients)) {
                info = buildFeignCall(invocation, execRef, declaringType);
            }

            if (info != null) {
                String key = info.getClientType() + ":" + info.getUrl() + ":" + info.getHttpMethod();
                if (seen.add(key)) {
                    externalCalls.add(info);
                }
            }
        });

        return externalCalls;
    }

    private boolean isRestTemplateCall(CtInvocation<?> invocation, String declaringType, String methodName) {
        if (!REST_TEMPLATE_METHODS.contains(methodName)) return false;

        if (declaringType.endsWith("RestTemplate")) return true;

        CtExpression<?> target = invocation.getTarget();
        if (target != null && target.getType() != null) {
            String typeName = target.getType().getQualifiedName();
            if (typeName.endsWith("RestTemplate")) return true;
        }

        // Fallback: check variable name
        if (target != null) {
            String targetText = target.toString().toLowerCase(Locale.ROOT);
            return targetText.contains("resttemplate");
        }
        return false;
    }

    private boolean isWebClientCall(CtInvocation<?> invocation, String declaringType, String methodName) {
        if (declaringType.endsWith("WebClient")) {
            return WEBCLIENT_HTTP_METHODS.contains(methodName) || "uri".equals(methodName);
        }
        CtExpression<?> target = invocation.getTarget();
        if (target != null && target.getType() != null) {
            String typeName = target.getType().getQualifiedName();
            return typeName.endsWith("WebClient")
                    || (typeName.contains("WebClient") && WEBCLIENT_HTTP_METHODS.contains(methodName));
        }
        return false;
    }

    private boolean isFeignClientCall(String declaringType, Set<String> feignClients) {
        return feignClients.contains(declaringType);
    }

    private ExternalCallInfo buildRestTemplateCall(CtInvocation<?> invocation, String declaringType,
                                                   String methodName, Map<String, String> properties,
                                                   Map<String, String> valueFieldMapping,
                                                   CtType<?> declaringClass) {
        String httpMethod = resolveRestTemplateHttpMethod(methodName, invocation);
        String url = extractUrlFromArguments(invocation.getArguments(), properties, valueFieldMapping, declaringClass);

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

    private ExternalCallInfo buildWebClientCall(CtInvocation<?> invocation, String declaringType,
                                                String methodName, Map<String, String> properties,
                                                Map<String, String> valueFieldMapping,
                                                CtType<?> declaringClass) {
        String httpMethod = WEBCLIENT_HTTP_METHODS.contains(methodName)
                ? methodName.toUpperCase(Locale.ROOT) : "UNKNOWN";
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

    private ExternalCallInfo buildFeignCall(CtInvocation<?> invocation, CtExecutableReference<?> execRef,
                                            String declaringType) {
        String httpMethod = "REQUEST";
        String url = "<dynamic>";

        try {
            CtExecutable<?> decl = execRef.getExecutableDeclaration();
            if (decl instanceof CtMethod) {
                CtMethod<?> feignMethod = (CtMethod<?>) decl;
                String basePath = extractBasePath(feignMethod.getDeclaringType());
                for (CtAnnotation<?> ann : feignMethod.getAnnotations()) {
                    String annName = ann.getAnnotationType().getSimpleName();
                    if (MAPPING_ANNOTATIONS.contains(annName)) {
                        httpMethod = ANNOTATION_TO_HTTP_METHOD.getOrDefault(annName, "REQUEST");
                        url = extractPath(ann, basePath);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve Feign method: {}", execRef.getSimpleName());
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

    private String resolveRestTemplateHttpMethod(String methodName, CtInvocation<?> invocation) {
        if ("exchange".equals(methodName)) {
            String httpMethod = extractHttpMethodFromArgs(invocation.getArguments());
            return httpMethod != null ? httpMethod : "REQUEST";
        }
        if (methodName.startsWith("get")) return "GET";
        if (methodName.startsWith("post")) return "POST";
        if ("put".equals(methodName)) return "PUT";
        if ("delete".equals(methodName)) return "DELETE";
        return "REQUEST";
    }

    private String extractHttpMethodFromArgs(List<? extends CtExpression<?>> arguments) {
        for (CtExpression<?> arg : arguments) {
            if (arg instanceof CtLiteral) {
                Object value = ((CtLiteral<?>) arg).getValue();
                if (value instanceof String) return value.toString();
            }
            String text = arg.toString();
            if (text != null && text.contains("HttpMethod")) {
                return text.replace("HttpMethod.", "");
            }
        }
        return null;
    }

    // ========================= URL EXTRACTION =========================

    private String extractUrlFromArguments(List<? extends CtExpression<?>> arguments,
                                           Map<String, String> properties,
                                           Map<String, String> valueFieldMapping,
                                           CtType<?> declaringClass) {
        for (CtExpression<?> arg : arguments) {
            String extracted = extractStringFromExpression(arg, properties, valueFieldMapping, declaringClass);
            if (extracted != null && !extracted.isBlank()) {
                return normalizeUrl(stripDynamicTokens(extracted));
            }
        }
        return "<dynamic>";
    }

    private String extractUrlFromWebClientChain(CtInvocation<?> invocation,
                                                Map<String, String> properties,
                                                Map<String, String> valueFieldMapping,
                                                CtType<?> declaringClass) {
        CtExpression<?> target = invocation.getTarget();
        while (target instanceof CtInvocation) {
            CtInvocation<?> targetInvocation = (CtInvocation<?>) target;
            if ("uri".equals(targetInvocation.getExecutable().getSimpleName())) {
                return extractUrlFromArguments(
                        targetInvocation.getArguments(), properties, valueFieldMapping, declaringClass);
            }
            target = targetInvocation.getTarget();
        }
        return "<dynamic>";
    }

    // ========================= STRING EXPRESSION RESOLUTION =========================

    /**
     * Extract a string value from a Spoon expression.
     * Handles: string literals, field references, static constants, string concatenation.
     */
    private String extractStringFromExpression(CtExpression<?> expression,
                                               Map<String, String> properties,
                                               Map<String, String> valueFieldMapping,
                                               CtType<?> declaringClass) {
        if (expression == null) return null;

        // String literal: "http://example.com/api"
        if (expression instanceof CtLiteral) {
            Object value = ((CtLiteral<?>) expression).getValue();
            if (value instanceof String) return (String) value;
        }

        // Field reference: marksTopic, TOPIC_CONSTANT, etc.
        if (expression instanceof CtFieldRead) {
            return resolveFieldRead((CtFieldRead<?>) expression, properties, valueFieldMapping);
        }

        // String concatenation: baseUrl + "/api/users"
        if (expression instanceof CtBinaryOperator) {
            CtBinaryOperator<?> binary = (CtBinaryOperator<?>) expression;
            if (binary.getKind() == BinaryOperatorKind.PLUS) {
                String left = extractStringFromExpression(
                        binary.getLeftHandOperand(), properties, valueFieldMapping, declaringClass);
                String right = extractStringFromExpression(
                        binary.getRightHandOperand(), properties, valueFieldMapping, declaringClass);
                return joinUrlParts(left, right);
            }
        }

        // Fallback: try to resolve from expression text (field name reference)
        String text = expression.toString();
        if (text != null && !text.isBlank()) {
            String resolved = resolveFieldByName(text.trim(), declaringClass, properties, valueFieldMapping);
            if (resolved != null) return resolved;
            return "<dynamic>";
        }
        return null;
    }

    private String resolveFieldRead(CtFieldRead<?> fieldRead,
                                    Map<String, String> properties,
                                    Map<String, String> valueFieldMapping) {
        try {
            CtFieldReference<?> fieldRef = fieldRead.getVariable();
            if (fieldRef == null) return null;

            String fieldName = fieldRef.getSimpleName();
            CtTypeReference<?> declaringType = fieldRef.getDeclaringType();

            if (declaringType != null) {
                String qualifiedName = declaringType.getQualifiedName() + "." + fieldName;

                // Try exact match in value field mapping
                String resolved = valueFieldMapping.get(qualifiedName);
                if (resolved != null && !resolved.startsWith("${")) return resolved;

                // Try suffix match (field name only)
                for (Map.Entry<String, String> entry : valueFieldMapping.entrySet()) {
                    if (entry.getKey().endsWith("." + fieldName)) return entry.getValue();
                }

                // Try resolving static constant directly from AST
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
                    log.debug("Could not resolve field declaration: {}", qualifiedName);
                }
            }
        } catch (Exception e) {
            log.debug("Error resolving field read: {}", e.getMessage());
        }
        return null;
    }

    private String resolveFieldByName(String fieldName, CtType<?> declaringClass,
                                      Map<String, String> properties,
                                      Map<String, String> valueFieldMapping) {
        // Try with declaring class qualifier
        if (declaringClass != null) {
            String qualified = declaringClass.getQualifiedName() + "." + fieldName;
            String resolved = valueFieldMapping.get(qualified);
            if (resolved != null && !resolved.startsWith("${")) return resolved;

            // Try resolving the placeholder value
            if (resolved != null && propertyResolver.hasPlaceholders(resolved)) {
                String fullyResolved = propertyResolver.resolveProperty(resolved, properties);
                if (!fullyResolved.startsWith("${")) return fullyResolved;
            }
        }

        // Try without qualifier
        String resolved = valueFieldMapping.get(fieldName);
        if (resolved != null && !resolved.startsWith("${")) return resolved;

        // Try suffix match
        for (Map.Entry<String, String> entry : valueFieldMapping.entrySet()) {
            if (entry.getKey().endsWith("." + fieldName)) return entry.getValue();
        }

        return null;
    }

    private String joinUrlParts(String left, String right) {
        String safeLeft = stripDynamicTokens(left);
        String safeRight = stripDynamicTokens(right);

        if (safeLeft == null || safeLeft.isEmpty()) return safeRight == null ? "" : safeRight;
        if (safeRight == null || safeRight.isEmpty()) return safeLeft;
        return safeLeft + safeRight;
    }

    private String normalizeUrl(String url) {
        if (url == null) return "";
        String normalized = stripDynamicTokens(url);
        if (normalized == null || normalized.isEmpty()) return "";

        // Collapse multiple slashes after removing dynamic tokens
        normalized = normalized.replaceAll("/{2,}", "/");

        if (normalized.startsWith("<dynamic>/")) return normalized.substring("<dynamic>".length());
        return normalized;
    }

    private String stripDynamicTokens(String value) {
        if (value == null) return null;
        return value.replace("<dynamic>", "{dynamic}");
    }

    // ========================= VALUE FIELD MAPPING =========================

    /**
     * Build a mapping of field names to resolved values.
     * Captures @Value annotated fields and static final String constants.
     */
    private Map<String, String> buildValueFieldMapping(CtModel model, String basePackage,
                                                       Map<String, String> properties) {
        Map<String, String> fieldMapping = new HashMap<>();

        model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(type -> matchesPackage(type, basePackage))
                .forEach(ctClass -> {
                    for (CtField<?> field : ctClass.getFields()) {
                        // @Value annotated fields
                        captureValueAnnotatedField(field, ctClass, properties, fieldMapping);

                        // Static final String constants
                        captureStaticConstant(field, ctClass, properties, fieldMapping);
                    }
                });

        log.info("Built value field mapping with {} entries", fieldMapping.size());
        return fieldMapping;
    }

    private void captureValueAnnotatedField(CtField<?> field, CtType<?> ctClass,
                                            Map<String, String> properties,
                                            Map<String, String> fieldMapping) {
        for (CtAnnotation<?> ann : field.getAnnotations()) {
            if (!"Value".equals(ann.getAnnotationType().getSimpleName())) continue;
            try {
                Object valueObj = ann.getValue("value");
                if (valueObj == null) continue;

                String placeholder = valueObj.toString().replaceAll("^\"|\"$", "");
                String resolvedValue = propertyResolver.resolveProperty(placeholder, properties);
                String key = ctClass.getQualifiedName() + "." + field.getSimpleName();
                fieldMapping.put(key, resolvedValue);
            } catch (Exception e) {
                log.debug("Could not resolve @Value for {}.{}: {}",
                        ctClass.getQualifiedName(), field.getSimpleName(), e.getMessage());
            }
        }
    }

    private void captureStaticConstant(CtField<?> field, CtType<?> ctClass,
                                       Map<String, String> properties,
                                       Map<String, String> fieldMapping) {
        try {
            if (!field.isStatic() || !field.isFinal()) return;
            if (field.getType() == null || !"String".equals(field.getType().getSimpleName())) return;

            CtExpression<?> defaultExpr = field.getDefaultExpression();
            if (!(defaultExpr instanceof CtLiteral)) return;

            Object literalValue = ((CtLiteral<?>) defaultExpr).getValue();
            if (!(literalValue instanceof String)) return;

            String value = (String) literalValue;
            if (value.contains("${")) {
                value = propertyResolver.resolveProperty(value, properties);
            }

            String key = ctClass.getQualifiedName() + "." + field.getSimpleName();
            fieldMapping.put(key, value);
        } catch (Exception e) {
            log.debug("Could not extract static constant for {}.{}: {}",
                    ctClass.getQualifiedName(), field.getSimpleName(), e.getMessage());
        }
    }

    // ========================= DATABASE OPERATIONS =========================

    private DatabaseOperationInfo extractDatabaseOperations(CtType<?> repositoryType, CtModel model,
                                                            String dbType) {
        try {
            String entityClassName = extractEntityClassName(repositoryType);
            if (entityClassName == null) return null;

            // Find entity class in model
            CtType<?> entityClass = model.getAllTypes().stream()
                    .filter(t -> t.getQualifiedName().equals(entityClassName)
                            || t.getSimpleName().equals(entityClassName))
                    .findFirst()
                    .orElse(null);

            if (entityClass == null) {
                log.debug("Could not find entity class: {}", entityClassName);
                return null;
            }

            String tableName = extractTableName(entityClass, entityClassName);
            String tableSource = determineTableSource(entityClass);
            List<String> operations = inferDatabaseOperations(repositoryType);

            return DatabaseOperationInfo.builder()
                    .className(repositoryType.getSimpleName())
                    .packageName(repositoryType.getPackage().getQualifiedName())
                    .entityClass(entityClassName)
                    .entitySimpleName(extractSimpleName(entityClassName))
                    .tableName(tableName)
                    .tableSource(tableSource)
                    .databaseType(dbType)
                    .operations(operations)
                    .line(createLineRange(repositoryType))
                    .build();
        } catch (Exception e) {
            log.debug("Error extracting database operations: {}", e.getMessage());
            return null;
        }
    }

    private String extractEntityClassName(CtType<?> repositoryType) {
        if (!repositoryType.isInterface()) return null;

        for (CtTypeReference<?> superInterface : repositoryType.getSuperInterfaces()) {
            if (!superInterface.getQualifiedName().contains("Repository")) continue;

            try {
                String typeString = superInterface.toString();
                if (!typeString.contains("<")) continue;

                int start = typeString.indexOf("<") + 1;
                int comma = typeString.indexOf(",");
                int end = comma > start ? comma : typeString.indexOf(">");
                if (end > start) return typeString.substring(start, end).trim();
            } catch (Exception e) {
                log.debug("Could not extract generic type from: {}", superInterface);
            }
        }
        return null;
    }

    private String extractTableName(CtType<?> entityClass, String entityClassName) {
        // Try @Table annotation
        for (CtAnnotation<?> ann : entityClass.getAnnotations()) {
            String annName = ann.getAnnotationType().getSimpleName();
            if ("Table".equals(annName)) {
                Object nameValue = ann.getValues().get("name");
                if (nameValue != null) return nameValue.toString().replaceAll("[\"\\s]", "");
            }
            if ("Document".equals(annName)) {
                Object collectionValue = ann.getValues().get("collection");
                if (collectionValue != null) return collectionValue.toString().replaceAll("[\"\\s]", "");
            }
        }

        // Fallback: derive from class name (CamelCase -> snake_case)
        return camelCaseToSnakeCase(extractSimpleName(entityClassName));
    }

    private String determineTableSource(CtType<?> entityClass) {
        for (CtAnnotation<?> ann : entityClass.getAnnotations()) {
            String annName = ann.getAnnotationType().getSimpleName();
            if ("Table".equals(annName)) return "@Table";
            if ("Document".equals(annName)) return "@Document";
        }
        return "derived_from_class_name";
    }

    private List<String> inferDatabaseOperations(CtType<?> repositoryType) {
        Set<String> operations = new TreeSet<>();

        for (CtMethod<?> method : repositoryType.getMethods()) {
            String name = method.getSimpleName().toLowerCase(Locale.ROOT);

            if (name.contains("find") || name.contains("get") || name.contains("read") || name.contains("query"))
                operations.add("READ");
            if (name.contains("save") || name.contains("create") || name.contains("insert") || name.contains("persist"))
                operations.add("WRITE");
            if (name.contains("update") || name.contains("merge"))
                operations.add("UPDATE");
            if (name.contains("delete") || name.contains("remove"))
                operations.add("DELETE");
        }

        // Default operations if no custom methods
        if (operations.isEmpty()) {
            operations.addAll(List.of("READ", "WRITE", "DELETE"));
        }

        return new ArrayList<>(operations);
    }

    // ========================= REQUEST/RESPONSE EXTRACTION =========================

    private RequestBodyInfo extractRequestBody(CtMethod<?> method) {
        try {
            for (CtParameter<?> parameter : method.getParameters()) {
                boolean hasRequestBody = parameter.getAnnotations().stream()
                        .anyMatch(a -> "RequestBody".equals(a.getAnnotationType().getSimpleName()));

                if (!hasRequestBody) continue;

                CtTypeReference<?> paramType = parameter.getType();
                String typeName = paramType.getQualifiedName();
                boolean isCollection = typeName.contains("List") || typeName.contains("Collection");

                List<String> fields = extractTypeFields(paramType);

                return RequestBodyInfo.builder()
                        .type(typeName)
                        .simpleTypeName(paramType.getSimpleName())
                        .fields(fields)
                        .isCollection(isCollection)
                        .isWrapper(true)
                        .build();
            }
        } catch (Exception e) {
            log.debug("Error extracting request body: {}", e.getMessage());
        }
        return null;
    }

    private ResponseTypeInfo extractResponseType(CtMethod<?> method, String httpMethod) {
        try {
            CtTypeReference<?> returnType = method.getType();

            if (returnType == null || "void".equals(returnType.getSimpleName())) {
                return ResponseTypeInfo.builder().statusCode(204).isCollection(false).build();
            }

            String typeName = returnType.getQualifiedName();
            boolean isCollection = typeName.contains("List") || typeName.contains("Collection")
                    || typeName.contains("ResponseEntity<List");

            int statusCode = "POST".equals(httpMethod) ? 201 : 200;

            List<String> fields = extractTypeFields(returnType);

            return ResponseTypeInfo.builder()
                    .type(typeName)
                    .simpleTypeName(returnType.getSimpleName())
                    .fields(fields)
                    .isCollection(isCollection)
                    .statusCode(statusCode)
                    .build();
        } catch (Exception e) {
            log.debug("Error extracting response type: {}", e.getMessage());
        }
        return ResponseTypeInfo.builder().statusCode(200).isCollection(false).build();
    }

    private List<String> extractTypeFields(CtTypeReference<?> typeRef) {
        List<String> fields = new ArrayList<>();
        try {
            CtType<?> typeDecl = typeRef.getTypeDeclaration();
            if (typeDecl != null && typeDecl.isClass()) {
                typeDecl.getFields().stream()
                        .filter(f -> !f.isPrivate())
                        .map(CtField::getSimpleName)
                        .forEach(fields::add);
            }
        } catch (Exception e) {
            log.debug("Could not extract fields for type: {}", typeRef.getQualifiedName());
        }
        return fields;
    }

    // ========================= CALL COLLECTION HELPERS =========================

    private List<ExternalCallInfo> collectExternalCallsFromCalls(List<MethodCall> calls) {
        List<ExternalCallInfo> result = new ArrayList<>();
        if (calls == null) return result;
        for (MethodCall call : calls) {
            if (call.getExternalCalls() != null) result.addAll(call.getExternalCalls());
            if (call.getCalls() != null) result.addAll(collectExternalCallsFromCalls(call.getCalls()));
        }
        return result;
    }

    private List<KafkaCallInfo> collectKafkaCallsFromCalls(List<MethodCall> calls) {
        List<KafkaCallInfo> result = new ArrayList<>();
        if (calls == null) return result;
        for (MethodCall call : calls) {
            if (call.getKafkaCalls() != null) result.addAll(call.getKafkaCalls());
            if (call.getCalls() != null) result.addAll(collectKafkaCallsFromCalls(call.getCalls()));
        }
        return result;
    }

    private List<ExternalCallInfo> mergeExternalCalls(List<ExternalCallInfo> direct,
                                                      List<ExternalCallInfo> nested) {
        Map<String, ExternalCallInfo> merged = new LinkedHashMap<>();
        if (direct != null) {
            for (ExternalCallInfo call : direct) {
                String key = call.getClientType() + ":" + call.getUrl() + ":" + call.getHttpMethod();
                merged.putIfAbsent(key, call);
            }
        }
        if (nested != null) {
            for (ExternalCallInfo call : nested) {
                String key = call.getClientType() + ":" + call.getUrl() + ":" + call.getHttpMethod();
                merged.putIfAbsent(key, call);
            }
        }
        return new ArrayList<>(merged.values());
    }

    // ========================= FEIGN CLIENT DETECTION =========================

    private Set<String> findFeignClientTypes(CtModel model) {
        return model.getAllTypes().stream()
                .filter(CtType::isInterface)
                .filter(t -> t.getAnnotations().stream()
                        .anyMatch(a -> "FeignClient".equals(a.getAnnotationType().getSimpleName())))
                .map(CtType::getQualifiedName)
                .collect(Collectors.toSet());
    }

    // ========================= TYPE CHECKING =========================

    private boolean isSpringBootApplication(CtType<?> ctType) {
        return ctType.getAnnotations().stream()
                .anyMatch(a -> "SpringBootApplication".equals(a.getAnnotationType().getSimpleName()));
    }

    private boolean isController(CtType<?> ctType) {
        return ctType.getAnnotations().stream()
                .anyMatch(a -> {
                    String name = a.getAnnotationType().getSimpleName();
                    return "RestController".equals(name) || "Controller".equals(name);
                });
    }

    private boolean isService(CtType<?> ctType) {
        return ctType.getAnnotations().stream()
                .anyMatch(a -> "Service".equals(a.getAnnotationType().getSimpleName()));
    }

    private boolean isRepository(CtType<?> ctType) {
        boolean hasAnnotation = ctType.getAnnotations().stream()
                .anyMatch(a -> "Repository".equals(a.getAnnotationType().getSimpleName()));

        boolean extendsRepository = ctType.isInterface()
                && ctType.getSuperInterfaces().stream()
                .anyMatch(si -> si.getSimpleName().endsWith("Repository"));

        return hasAnnotation || extendsRepository;
    }

    private boolean isConfiguration(CtType<?> ctType) {
        return ctType.getAnnotations().stream()
                .anyMatch(a -> {
                    String name = a.getAnnotationType().getSimpleName();
                    return "Configuration".equals(name) || "SpringBootApplication".equals(name);
                });
    }

    private boolean matchesPackage(CtType<?> ctType, String basePackage) {
        if (basePackage == null || basePackage.isEmpty()) return true;
        return ctType.getPackage().getQualifiedName().startsWith(basePackage);
    }

    // ========================= ANNOTATION/PATH HELPERS =========================

    private String extractBasePath(CtType<?> controllerClass) {
        return controllerClass.getAnnotations().stream()
                .filter(a -> "RequestMapping".equals(a.getAnnotationType().getSimpleName()))
                .findFirst()
                .map(a -> {
                    Object value = a.getValues().get("value");
                    if (value == null) return "";
                    return value.toString().replaceAll("[\"\\[\\]]", "");
                })
                .orElse("");
    }

    private String extractPath(CtAnnotation<?> annotation, String basePath) {
        Object value = annotation.getValues().get("value");
        if (value == null) value = annotation.getValues().get("path");

        String path = "";
        if (value != null) {
            path = value.toString().replaceAll("[\"\\[\\]]", "");
        }

        if (basePath.isEmpty()) return path.isEmpty() ? "/" : path;
        return basePath + (path.isEmpty() ? "" : path);
    }

    private String extractExtendsClass(CtType<?> ctType) {
        if (!ctType.isInterface()) return "None";
        return ctType.getSuperInterfaces().stream()
                .map(CtTypeReference::getQualifiedName)
                .filter(name -> name.contains("Repository"))
                .findFirst()
                .orElse("None");
    }

    private String determineRepositoryType(String extendsClass) {
        if (extendsClass.contains("ReactiveMongoRepository")) return "Reactive MongoDB";
        if (extendsClass.contains("ReactiveCrudRepository")) return "Reactive JPA";
        if (extendsClass.contains("MongoRepository")) return "MongoDB";
        if (extendsClass.contains("JpaRepository") || extendsClass.contains("CrudRepository")) return "JPA";
        return "Custom";
    }

    // ========================= FILTERING =========================

    private boolean isJavaStandardLibrary(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jakarta.")
                || className.startsWith("org.springframework.")
                || className.startsWith("lombok.");
    }

    private boolean isGetterOrSetter(CtExecutableReference<?> execRef) {
        if (execRef == null) return false;

        String methodName = execRef.getSimpleName();
        CtTypeReference<?> returnType = execRef.getType();
        int paramCount = execRef.getParameters() == null ? 0 : execRef.getParameters().size();

        boolean returnsVoid = returnType != null && "void".equals(returnType.getSimpleName());
        boolean returnsDeclaringType = execRef.getDeclaringType() != null && returnType != null
                && execRef.getDeclaringType().getSimpleName().equals(returnType.getSimpleName());

        boolean isStdGetter = methodName.startsWith("get") && methodName.length() > 3
                && Character.isUpperCase(methodName.charAt(3))
                && paramCount == 0 && !returnsVoid;

        boolean isBooleanGetter = methodName.startsWith("is") && methodName.length() > 2
                && Character.isUpperCase(methodName.charAt(2))
                && paramCount == 0
                && returnType != null
                && ("boolean".equalsIgnoreCase(returnType.getSimpleName())
                    || Boolean.class.getSimpleName().equals(returnType.getSimpleName()));

        boolean isStdSetter = methodName.startsWith("set") && methodName.length() > 3
                && Character.isUpperCase(methodName.charAt(3))
                && paramCount == 1
                && (returnsVoid || returnsDeclaringType);

        if (isStdGetter || isBooleanGetter || isStdSetter) {
            return true;
        }

        return isGetterOrSetter(methodName);
    }

    private boolean isGetterOrSetter(String methodName) {
        if (methodName == null || methodName.isEmpty()) return false;

        if (methodName.startsWith("get") && methodName.length() > 3
                && Character.isUpperCase(methodName.charAt(3))) return true;
        if (methodName.startsWith("is") && methodName.length() > 2
                && Character.isUpperCase(methodName.charAt(2))) return true;
        if (methodName.startsWith("set") && methodName.length() > 3
                && Character.isUpperCase(methodName.charAt(3))) return true;

        switch (methodName) {
            case "builder": case "build": case "toBuilder":
            case "toString": case "hashCode": case "equals": case "canEqual":
            case "of": case "valueOf":
                return true;
            default:
                return false;
        }
    }

    // ========================= UTILITY =========================

    private LineRange createLineRange(CtElement element) {
        try {
            return LineRange.builder()
                    .start(element.getPosition().getLine())
                    .end(element.getPosition().getEndLine())
                    .build();
        } catch (Exception e) {
            return LineRange.builder().start(0).end(0).build();
        }
    }

    private String extractSimpleName(String fullyQualifiedName) {
        if (fullyQualifiedName == null || fullyQualifiedName.isEmpty()) return "";
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot > 0 ? fullyQualifiedName.substring(lastDot + 1) : fullyQualifiedName;
    }

    private String camelCaseToSnakeCase(String input) {
        if (input == null || input.isEmpty()) return "";
        return input.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
