package com.architecture.memory.orkestify.service.graph.analyzer;

import com.architecture.memory.orkestify.service.AnalyzerConfigurationService;
import com.architecture.memory.orkestify.service.PropertyResolver;
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
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Neo4j-native Spoon code analyzer.
 *
 * Two-pass design:
 *   Pass 1 - COLLECT: Parse all types, extract components, injected dependencies, raw invocations.
 *   Pass 2 - RESOLVE: Build interface->impl map, resolve DI field types, walk call chains with full depth.
 *
 * Key improvements over SpoonCodeAnalyzer:
 *   1. Tracks injected dependencies (constructor + @Autowired fields) to resolve field types
 *   2. Builds complete interface->implementation mapping BEFORE resolving any calls
 *   3. Unlimited depth call tracing with cycle detection (Controller -> Service -> Service -> Repository)
 *   4. Self-call resolution (private method calls within the same class)
 *   5. Proper handling of interface-typed fields (IPaymentService -> PaymentServiceImpl)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class Neo4jSpoonAnalyzer {

    private final PropertyResolver propertyResolver;
    private final AnalyzerConfigurationService configService;


    // HttpURLConnection methods that indicate HTTP calls
    private static final Set<String> HTTP_URL_CONNECTION_METHODS = Set.of(
            "openConnection", "setRequestMethod", "getInputStream", "getOutputStream", "connect"
    );

    // ========================= PUBLIC API =========================

    /**
     * Analyze a repository and return parsed application(s).
     * Each Spring Boot application in the repo becomes a ParsedApplication.
     */
    public List<ParsedApplication> analyze(Path repositoryPath) {
        log.info("[neo4j-analyzer] Starting analysis for: {}", repositoryPath);

        Map<String, String> properties = propertyResolver.loadProperties(repositoryPath);
        log.info("[neo4j-analyzer] Loaded {} configuration properties", properties.size());

        CtModel model = buildSpoonModel(repositoryPath);
        Map<String, String> valueFieldMapping = buildValueFieldMapping(model, "", properties);

        List<CtType<?>> springBootApps = findSpringBootApplications(model);

        if (springBootApps.isEmpty()) {
            log.warn("[neo4j-analyzer] No Spring Boot application found, analyzing all types");
            ParsedApplication app = analyzeAllTypes(model, properties, valueFieldMapping);
            return List.of(app);
        }

        log.info("[neo4j-analyzer] Found {} Spring Boot application(s)", springBootApps.size());
        return springBootApps.stream()
                .map(sbApp -> analyzeSpringBootApp(model, sbApp, properties, valueFieldMapping))
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

    // ========================= APPLICATION-LEVEL ANALYSIS =========================

    private ParsedApplication analyzeSpringBootApp(CtModel model, CtType<?> sbApp,
                                                    Map<String, String> properties,
                                                    Map<String, String> valueFieldMapping) {
        String basePackage = sbApp.getPackage().getQualifiedName();
        log.info("[neo4j-analyzer] Analyzing Spring Boot app: {} (package: {})", sbApp.getSimpleName(), basePackage);

        Map<String, String> scopedValueMapping = buildValueFieldMapping(model, basePackage, properties);
        scopedValueMapping.putAll(valueFieldMapping);

        // PASS 1: Collect all components
        ParsedApplication app = ParsedApplication.builder()
                .mainClassName(sbApp.getSimpleName())
                .mainClassPackage(basePackage)
                .isSpringBoot(true)
                .rootPath(safeGetFilePath(sbApp))
                .lineStart(safeGetLine(sbApp))
                .lineEnd(safeGetEndLine(sbApp))
                .build();

        collectComponents(model, basePackage, properties, scopedValueMapping, app);

        // PASS 2: Resolve interfaces, DI, and call chains
        resolveAll(app, model, basePackage);

        logSummary(app);
        return app;
    }

    private ParsedApplication analyzeAllTypes(CtModel model, Map<String, String> properties,
                                               Map<String, String> valueFieldMapping) {
        ParsedApplication app = ParsedApplication.builder()
                .build();

        collectComponents(model, "", properties, valueFieldMapping, app);
        resolveAll(app, model, "");

        logSummary(app);
        return app;
    }

    private void logSummary(ParsedApplication app) {
        log.info("[neo4j-analyzer] Analysis complete: controllers={}, services={}, repositories={}, configs={}, kafkaListeners={}",
                app.getControllers().size(), app.getServices().size(), app.getRepositories().size(),
                app.getConfigurations().size(), app.getKafkaListeners().size());
        log.info("[neo4j-analyzer] Interface mappings: {}", app.getInterfaceToImplMap().size());
        log.info("[neo4j-analyzer] Component index size: {}", app.getComponentIndex().size());
    }

    // ========================= PASS 1: COLLECTION =========================

    private void collectComponents(CtModel model, String basePackage,
                                    Map<String, String> properties,
                                    Map<String, String> valueFieldMapping,
                                    ParsedApplication app) {
        Set<String> feignClients = findFeignClientTypes(model);

        for (CtType<?> ctType : model.getAllTypes()) {
            if (!matchesPackage(ctType, basePackage)) continue;

            SpringComponentType componentType = classifyComponent(ctType);
            if (componentType == SpringComponentType.UNKNOWN) continue;

            ParsedComponent component = parseComponent(ctType, componentType, model, properties, valueFieldMapping, feignClients);

            // Check if this component has already been processed to avoid duplicates
            if (app.getComponentIndex().containsKey(component.getQualifiedName())) {
                log.debug("[neo4j-analyzer] Skipping duplicate component: {}", component.getQualifiedName());
                continue;
            }

            switch (componentType) {
                case CONTROLLER, REST_CONTROLLER -> app.getControllers().add(component);
                case SERVICE -> app.getServices().add(component);
                case REPOSITORY -> app.getRepositories().add(component);
                case CONFIGURATION -> app.getConfigurations().add(component);
                case KAFKA_LISTENER -> app.getKafkaListeners().add(component);
                default -> { /* skip */ }
            }

            // Index by qualified name
            app.getComponentIndex().put(component.getQualifiedName(), component);
            // Also index by simple name for fallback resolution
            app.getComponentIndex().putIfAbsent(component.getClassName(), component);
        }
    }

    private SpringComponentType classifyComponent(CtType<?> ctType) {
        for (CtAnnotation<?> ann : ctType.getAnnotations()) {
            String name = ann.getAnnotationType().getSimpleName();
            switch (name) {
                case "RestController" -> { return SpringComponentType.REST_CONTROLLER; }
                case "Controller" -> { return SpringComponentType.CONTROLLER; }
                case "Service" -> { return SpringComponentType.SERVICE; }
                case "Repository" -> { return SpringComponentType.REPOSITORY; }
                case "Configuration" -> { return SpringComponentType.CONFIGURATION; }
                case "Component" -> {
                    // Check if it has @KafkaListener methods
                    if (hasKafkaListenerMethods(ctType)) {
                        return SpringComponentType.KAFKA_LISTENER;
                    }
                    return SpringComponentType.COMPONENT;
                }
            }
        }

        // Repository interfaces that extend Spring Data Repository
        if (ctType.isInterface() && ctType.getSuperInterfaces().stream()
                .anyMatch(si -> si.getSimpleName().endsWith("Repository"))) {
            return SpringComponentType.REPOSITORY;
        }

        // Classes with @KafkaListener methods but no specific annotation
        if (ctType.isClass() && hasKafkaListenerMethods(ctType)) {
            return SpringComponentType.KAFKA_LISTENER;
        }

        // Service classes that implement interfaces (tagged @Service)
        if (ctType.isClass()) {
            for (CtAnnotation<?> ann : ctType.getAnnotations()) {
                if ("Service".equals(ann.getAnnotationType().getSimpleName())) {
                    return SpringComponentType.SERVICE;
                }
            }
        }

        return SpringComponentType.UNKNOWN;
    }

    private boolean hasKafkaListenerMethods(CtType<?> ctType) {
        try {
            return ctType.getMethods().stream()
                    .anyMatch(m -> m.getAnnotations().stream()
                            .anyMatch(a -> {
                                String n = a.getAnnotationType().getSimpleName();
                                return "KafkaListener".equals(n) || "KafkaHandler".equals(n);
                            }));
        } catch (Exception e) {
            return false;
        }
    }

    // ========================= COMPONENT PARSING =========================

    private ParsedComponent parseComponent(CtType<?> ctType, SpringComponentType componentType,
                                            CtModel model,
                                            Map<String, String> properties,
                                            Map<String, String> valueFieldMapping,
                                            Set<String> feignClients) {
        ParsedComponent component = ParsedComponent.builder()
                .className(ctType.getSimpleName())
                .qualifiedName(ctType.getQualifiedName())
                .packageName(ctType.getPackage() != null ? ctType.getPackage().getQualifiedName() : "")
                .componentType(componentType)
                .lineStart(safeGetLine(ctType))
                .lineEnd(safeGetEndLine(ctType))
                .build();

        // Extract implemented interfaces
        if (ctType.isClass()) {
            extractImplementedInterfaces(ctType, component);
            extractInjectedDependencies(ctType, component);
        }

        // Parse methods based on component type
        switch (componentType) {
            case CONTROLLER, REST_CONTROLLER -> {
                component.setBaseUrl(extractBasePath(ctType));
                parseControllerMethods(ctType, component, properties, valueFieldMapping, feignClients);
            }
            case SERVICE, COMPONENT -> {
                parseServiceMethods(ctType, component, properties, valueFieldMapping, feignClients);
            }
            case REPOSITORY -> {
                parseRepositoryInfo(ctType, component, model);
                parseRepositoryMethods(ctType, component);
            }
            case CONFIGURATION -> {
                parseBeans(ctType, component);
            }
            case KAFKA_LISTENER -> {
                parseKafkaListenerComponent(ctType, component, properties, valueFieldMapping, feignClients);
            }
        }

        return component;
    }

    // ========================= INTERFACE EXTRACTION =========================

    private void extractImplementedInterfaces(CtType<?> ctType, ParsedComponent component) {
        try {
            Set<CtTypeReference<?>> superInterfaces = ctType.getSuperInterfaces();
            if (superInterfaces == null) return;

            for (CtTypeReference<?> iface : superInterfaces) {
                String simpleName = iface.getSimpleName();
                String qualifiedName = iface.getQualifiedName();
                if (simpleName != null && !simpleName.isEmpty()) {
                    component.getImplementedInterfaces().add(simpleName);
                }
                if (qualifiedName != null && !qualifiedName.isEmpty() && !qualifiedName.equals(simpleName)) {
                    component.getImplementedInterfaces().add(qualifiedName);
                }
            }
        } catch (Exception e) {
            log.debug("[neo4j-analyzer] Could not extract interfaces for: {}", ctType.getSimpleName());
        }
    }

    // ========================= DEPENDENCY INJECTION EXTRACTION =========================

    private void extractInjectedDependencies(CtType<?> ctType, ParsedComponent component) {
        // 1. Constructor injection: if class uses @RequiredArgsConstructor or has a constructor with params
        boolean usesLombokConstructor = ctType.getAnnotations().stream()
                .anyMatch(a -> {
                    String n = a.getAnnotationType().getSimpleName();
                    return "RequiredArgsConstructor".equals(n) || "AllArgsConstructor".equals(n);
                });

        if (usesLombokConstructor) {
            // With Lombok @RequiredArgsConstructor, all final fields are constructor-injected
            for (CtField<?> field : ctType.getFields()) {
                if (field.isFinal() && !field.isStatic()) {
                    addInjectedDependency(component, field, InjectedDependency.InjectionType.CONSTRUCTOR);
                }
            }
        }

        // 2. Explicit constructor injection
        if (ctType instanceof CtClass<?> ctClass) {
            for (CtConstructor<?> constructor : ctClass.getConstructors()) {
                if (constructor.getParameters().isEmpty()) continue;
                for (CtParameter<?> param : constructor.getParameters()) {
                    // Find the field that matches this constructor param
                    CtField<?> matchingField = findFieldByType(ctType, param.getType());
                    if (matchingField != null) {
                        addInjectedDependency(component, matchingField, InjectedDependency.InjectionType.CONSTRUCTOR);
                    }
                }
            }
        }

        // 3. @Autowired field injection
        for (CtField<?> field : ctType.getFields()) {
            boolean isAutowired = field.getAnnotations().stream()
                    .anyMatch(a -> {
                        String n = a.getAnnotationType().getSimpleName();
                        return "Autowired".equals(n) || "Inject".equals(n) || "Resource".equals(n);
                    });
            if (isAutowired) {
                addInjectedDependency(component, field, InjectedDependency.InjectionType.FIELD_AUTOWIRED);
            }
        }
    }

    private void addInjectedDependency(ParsedComponent component, CtField<?> field,
                                        InjectedDependency.InjectionType injectionType) {
        try {
            CtTypeReference<?> fieldType = field.getType();
            if (fieldType == null) return;

            String simpleName = fieldType.getSimpleName();
            String qualifiedName = fieldType.getQualifiedName();

            // Skip primitive types, collections, and standard library types
            if (isStandardType(qualifiedName)) return;

            component.getInjectedDependencies().put(field.getSimpleName(),
                    InjectedDependency.builder()
                            .fieldName(field.getSimpleName())
                            .declaredTypeSimple(simpleName)
                            .declaredTypeQualified(qualifiedName)
                            .injectionType(injectionType)
                            .build());
        } catch (Exception e) {
            log.debug("[neo4j-analyzer] Could not extract dependency for field: {}", field.getSimpleName());
        }
    }

    private CtField<?> findFieldByType(CtType<?> ctType, CtTypeReference<?> paramType) {
        try {
            for (CtField<?> field : ctType.getFields()) {
                if (field.getType() != null && field.getType().getQualifiedName().equals(paramType.getQualifiedName())) {
                    return field;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    // ========================= CONTROLLER METHOD PARSING =========================

    private void parseControllerMethods(CtType<?> ctType, ParsedComponent component,
                                         Map<String, String> properties,
                                         Map<String, String> valueFieldMapping,
                                         Set<String> feignClients) {
        String basePath = component.getBaseUrl();
        if (basePath == null) basePath = "";

        for (CtMethod<?> method : ctType.getMethods()) {
            for (CtAnnotation<?> ann : method.getAnnotations()) {
                String annName = ann.getAnnotationType().getSimpleName();
                if (!configService.getMappingAnnotations().contains(annName)) continue;

                String httpMethod = configService.getAnnotationToHttpMethod().getOrDefault(annName, "REQUEST");
                String path = extractPath(ann, basePath);

                ParsedMethod pm = ParsedMethod.builder()
                        .methodName(method.getSimpleName())
                        .signature(method.getSignature())
                        .lineStart(safeGetLine(method))
                        .lineEnd(safeGetEndLine(method))
                        .isPublic(method.isPublic())
                        .isPrivate(method.isPrivate())
                        .httpMethod(httpMethod)
                        .path(path)
                        .requestBodyType(extractRequestBodyType(method))
                        .responseType(extractResponseType(method))
                        .build();

                // Collect raw invocations from method body
                collectRawInvocations(method, pm, ctType, properties, valueFieldMapping, feignClients);

                component.getMethods().add(pm);
            }
        }
    }

    // ========================= SERVICE METHOD PARSING =========================

    private void parseServiceMethods(CtType<?> ctType, ParsedComponent component,
                                      Map<String, String> properties,
                                      Map<String, String> valueFieldMapping,
                                      Set<String> feignClients) {
        for (CtMethod<?> method : ctType.getMethods()) {
            ParsedMethod pm = ParsedMethod.builder()
                    .methodName(method.getSimpleName())
                    .signature(method.getSignature())
                    .lineStart(safeGetLine(method))
                    .lineEnd(safeGetEndLine(method))
                    .isPublic(method.isPublic())
                    .isPrivate(method.isPrivate())
                    .build();

            collectRawInvocations(method, pm, ctType, properties, valueFieldMapping, feignClients);
            component.getMethods().add(pm);
        }
    }

    // ========================= REPOSITORY PARSING =========================

    private void parseRepositoryInfo(CtType<?> ctType, ParsedComponent component, CtModel model) {
        // Extract extends class (Repository type)
        String extendsClass = "None";
        if (ctType.isInterface()) {
            extendsClass = ctType.getSuperInterfaces().stream()
                    .map(CtTypeReference::getQualifiedName)
                    .filter(name -> name.contains("Repository"))
                    .findFirst()
                    .orElse("None");
        }
        component.setExtendsClass(extendsClass);
        component.setRepositoryType(determineRepositoryType(extendsClass));

        // Extract entity class and table info
        String entityClassName = extractEntityClassName(ctType);
        component.setEntityClassName(entityClassName);

        if (entityClassName != null) {
            CtType<?> entityClass = model.getAllTypes().stream()
                    .filter(t -> t.getQualifiedName().equals(entityClassName)
                            || t.getSimpleName().equals(entityClassName))
                    .findFirst()
                    .orElse(null);

            if (entityClass != null) {
                component.setTableName(extractTableName(entityClass, entityClassName));
                component.setTableSource(determineTableSource(entityClass));
            }
            component.setDatabaseType(component.getRepositoryType());
            component.setDatabaseOperations(inferDatabaseOperations(ctType));
        }
    }

    private void parseRepositoryMethods(CtType<?> ctType, ParsedComponent component) {
        for (CtMethod<?> method : ctType.getMethods()) {
            ParsedMethod pm = ParsedMethod.builder()
                    .methodName(method.getSimpleName())
                    .signature(method.getSignature())
                    .lineStart(safeGetLine(method))
                    .lineEnd(safeGetEndLine(method))
                    .isPublic(!method.isPrivate())
                    .isPrivate(method.isPrivate())
                    .build();
            component.getMethods().add(pm);
        }
    }

    // ========================= CONFIGURATION PARSING =========================

    private void parseBeans(CtType<?> ctType, ParsedComponent component) {
        for (CtMethod<?> method : ctType.getMethods()) {
            boolean isBean = method.getAnnotations().stream()
                    .anyMatch(a -> "Bean".equals(a.getAnnotationType().getSimpleName()));
            if (isBean) {
                component.getBeans().add(ParsedBean.builder()
                        .beanName(method.getSimpleName())
                        .methodName(method.getSimpleName())
                        .returnType(method.getType() != null ? method.getType().getQualifiedName() : "")
                        .lineStart(safeGetLine(method))
                        .lineEnd(safeGetEndLine(method))
                        .build());
            }
        }
    }

    // ========================= KAFKA LISTENER PARSING =========================

    private void parseKafkaListenerComponent(CtType<?> ctType, ParsedComponent component,
                                              Map<String, String> properties,
                                              Map<String, String> valueFieldMapping,
                                              Set<String> feignClients) {
        // Also extract injected dependencies for kafka listeners
        if (ctType.isClass()) {
            extractInjectedDependencies(ctType, component);
        }

        for (CtMethod<?> method : ctType.getMethods()) {
            Optional<CtAnnotation<?>> kafkaAnn = method.getAnnotations().stream()
                    .filter(a -> {
                        String n = a.getAnnotationType().getSimpleName();
                        return "KafkaListener".equals(n) || "KafkaHandler".equals(n);
                    })
                    .findFirst();

            if (kafkaAnn.isPresent()) {
                CtAnnotation<?> ann = kafkaAnn.get();
                String topic = extractKafkaTopic(ann, properties, valueFieldMapping);
                String groupId = extractKafkaGroupId(ann, properties, valueFieldMapping);

                ParsedKafkaListenerMethod klm = ParsedKafkaListenerMethod.builder()
                        .methodName(method.getSimpleName())
                        .signature(method.getSignature())
                        .topic(topic)
                        .groupId(groupId)
                        .lineStart(safeGetLine(method))
                        .lineEnd(safeGetEndLine(method))
                        .build();

                // Collect invocations from listener method body
                collectRawInvocationsForKafkaListener(method, klm, ctType, properties, valueFieldMapping, feignClients);

                component.getKafkaListenerMethods().add(klm);
            } else {
                // Non-listener methods in the kafka listener class - parse as service methods
                ParsedMethod pm = ParsedMethod.builder()
                        .methodName(method.getSimpleName())
                        .signature(method.getSignature())
                        .lineStart(safeGetLine(method))
                        .lineEnd(safeGetEndLine(method))
                        .isPublic(method.isPublic())
                        .isPrivate(method.isPrivate())
                        .build();
                collectRawInvocations(method, pm, ctType, properties, valueFieldMapping, feignClients);
                component.getMethods().add(pm);
            }
        }
    }

    // ========================= RAW INVOCATION COLLECTION =========================

    /**
     * Collect all method invocations from a method body as RawInvocation objects.
     * These are NOT resolved yet - resolution happens in Pass 2.
     *
     * This is the key difference from the old parser: we capture the field name and
     * declared type so we can resolve interface -> implementation later.
     */
    private void collectRawInvocations(CtMethod<?> method, ParsedMethod pm,
                                        CtType<?> declaringClass,
                                        Map<String, String> properties,
                                        Map<String, String> valueFieldMapping,
                                        Set<String> feignClients) {
        Set<String> seen = new HashSet<>();

        method.getElements(e -> e instanceof CtInvocation).forEach(element -> {
            CtInvocation<?> invocation = (CtInvocation<?>) element;
            CtExecutableReference<?> execRef = invocation.getExecutable();
            if (execRef == null) return;

            // Handle cases where execRef.getDeclaringType() is null (e.g., JPA repository proxy calls)
            String declaredType = null;
            if (execRef.getDeclaringType() != null) {
                declaredType = execRef.getDeclaringType().getQualifiedName();
            } else {
                // Try to get the type from the target field (for repository calls like repository.save())
                CtExpression<?> target = invocation.getTarget();
                if (target != null) {
                    try {
                        if (target.getType() != null && target.getType().getQualifiedName() != null) {
                            declaredType = target.getType().getQualifiedName();
                        }
                    } catch (Exception e) {
                        // Ignore and skip this invocation
                    }
                }
                if (declaredType == null) return;
            }

            String methodName = execRef.getSimpleName();

            // Skip standard library, getters/setters, builder patterns
          /*  if (isStandardType(declaredType) || isGetterOrSetter(methodName)) return;*/
            if (isStandardType(declaredType)) return;

            String key = declaredType + "#" + methodName;
            if (!seen.add(key)) return;

            // Detect external HTTP calls
            if (isRestTemplateCall(declaredType, methodName, invocation)) {
                pm.getExternalCalls().add(buildRestTemplateExternalCall(invocation, declaredType, methodName,
                        properties, valueFieldMapping, declaringClass));
                return;
            }
            if (isWebClientCall(declaredType, methodName, invocation)) {
                pm.getExternalCalls().add(buildWebClientExternalCall(invocation, declaredType, methodName,
                        properties, valueFieldMapping, declaringClass));
                return;
            }
            if (isHttpUrlConnectionCall(declaredType, methodName, invocation)) {
                pm.getExternalCalls().add(buildHttpUrlConnectionCall(invocation, declaredType, methodName,
                        method, properties, valueFieldMapping, declaringClass));
                return;
            }
            if (feignClients.contains(declaredType)) {
                pm.getExternalCalls().add(buildFeignExternalCall(invocation, execRef, declaredType));
                return;
            }

            // Detect Kafka producer calls
            if (isKafkaProducerCall(declaredType, methodName)) {
                pm.getKafkaCalls().add(buildKafkaProducerCall(invocation, declaredType, methodName,
                        declaringClass, properties, valueFieldMapping));
                return;
            }

            // Log repository calls for debugging
            if (isRepositoryCall(declaredType, methodName)) {
                log.debug("[neo4j-analyzer] Detected repository call: {}.{}() - operation: {}",
                        declaredType, methodName, getRepositoryOperation(methodName));
            }

            // Regular method call - capture as raw invocation
            RawInvocation raw = buildRawInvocation(invocation, execRef, declaringClass);
            if (raw != null) {
                pm.getRawInvocations().add(raw);
            }
        });
    }

    private void collectRawInvocationsForKafkaListener(CtMethod<?> method, ParsedKafkaListenerMethod klm,
                                                        CtType<?> declaringClass,
                                                        Map<String, String> properties,
                                                        Map<String, String> valueFieldMapping,
                                                        Set<String> feignClients) {
        Set<String> seen = new HashSet<>();

        method.getElements(e -> e instanceof CtInvocation).forEach(element -> {
            CtInvocation<?> invocation = (CtInvocation<?>) element;
            CtExecutableReference<?> execRef = invocation.getExecutable();
            if (execRef == null) return;

            // Handle cases where execRef.getDeclaringType() is null (e.g., JPA repository proxy calls)
            String declaredType = null;
            if (execRef.getDeclaringType() != null) {
                declaredType = execRef.getDeclaringType().getQualifiedName();
            } else {
                // Try to get the type from the target field (for repository calls like repository.save())
                CtExpression<?> target = invocation.getTarget();
                if (target != null) {
                    try {
                        if (target.getType() != null && target.getType().getQualifiedName() != null) {
                            declaredType = target.getType().getQualifiedName();
                        }
                    } catch (Exception e) {
                        // Ignore and skip this invocation
                    }
                }
                if (declaredType == null) return;
            }

            String methodName = execRef.getSimpleName();

            if (isStandardType(declaredType) || isGetterOrSetter(methodName)) return;

            String key = declaredType + "#" + methodName;
            if (!seen.add(key)) return;

            if (isRestTemplateCall(declaredType, methodName, invocation)) {
                klm.getExternalCalls().add(buildRestTemplateExternalCall(invocation, declaredType, methodName,
                        properties, valueFieldMapping, declaringClass));
                return;
            }
            if (isWebClientCall(declaredType, methodName, invocation)) {
                klm.getExternalCalls().add(buildWebClientExternalCall(invocation, declaredType, methodName,
                        properties, valueFieldMapping, declaringClass));
                return;
            }
            if (isHttpUrlConnectionCall(declaredType, methodName, invocation)) {
                klm.getExternalCalls().add(buildHttpUrlConnectionCall(invocation, declaredType, methodName,
                        method, properties, valueFieldMapping, declaringClass));
                return;
            }
            if (feignClients.contains(declaredType)) {
                klm.getExternalCalls().add(buildFeignExternalCall(invocation, execRef, declaredType));
                return;
            }
            if (isKafkaProducerCall(declaredType, methodName)) {
                klm.getKafkaCalls().add(buildKafkaProducerCall(invocation, declaredType, methodName,
                        declaringClass, properties, valueFieldMapping));
                return;
            }

            // Log repository calls for debugging
            if (isRepositoryCall(declaredType, methodName)) {
                log.debug("[neo4j-analyzer] Detected repository call in Kafka listener: {}.{}() - operation: {}",
                        declaredType, methodName, getRepositoryOperation(methodName));
            }

            RawInvocation raw = buildRawInvocation(invocation, execRef, declaringClass);
            if (raw != null) {
                klm.getRawInvocations().add(raw);
            }
        });
    }

    private RawInvocation buildRawInvocation(CtInvocation<?> invocation, CtExecutableReference<?> execRef,
                                              CtType<?> declaringClass) {
        try {
            String declaredTypeSimple = null;
            String declaredTypeQualified = null;

            // Handle null declaring type (e.g., JPA repository proxies)
            if (execRef.getDeclaringType() != null) {
                declaredTypeSimple = execRef.getDeclaringType().getSimpleName();
                declaredTypeQualified = execRef.getDeclaringType().getQualifiedName();
            } else {
                // Try to get type from the invocation target
                CtExpression<?> target = invocation.getTarget();
                if (target != null && target.getType() != null) {
                    declaredTypeSimple = target.getType().getSimpleName();
                    declaredTypeQualified = target.getType().getQualifiedName();
                }
            }

            // If we still don't have type info, skip this invocation
            if (declaredTypeSimple == null || declaredTypeQualified == null) {
                return null;
            }

            String methodName = execRef.getSimpleName();
            String signature = execRef.getSignature();

            // Determine if this is a self-call (this.method() or method())
            boolean selfCall = false;
            String targetFieldName = null;

            CtExpression<?> target = invocation.getTarget();
            if (target == null || target.toString().equals("this")) {
                selfCall = true;
            } else if (target instanceof CtFieldRead) {
                CtFieldRead<?> fieldRead = (CtFieldRead<?>) target;
                targetFieldName = fieldRead.getVariable().getSimpleName();
            } else if (target instanceof CtVariableRead) {
                targetFieldName = ((CtVariableRead<?>) target).getVariable().getSimpleName();
            } else {
                // Could be a chained call or complex expression
                String targetStr = target.toString();
                if (targetStr.startsWith("this.")) {
                    targetFieldName = targetStr.substring(5);
                    if (targetFieldName.contains(".")) {
                        targetFieldName = targetFieldName.substring(0, targetFieldName.indexOf('.'));
                    }
                }
            }

            return RawInvocation.builder()
                    .targetFieldName(targetFieldName)
                    .declaredTypeSimple(declaredTypeSimple)
                    .declaredTypeQualified(declaredTypeQualified)
                    .methodName(methodName)
                    .signature(signature)
                    .lineStart(safeGetLine(invocation))
                    .lineEnd(safeGetEndLine(invocation))
                    .selfCall(selfCall)
                    .build();
        } catch (Exception e) {
            log.debug("[neo4j-analyzer] Could not build raw invocation: {}", e.getMessage());
            return null;
        }
    }

    // ========================= PASS 2: RESOLUTION =========================

    /**
     * Pass 2: Resolve all relationships.
     *
     * 1. Build interface -> implementation map from all collected components
     * 2. Resolve injected dependency field types using interface map
     * 3. (Call chain resolution happens at graph build time using the resolved indexes)
     */
    private void resolveAll(ParsedApplication app, CtModel model, String basePackage) {
        log.info("[neo4j-analyzer] Pass 2: Resolving interfaces and dependencies...");

        // Step 1: Build interface -> implementation map
        buildInterfaceToImplMap(app);

        // Step 2: Resolve injected dependency types
        resolveInjectedDependencies(app);

        log.info("[neo4j-analyzer] Resolution complete. Interface mappings: {}", app.getInterfaceToImplMap().size());
    }

    private void buildInterfaceToImplMap(ParsedApplication app) {
        // Collect from all component types that can implement interfaces
        List<ParsedComponent> allComponents = new ArrayList<>();
        allComponents.addAll(app.getServices());
        allComponents.addAll(app.getRepositories());
        allComponents.addAll(app.getKafkaListeners());
        allComponents.addAll(app.getControllers());

        for (ParsedComponent component : allComponents) {
            for (String interfaceName : component.getImplementedInterfaces()) {
                app.getInterfaceToImplMap()
                        .computeIfAbsent(interfaceName, k -> new ArrayList<>())
                        .add(component.getQualifiedName());
            }
        }

        // Log notable interface mappings
        app.getInterfaceToImplMap().forEach((iface, impls) -> {
            if (impls.size() > 1) {
                log.info("[neo4j-analyzer] Interface {} has multiple implementations: {}", iface, impls);
            }
        });
    }

    private void resolveInjectedDependencies(ParsedApplication app) {
        List<ParsedComponent> allComponents = new ArrayList<>();
        allComponents.addAll(app.getControllers());
        allComponents.addAll(app.getServices());
        allComponents.addAll(app.getKafkaListeners());

        for (ParsedComponent component : allComponents) {
            for (InjectedDependency dep : component.getInjectedDependencies().values()) {
                String declaredType = dep.getDeclaredTypeQualified();
                String declaredSimple = dep.getDeclaredTypeSimple();

                // First check if the declared type is directly a known component
                ParsedComponent directMatch = app.getComponentIndex().get(declaredType);
                if (directMatch == null) {
                    directMatch = app.getComponentIndex().get(declaredSimple);
                }

                if (directMatch != null) {
                    dep.setResolvedTypeQualified(directMatch.getQualifiedName());
                    dep.setResolvedTypeSimple(directMatch.getClassName());
                    continue;
                }

                // Check if it's an interface with a known implementation
                List<String> impls = app.getInterfaceToImplMap().get(declaredType);
                if (impls == null) {
                    impls = app.getInterfaceToImplMap().get(declaredSimple);
                }

                if (impls != null && !impls.isEmpty()) {
                    // Use the first implementation (most common case: single impl per interface)
                    String implQualified = impls.get(0);
                    ParsedComponent implComponent = app.getComponentIndex().get(implQualified);
                    if (implComponent != null) {
                        dep.setResolvedTypeQualified(implComponent.getQualifiedName());
                        dep.setResolvedTypeSimple(implComponent.getClassName());
                    } else {
                        dep.setResolvedTypeQualified(implQualified);
                        dep.setResolvedTypeSimple(extractSimpleName(implQualified));
                    }
                    log.debug("[neo4j-analyzer] Resolved DI: {}.{} : {} -> {}",
                            component.getClassName(), dep.getFieldName(), declaredSimple, dep.getResolvedTypeSimple());
                }
            }
        }
    }

    // ========================= EXTERNAL CALL BUILDERS =========================

    private boolean isRestTemplateCall(String declaredType, String methodName, CtInvocation<?> invocation) {
        if (!configService.getRestTemplateMethods().contains(methodName)) return false;
        if (declaredType.endsWith("RestTemplate")) return true;
        CtExpression<?> target = invocation.getTarget();
        if (target != null && target.getType() != null) {
            return target.getType().getQualifiedName().endsWith("RestTemplate");
        }
        return target != null && target.toString().toLowerCase(Locale.ROOT).contains("resttemplate");
    }

    private boolean isWebClientCall(String declaredType, String methodName, CtInvocation<?> invocation) {
        if (declaredType.endsWith("WebClient")) {
            return configService.getWebClientHttpMethods().contains(methodName) || "uri".equals(methodName);
        }
        CtExpression<?> target = invocation.getTarget();
        if (target != null && target.getType() != null) {
            String typeName = target.getType().getQualifiedName();
            return typeName.endsWith("WebClient")
                    || (typeName.contains("WebClient") && configService.getWebClientHttpMethods().contains(methodName));
        }
        return false;
    }

    private boolean isHttpUrlConnectionCall(String declaredType, String methodName, CtInvocation<?> invocation) {
        // Check for URL.openConnection() or HttpURLConnection methods
        if ("openConnection".equals(methodName) &&
            (declaredType.equals("java.net.URL") || declaredType.endsWith(".URL"))) {
            return true;
        }

        // Check for HttpURLConnection methods
        if (HTTP_URL_CONNECTION_METHODS.contains(methodName)) {
            if (declaredType.contains("HttpURLConnection") ||
                declaredType.contains("URLConnection")) {
                return true;
            }

            // Check target type
            CtExpression<?> target = invocation.getTarget();
            if (target != null && target.getType() != null) {
                String typeName = target.getType().getQualifiedName();
                return typeName.contains("HttpURLConnection") || typeName.contains("URLConnection");
            }
        }

        return false;
    }

    private boolean isKafkaProducerCall(String declaredType, String methodName) {
        return configService.getKafkaProducerMethods().contains(methodName)
                && configService.getKafkaProducerTypes().stream().anyMatch(declaredType::endsWith);
    }

    private boolean isRepositoryCall(String declaredType, String methodName) {
        // Check if it's a repository type and uses a repository method
        boolean isRepoType = declaredType.endsWith("Repository") ||
                           declaredType.contains("Repository<");
        boolean isRepoMethod = configService.getRepositoryWriteMethods().contains(methodName) ||
                             configService.getRepositoryReadMethods().contains(methodName);
        return isRepoType && isRepoMethod;
    }

    private String getRepositoryOperation(String methodName) {
        if (configService.getRepositoryWriteMethods().contains(methodName)) {
            if (methodName.startsWith("save")) return "SAVE";
            if (methodName.startsWith("delete")) return "DELETE";
            if (methodName.equals("insert")) return "INSERT";
            if (methodName.equals("update")) return "UPDATE";
            if (methodName.equals("upsert")) return "UPSERT";
        } else if (configService.getRepositoryReadMethods().contains(methodName)) {
            return "READ";
        }
        return "UNKNOWN";
    }

    private ParsedExternalCall buildRestTemplateExternalCall(CtInvocation<?> invocation, String declaredType,
                                                             String methodName,
                                                             Map<String, String> properties,
                                                             Map<String, String> valueFieldMapping,
                                                             CtType<?> declaringClass) {
        String httpMethod = resolveRestTemplateHttpMethod(methodName, invocation);
        String url = extractUrlFromArguments(invocation.getArguments(), properties, valueFieldMapping, declaringClass);

        return ParsedExternalCall.builder()
                .clientType("RestTemplate")
                .httpMethod(httpMethod)
                .url(url)
                .targetClass(declaredType)
                .targetMethod(methodName)
                .lineStart(safeGetLine(invocation))
                .lineEnd(safeGetEndLine(invocation))
                .build();
    }

    private ParsedExternalCall buildWebClientExternalCall(CtInvocation<?> invocation, String declaredType,
                                                           String methodName,
                                                           Map<String, String> properties,
                                                           Map<String, String> valueFieldMapping,
                                                           CtType<?> declaringClass) {
        String httpMethod = configService.getWebClientHttpMethods().contains(methodName)
                ? methodName.toUpperCase(Locale.ROOT) : "UNKNOWN";
        String url = extractUrlFromWebClientChain(invocation, properties, valueFieldMapping, declaringClass);

        return ParsedExternalCall.builder()
                .clientType("WebClient")
                .httpMethod(httpMethod)
                .url(url)
                .targetClass(declaredType)
                .targetMethod(methodName)
                .lineStart(safeGetLine(invocation))
                .lineEnd(safeGetEndLine(invocation))
                .build();
    }

    private ParsedExternalCall buildFeignExternalCall(CtInvocation<?> invocation, CtExecutableReference<?> execRef,
                                                       String declaredType) {
        String httpMethod = "REQUEST";
        String url = "<dynamic>";

        try {
            CtExecutable<?> decl = execRef.getExecutableDeclaration();
            if (decl instanceof CtMethod<?> feignMethod) {
                String basePath = extractBasePath(feignMethod.getDeclaringType());
                for (CtAnnotation<?> ann : feignMethod.getAnnotations()) {
                    String annName = ann.getAnnotationType().getSimpleName();
                    if (configService.getMappingAnnotations().contains(annName)) {
                        httpMethod = configService.getAnnotationToHttpMethod().getOrDefault(annName, "REQUEST");
                        url = extractPath(ann, basePath);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[neo4j-analyzer] Could not resolve Feign method: {}", execRef.getSimpleName());
        }

        return ParsedExternalCall.builder()
                .clientType("Feign")
                .httpMethod(httpMethod)
                .url(url)
                .targetClass(declaredType)
                .targetMethod(execRef.getSimpleName())
                .lineStart(safeGetLine(invocation))
                .lineEnd(safeGetEndLine(invocation))
                .build();
    }

    private ParsedExternalCall buildHttpUrlConnectionCall(CtInvocation<?> invocation, String declaredType,
                                                           String methodName, CtMethod<?> containingMethod,
                                                           Map<String, String> properties,
                                                           Map<String, String> valueFieldMapping,
                                                           CtType<?> declaringClass) {
        String httpMethod = "GET"; // Default
        String url = "<dynamic>";

        try {
            // Extract HTTP method from setRequestMethod calls in the same method
            if (containingMethod != null) {
                containingMethod.getElements(e -> e instanceof CtInvocation).forEach(element -> {
                    try {
                        CtInvocation<?> inv = (CtInvocation<?>) element;
                        if ("setRequestMethod".equals(inv.getExecutable().getSimpleName())) {
                            List<CtExpression<?>> args = inv.getArguments();
                            if (!args.isEmpty()) {
                                String method = extractStringFromExpression(args.get(0), properties, valueFieldMapping, declaringClass);
                                if (method != null && !method.isEmpty()) {
                                    log.debug("[neo4j-analyzer] Found HTTP method: {}", method);
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                });
            }

            // Extract URL from new URL(urlString).openConnection() pattern
            if ("openConnection".equals(methodName) && invocation.getTarget() instanceof CtConstructorCall) {
                CtConstructorCall<?> ctorCall = (CtConstructorCall<?>) invocation.getTarget();
                List<CtExpression<?>> args = ctorCall.getArguments();
                if (!args.isEmpty()) {
                    url = extractStringFromExpression(args.get(0), properties, valueFieldMapping, declaringClass);
                    if (url == null) url = "<dynamic>";
                }
            } else if (containingMethod != null) {
                // Look for new URL(...) constructor calls in the method
                for (Object element : containingMethod.getElements(e -> e instanceof CtConstructorCall)) {
                    try {
                        CtConstructorCall<?> ctor = (CtConstructorCall<?>) element;
                        if (ctor.getType() != null && ctor.getType().getQualifiedName().equals("java.net.URL")) {
                            List<CtExpression<?>> args = ctor.getArguments();
                            if (!args.isEmpty()) {
                                String extractedUrl = extractStringFromExpression(args.get(0), properties, valueFieldMapping, declaringClass);
                                if (extractedUrl != null && !extractedUrl.isEmpty()) {
                                    url = extractedUrl;
                                    log.debug("[neo4j-analyzer] Found URL: {}", extractedUrl);
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.debug("[neo4j-analyzer] Could not extract HttpURLConnection details: {}", e.getMessage());
        }

        return ParsedExternalCall.builder()
                .clientType("HttpURLConnection")
                .httpMethod(httpMethod)
                .url(url)
                .targetClass(declaredType)
                .targetMethod(methodName)
                .lineStart(safeGetLine(invocation))
                .lineEnd(safeGetEndLine(invocation))
                .build();
    }

    private ParsedKafkaCall buildKafkaProducerCall(CtInvocation<?> invocation, String declaredType,
                                                    String methodName, CtType<?> declaringClass,
                                                    Map<String, String> properties,
                                                    Map<String, String> valueFieldMapping) {
        String rawTopic = extractTopicFromKafkaProducerCall(invocation, properties, valueFieldMapping, declaringClass);

        return ParsedKafkaCall.builder()
                .direction("PRODUCER")
                .topicName(rawTopic)
                .rawTopic(rawTopic)
                .className(declaringClass.getSimpleName())
                .methodName(methodName)
                .lineStart(safeGetLine(invocation))
                .lineEnd(safeGetEndLine(invocation))
                .build();
    }

    // ========================= KAFKA EXTRACTION HELPERS =========================

    private String extractKafkaTopic(CtAnnotation<?> annotation, Map<String, String> properties,
                                      Map<String, String> valueFieldMapping) {
        try {
            // Try "topics" first, then "value"
            Object topicsValue = annotation.getValues().get("topics");
            if (topicsValue == null) topicsValue = annotation.getValues().get("value");
            if (topicsValue == null) return "";

            String raw = topicsValue.toString().replaceAll("[\"\\[\\]{}]", "").trim();
            if (raw.contains("${") || raw.contains("#{")) {
                return propertyResolver.resolveProperty(raw, properties);
            }
            // Try value field mapping
            String resolved = valueFieldMapping.get(raw);
            if (resolved != null) return resolved;
            return raw;
        } catch (Exception e) {
            return "";
        }
    }

    private String extractKafkaGroupId(CtAnnotation<?> annotation, Map<String, String> properties,
                                        Map<String, String> valueFieldMapping) {
        try {
            Object groupIdValue = annotation.getValues().get("groupId");
            if (groupIdValue == null) return "";
            String raw = groupIdValue.toString().replaceAll("[\"\\[\\]{}]", "").trim();
            if (raw.contains("${")) {
                return propertyResolver.resolveProperty(raw, properties);
            }
            return raw;
        } catch (Exception e) {
            return "";
        }
    }

    private String extractTopicFromKafkaProducerCall(CtInvocation<?> invocation, Map<String, String> properties,
                                                      Map<String, String> valueFieldMapping,
                                                      CtType<?> declaringClass) {
        try {
            List<? extends CtExpression<?>> args = invocation.getArguments();
            if (args.isEmpty()) return "<dynamic>";

            // First argument is typically the topic
            CtExpression<?> firstArg = args.get(0);
            String extracted = extractStringFromExpression(firstArg, properties, valueFieldMapping, declaringClass);
            return extracted != null ? extracted : "<dynamic>";
        } catch (Exception e) {
            return "<dynamic>";
        }
    }

    // ========================= URL / STRING EXTRACTION =========================

    private String extractUrlFromArguments(List<? extends CtExpression<?>> arguments,
                                           Map<String, String> properties,
                                           Map<String, String> valueFieldMapping,
                                           CtType<?> declaringClass) {
        for (CtExpression<?> arg : arguments) {
            String extracted = extractStringFromExpression(arg, properties, valueFieldMapping, declaringClass);
            if (extracted != null && !extracted.isBlank()) {
                return extracted;
            }
        }
        return "<dynamic>";
    }

    private String extractUrlFromWebClientChain(CtInvocation<?> invocation,
                                                Map<String, String> properties,
                                                Map<String, String> valueFieldMapping,
                                                CtType<?> declaringClass) {
        CtExpression<?> target = invocation.getTarget();
        while (target instanceof CtInvocation<?> targetInvocation) {
            if ("uri".equals(targetInvocation.getExecutable().getSimpleName())) {
                return extractUrlFromArguments(targetInvocation.getArguments(), properties, valueFieldMapping, declaringClass);
            }
            target = targetInvocation.getTarget();
        }
        return "<dynamic>";
    }

    private String extractStringFromExpression(CtExpression<?> expression,
                                               Map<String, String> properties,
                                               Map<String, String> valueFieldMapping,
                                               CtType<?> declaringClass) {
        if (expression == null) return null;

        if (expression instanceof CtLiteral<?> literal) {
            Object value = literal.getValue();
            if (value instanceof String s) return s;
        }

        if (expression instanceof CtFieldRead<?> fieldRead) {
            return resolveFieldRead(fieldRead, properties, valueFieldMapping);
        }

        // Resolve local variable reads (e.g., userServiceUrl + "/path" + id)
        if (expression instanceof CtVariableRead<?> variableRead) {
            String varName = variableRead.getVariable().getSimpleName();
            // Try to resolve from enclosing method local variables / assignments
            CtMethod<?> enclosingMethod = findEnclosingMethod(expression);
            if (enclosingMethod != null) {
                // Search for local variable declarations with initializer
                for (Object element : enclosingMethod.getElements(e -> e instanceof CtLocalVariable)) {
                    try {
                        CtLocalVariable<?> local = (CtLocalVariable<?>) element;
                        if (local.getSimpleName().equals(varName) && local.getDefaultExpression() != null) {
                            String resolved = extractStringFromExpression(local.getDefaultExpression(), properties, valueFieldMapping, declaringClass);
                            if (resolved != null) return resolved;
                        }
                    } catch (Exception ignored) {}
                }

                // Search for assignments to the variable earlier in the method
                for (Object element : enclosingMethod.getElements(e -> e instanceof CtAssignment)) {
                    try {
                        CtAssignment<?, ?> assign = (CtAssignment<?, ?>) element;
                        String assignedTo = assign.getAssigned().toString();
                        // Simple name match
                        if (assignedTo.equals(varName) || assignedTo.endsWith("." + varName)) {
                            String resolved = extractStringFromExpression((CtExpression<?>) assign.getAssignment(), properties, valueFieldMapping, declaringClass);
                            if (resolved != null) return resolved;
                        }
                    } catch (Exception ignored) {}
                }

                // Check if it's a method parameter - these are dynamic at runtime
                try {
                    boolean isMethodParam = enclosingMethod.getParameters().stream()
                            .anyMatch(param -> param.getSimpleName().equals(varName));
                    if (isMethodParam) {
                        log.debug("[neo4j-analyzer] Variable '{}' is a method parameter, treating as <dynamic>", varName);
                        return "<dynamic>";
                    }
                } catch (Exception ignored) {}
            }

            // Fall back: try to resolve as a field read if possible
            try {
                if (variableRead.getVariable() instanceof CtFieldReference) {
                    CtFieldRead<?> fr = variableRead.getParent(CtFieldRead.class);
                    if (fr != null) return resolveFieldRead(fr, properties, valueFieldMapping);
                }
            } catch (Exception ignored) {}

            // Could not resolve - return <dynamic> placeholder for unresolved variables
            log.debug("[neo4j-analyzer] Variable '{}' could not be resolved, treating as <dynamic>", varName);
            return "<dynamic>";
        }

        if (expression instanceof CtBinaryOperator<?> binary) {
            if (binary.getKind() == BinaryOperatorKind.PLUS) {
                String left = extractStringFromExpression(binary.getLeftHandOperand(), properties, valueFieldMapping, declaringClass);
                String right = extractStringFromExpression(binary.getRightHandOperand(), properties, valueFieldMapping, declaringClass);

                // Handle concatenation where one or both parts might be unresolved (dynamic)
                if (left != null && right != null) {
                    return left + right;
                }
                // If one side is null (unresolved variable), treat it as <dynamic>
                if (left != null && right == null) {
                    return left + "<dynamic>";
                }
                if (left == null && right != null) {
                    return "<dynamic>" + right;
                }
                // Both null - return dynamic placeholder
                if (left == null && right == null) {
                    return "<dynamic>";
                }
            }
        }

        // Handle String.format() calls
        if (expression instanceof CtInvocation<?> invocation) {
            try {
                CtExecutableReference<?> execRef = invocation.getExecutable();
                if (execRef != null && "format".equals(execRef.getSimpleName())) {
                    CtTypeReference<?> declaringType = execRef.getDeclaringType();
                    if (declaringType != null && "java.lang.String".equals(declaringType.getQualifiedName())) {
                        // Get the format string (first argument)
                        List<CtExpression<?>> args = invocation.getArguments();
                        if (!args.isEmpty()) {
                            String formatStr = extractStringFromExpression(args.get(0), properties, valueFieldMapping, declaringClass);
                            if (formatStr != null) {
                                // Return the format string with placeholders (e.g., "https://example.com/api?lat=%f&lon=%f")
                                return formatStr;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[neo4j-analyzer] Could not extract from invocation: {}", e.getMessage());
            }
        }

        return null;
    }

    // Helper: find enclosing method for a CtElement by walking parents
    private CtMethod<?> findEnclosingMethod(CtElement element) {
        CtElement current = element;
        while (current != null && !(current instanceof CtMethod)) {
            current = current.getParent();
        }
        return (CtMethod<?>) current;
    }

    // ========================= VALUE FIELD MAPPING =========================

    private Map<String, String> buildValueFieldMapping(CtModel model, String basePackage,
                                                        Map<String, String> properties) {
        Map<String, String> fieldMapping = new HashMap<>();

        model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(type -> matchesPackage(type, basePackage))
                .forEach(ctClass -> {
                    for (CtField<?> field : ctClass.getFields()) {
                        // @Value annotated fields
                        for (CtAnnotation<?> ann : field.getAnnotations()) {
                            if ("Value".equals(ann.getAnnotationType().getSimpleName())) {
                                try {
                                    Object valueObj = ann.getValue("value");
                                    if (valueObj != null) {
                                        String placeholder = valueObj.toString().replaceAll("^\"|\"$", "");
                                        String resolvedValue = propertyResolver.resolveProperty(placeholder, properties);
                                        String key = ctClass.getQualifiedName() + "." + field.getSimpleName();
                                        fieldMapping.put(key, resolvedValue);
                                    }
                                } catch (Exception e) {
                                    // ignore
                                }
                            }
                        }

                        // Static final String constants
                        try {
                            if (field.isStatic() && field.isFinal()
                                    && field.getType() != null && "String".equals(field.getType().getSimpleName())) {
                                CtExpression<?> defaultExpr = field.getDefaultExpression();
                                if (defaultExpr instanceof CtLiteral<?> lit) {
                                    Object val = lit.getValue();
                                    if (val instanceof String s) {
                                        if (s.contains("${")) {
                                            s = propertyResolver.resolveProperty(s, properties);
                                        }
                                        fieldMapping.put(ctClass.getQualifiedName() + "." + field.getSimpleName(), s);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                });

        return fieldMapping;
    }

    // ========================= REPOSITORY HELPERS =========================

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
                // ignore
            }
        }
        return null;
    }

    private String extractTableName(CtType<?> entityClass, String entityClassName) {
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

    private String determineRepositoryType(String extendsClass) {
        if (extendsClass.contains("ReactiveMongoRepository")) return "Reactive MongoDB";
        if (extendsClass.contains("ReactiveCrudRepository")) return "Reactive JPA";
        if (extendsClass.contains("MongoRepository")) return "MongoDB";
        if (extendsClass.contains("JpaRepository") || extendsClass.contains("CrudRepository")) return "JPA";
        return "Custom";
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
        if (operations.isEmpty()) {
            operations.addAll(List.of("READ", "WRITE", "DELETE"));
        }
        return new ArrayList<>(operations);
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

    private String extractRequestBodyType(CtMethod<?> method) {
        try {
            for (CtParameter<?> param : method.getParameters()) {
                boolean hasRequestBody = param.getAnnotations().stream()
                        .anyMatch(a -> "RequestBody".equals(a.getAnnotationType().getSimpleName()));
                if (hasRequestBody) {
                    return param.getType().getQualifiedName();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private String extractResponseType(CtMethod<?> method) {
        try {
            CtTypeReference<?> returnType = method.getType();
            if (returnType == null || "void".equals(returnType.getSimpleName())) return null;
            return returnType.getQualifiedName();
        } catch (Exception e) {
            return null;
        }
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

    // ========================= SPRING BOOT DETECTION =========================

    private List<CtType<?>> findSpringBootApplications(CtModel model) {
        return model.getAllTypes().stream()
                .filter(CtType::isClass)
                .filter(t -> t.getAnnotations().stream()
                        .anyMatch(a -> "SpringBootApplication".equals(a.getAnnotationType().getSimpleName())))
                .collect(Collectors.toList());
    }

    // ========================= UTILITY =========================

    private boolean matchesPackage(CtType<?> ctType, String basePackage) {
        if (basePackage == null || basePackage.isEmpty()) return true;
        return ctType.getPackage() != null && ctType.getPackage().getQualifiedName().startsWith(basePackage);
    }

    private boolean isStandardType(String className) {
        if (className == null) return true;

        // Check if this package is explicitly allowed for analysis
        Set<String> allowedPackages = configService.getAllowedAnalysisPackages();
        for (String allowedPackage : allowedPackages) {
            if (className.startsWith(allowedPackage)) {
                return false; // Not a standard type - should be analyzed
            }
        }

        // Otherwise, filter out standard library and framework packages
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jakarta.")
                || className.startsWith("org.springframework.")
                || className.startsWith("lombok.")
                || className.startsWith("org.slf4j.")
                || className.startsWith("org.apache.");
    }

    private boolean isGetterOrSetter(String methodName) {
        if (methodName == null || methodName.isEmpty()) return false;
        if (methodName.startsWith("get") && methodName.length() > 3 && Character.isUpperCase(methodName.charAt(3)))
            return true;
        if (methodName.startsWith("set") && methodName.length() > 3 && Character.isUpperCase(methodName.charAt(3)))
            return true;
        if (methodName.startsWith("is") && methodName.length() > 2 && Character.isUpperCase(methodName.charAt(2)))
            return true;
        return switch (methodName) {
            case "builder", "build", "toBuilder", "toString", "hashCode", "equals",
                 "canEqual", "of", "valueOf" -> true;
            default -> false;
        };
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

    private int safeGetLine(CtElement element) {
        try { return element.getPosition().getLine(); } catch (Exception e) { return 0; }
    }

    private int safeGetEndLine(CtElement element) {
        try { return element.getPosition().getEndLine(); } catch (Exception e) { return 0; }
    }

    private String safeGetFilePath(CtElement element) {
        try { return element.getPosition().getFile().getAbsolutePath(); } catch (Exception e) { return ""; }
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
                    log.debug("[neo4j-analyzer] Could not resolve field declaration: {}", qualifiedName);
                }
            }
        } catch (Exception e) {
            log.debug("[neo4j-analyzer] Error resolving field read: {}", e.getMessage());
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
}
