package com.architecture.memory.orkestify.service.graph.analyzer;

import com.architecture.memory.orkestify.model.graph.nodes.*;
import com.architecture.memory.orkestify.repository.graph.ApplicationNodeRepository;
import com.architecture.memory.orkestify.repository.graph.KafkaTopicNodeRepository;
import com.architecture.memory.orkestify.service.graph.CanonicalIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Converts ParsedApplication (output of Neo4jSpoonAnalyzer) into Neo4j graph nodes and persists them.
 *
 * Key improvements over old GraphPersistenceService:
 *   1. Full-depth call chain resolution using resolved DI types and interface map
 *   2. Proper interface -> implementation method linking
 *   3. Self-call resolution (private methods within the same service)
 *   4. Service -> Service call chains (unlimited depth with cycle detection)
 *   5. Service -> Repository calls properly resolved via field type, not declaring type
 *   6. Canonical ID generation for stable graph identity across commits and PRs
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Neo4jGraphBuilder {

    private final ApplicationNodeRepository applicationNodeRepository;
    private final KafkaTopicNodeRepository kafkaTopicNodeRepository;
    private final CanonicalIdGenerator canonicalIdGenerator;

    /**
     * Build and persist the Neo4j graph from a parsed application.
     */
    @Transactional
    public void buildAndPersist(String projectId, String userId, String repoUrl,
                                 ParsedApplication parsedApp) {
        log.info("[neo4j-builder] Building graph for projectId={} repoUrl={}", projectId, repoUrl);

        String appKey = buildAppKey(repoUrl, parsedApp);

        // Upsert application node
        ApplicationNode appNode = applicationNodeRepository.findByProjectIdAndAppKey(projectId, appKey)
                .orElseGet(() -> {
                    ApplicationNode node = ApplicationNode.builder()
                            .projectId(projectId)
                            .repoUrl(repoUrl)
                            .appKey(appKey)
                            .controllers(new HashSet<>())
                            .services(new HashSet<>())
                            .repositories(new HashSet<>())
                            .kafkaListeners(new HashSet<>())
                            .configurations(new HashSet<>())
                            .isSpringBoot(true)
                            .build();
                    node.setCanonicalId(canonicalIdGenerator.generateApplicationCanonicalId(appKey));
                    return node;
                });

        // Set canonicalId if not already set (for existing nodes)
        if (appNode.getCanonicalId() == null) {
            appNode.setCanonicalId(canonicalIdGenerator.generateApplicationCanonicalId(appKey));
        }

        // Clear existing relationships for update
        appNode.getControllers().clear();
        appNode.getServices().clear();
        appNode.getRepositories().clear();
        appNode.getKafkaListeners().clear();
        appNode.getConfigurations().clear();

        // Set application info
        appNode.setMainClassName(parsedApp.getMainClassName());
        appNode.setMainClassPackage(parsedApp.getMainClassPackage());
        appNode.setSpringBoot(parsedApp.isSpringBoot());
        appNode.setRootPath(parsedApp.getRootPath());
        appNode.setLineStart(parsedApp.getLineStart());
        appNode.setLineEnd(parsedApp.getLineEnd());
        appNode.setAnalyzedAt(LocalDateTime.now());
        appNode.setStatus("COMPLETED");

        // Build all components and resolution indexes
        Map<String, MethodNode> serviceMethodIndex = new HashMap<>();
        Map<String, MethodNode> repositoryMethodIndex = new HashMap<>();
        Map<String, MethodNode> allMethodIndex = new HashMap<>();

        // Step 1: Build repository nodes and index their methods
        Set<RepositoryClassNode> repoNodes = buildRepositoryNodes(
                parsedApp.getRepositories(), projectId, appKey, repositoryMethodIndex);
        appNode.getRepositories().addAll(repoNodes);
        allMethodIndex.putAll(repositoryMethodIndex);

        // Step 2: Build service nodes and index their methods
        Set<ServiceNode> serviceNodes = buildServiceNodes(
                parsedApp.getServices(), projectId, appKey, serviceMethodIndex);
        appNode.getServices().addAll(serviceNodes);
        allMethodIndex.putAll(serviceMethodIndex);

        // Step 3: Resolve service method calls (service -> repo, service -> service, self-calls)
        resolveServiceMethodCalls(parsedApp, serviceMethodIndex, repositoryMethodIndex, allMethodIndex);

        // Step 4: Build controller nodes and link endpoints to service methods
        Set<ControllerNode> controllerNodes = buildControllerNodes(
                parsedApp.getControllers(), projectId, appKey, parsedApp, serviceMethodIndex, allMethodIndex);
        appNode.getControllers().addAll(controllerNodes);

        // Step 5: Build Kafka listener nodes
        Set<KafkaListenerNode> kafkaListenerNodes = buildKafkaListenerNodes(
                parsedApp.getKafkaListeners(), projectId, appKey, parsedApp, serviceMethodIndex, allMethodIndex);
        appNode.getKafkaListeners().addAll(kafkaListenerNodes);

        // Step 6: Build configuration nodes
        Set<ConfigurationNode> configNodes = buildConfigurationNodes(
                parsedApp.getConfigurations(), projectId, appKey);
        appNode.getConfigurations().addAll(configNodes);

        // Save everything
        applicationNodeRepository.save(appNode);

        log.info("[neo4j-builder] Graph persisted: controllers={}, services={}, repos={}, kafkaListeners={}, configs={}",
                controllerNodes.size(), serviceNodes.size(), repoNodes.size(),
                kafkaListenerNodes.size(), configNodes.size());
    }

    @Transactional
    public void deleteProjectGraph(String projectId) {
        applicationNodeRepository.deleteApplicationGraphByProjectId(projectId);
    }

    // ========================= Repository Building =========================

    private Set<RepositoryClassNode> buildRepositoryNodes(List<ParsedComponent> repositories,
                                                           String projectId, String appKey,
                                                           Map<String, MethodNode> repositoryMethodIndex) {
        Set<RepositoryClassNode> nodes = new HashSet<>();
        if (repositories == null) return nodes;

        for (ParsedComponent repo : repositories) {
            RepositoryClassNode repoNode = RepositoryClassNode.builder()
                    .className(repo.getClassName())
                    .packageName(repo.getPackageName())
                    .canonicalId(canonicalIdGenerator.generateRepositoryCanonicalId(
                            repo.getPackageName(), repo.getClassName()))
                    .repositoryType(repo.getRepositoryType())
                    .extendsClass(repo.getExtendsClass())
                    .projectId(projectId)
                    .appKey(appKey)
                    .lineStart(repo.getLineStart())
                    .lineEnd(repo.getLineEnd())
                    .methods(new HashSet<>())
                    .accessesTables(new HashSet<>())
                    .build();

            // Build methods
            for (ParsedMethod pm : repo.getMethods()) {
                MethodNode methodNode = MethodNode.builder()
                        .className(repo.getClassName())
                        .packageName(repo.getPackageName())
                        .methodName(pm.getMethodName())
                        .signature(pm.getSignature())
                        .canonicalId(canonicalIdGenerator.generateMethodCanonicalId(
                                repo.getPackageName(), repo.getClassName(), pm.getMethodName(), pm.getSignature()))
                        .projectId(projectId)
                        .appKey(appKey)
                        .methodType("REPOSITORY_METHOD")
                        .lineStart(pm.getLineStart())
                        .lineEnd(pm.getLineEnd())
                        .calls(new HashSet<>())
                        .externalCalls(new HashSet<>())
                        .producesToTopics(new HashSet<>())
                        .build();

                repoNode.getMethods().add(methodNode);
                indexMethod(repositoryMethodIndex, methodNode, repo.getQualifiedName());
            }

            // Build database table node
            if (repo.getTableName() != null) {
                DatabaseTableNode tableNode = DatabaseTableNode.builder()
                        .tableName(repo.getTableName())
                        .canonicalId(canonicalIdGenerator.generateDatabaseTableCanonicalId(repo.getTableName()))
                        .entityClass(repo.getEntityClassName())
                        .entitySimpleName(extractSimpleName(repo.getEntityClassName()))
                        .databaseType(repo.getDatabaseType())
                        .tableSource(repo.getTableSource())
                        .operations(repo.getDatabaseOperations())
                        .projectId(projectId)
                        .appKey(appKey)
                        .build();
                repoNode.getAccessesTables().add(tableNode);
            }

            nodes.add(repoNode);
        }
        return nodes;
    }

    // ========================= Service Building =========================

    private Set<ServiceNode> buildServiceNodes(List<ParsedComponent> services,
                                                String projectId, String appKey,
                                                Map<String, MethodNode> serviceMethodIndex) {
        Set<ServiceNode> nodes = new HashSet<>();
        if (services == null) return nodes;

        for (ParsedComponent service : services) {
            ServiceNode serviceNode = ServiceNode.builder()
                    .className(service.getClassName())
                    .packageName(service.getPackageName())
                    .canonicalId(canonicalIdGenerator.generateServiceCanonicalId(
                            service.getPackageName(), service.getClassName()))
                    .projectId(projectId)
                    .appKey(appKey)
                    .lineStart(service.getLineStart())
                    .lineEnd(service.getLineEnd())
                    .methods(new HashSet<>())
                    .build();

            for (ParsedMethod pm : service.getMethods()) {
                MethodNode methodNode = MethodNode.builder()
                        .className(service.getClassName())
                        .packageName(service.getPackageName())
                        .methodName(pm.getMethodName())
                        .signature(pm.getSignature())
                        .canonicalId(canonicalIdGenerator.generateMethodCanonicalId(
                                service.getPackageName(), service.getClassName(), pm.getMethodName(), pm.getSignature()))
                        .projectId(projectId)
                        .appKey(appKey)
                        .methodType("SERVICE_METHOD")
                        .lineStart(pm.getLineStart())
                        .lineEnd(pm.getLineEnd())
                        .calls(new HashSet<>())
                        .externalCalls(new HashSet<>())
                        .producesToTopics(new HashSet<>())
                        .build();

                // Build external calls
                for (ParsedExternalCall ext : pm.getExternalCalls()) {
                    methodNode.getExternalCalls().add(buildExternalCallNode(ext, projectId, appKey));
                }

                // Build Kafka producer calls
                for (ParsedKafkaCall kc : pm.getKafkaCalls()) {
                    KafkaTopicNode topicNode = getOrCreateKafkaTopic(kc.getTopicName(), projectId, appKey);
                    String detail = String.format("%s.%s (line %d)", kc.getClassName(), kc.getMethodName(), kc.getLineStart());
                    if (!topicNode.getProducerDetails().contains(detail)) {
                        topicNode.getProducerDetails().add(detail);
                    }
                    if (!topicNode.getProducerServiceNames().contains(kc.getClassName())) {
                        topicNode.getProducerServiceNames().add(kc.getClassName());
                    }
                    methodNode.getProducesToTopics().add(topicNode);
                }

                serviceNode.getMethods().add(methodNode);
                indexMethod(serviceMethodIndex, methodNode, service.getQualifiedName());

                // Also index by interface names for this service
                for (String interfaceName : service.getImplementedInterfaces()) {
                    String ifaceKey = interfaceName + "#" + pm.getMethodName();
                    serviceMethodIndex.putIfAbsent(ifaceKey, methodNode);
                }
            }

            nodes.add(serviceNode);
        }
        return nodes;
    }

    // ========================= Call Chain Resolution =========================

    /**
     * Resolve all service method calls using the resolved DI information.
     *
     * For each service method's raw invocations:
     *   1. If selfCall: look up the method in the same service's method index
     *   2. If targetFieldName is present: use the DI map to find the concrete type, then look up the method
     *   3. If the declared type is an interface: use interfaceToImplMap to find the implementation
     *   4. Fallback: try direct class#method lookup
     */
    private void resolveServiceMethodCalls(ParsedApplication parsedApp,
                                            Map<String, MethodNode> serviceMethodIndex,
                                            Map<String, MethodNode> repositoryMethodIndex,
                                            Map<String, MethodNode> allMethodIndex) {
        log.info("[neo4j-builder] Resolving service method calls...");

        List<ParsedComponent> allServicesAndListeners = new ArrayList<>();
        allServicesAndListeners.addAll(parsedApp.getServices());
        allServicesAndListeners.addAll(parsedApp.getKafkaListeners());

        for (ParsedComponent component : allServicesAndListeners) {
            for (ParsedMethod pm : component.getMethods()) {
                MethodNode methodNode = findMethodNode(serviceMethodIndex, component, pm);
                if (methodNode == null) continue;

                Set<String> visited = new HashSet<>();
                visited.add(component.getQualifiedName() + "#" + pm.getMethodName());

                resolveInvocationsForMethod(pm.getRawInvocations(), methodNode, component, parsedApp,
                        serviceMethodIndex, repositoryMethodIndex, allMethodIndex, visited);
            }
        }
    }

    private void resolveInvocationsForMethod(List<RawInvocation> invocations, MethodNode methodNode,
                                              ParsedComponent ownerComponent, ParsedApplication parsedApp,
                                              Map<String, MethodNode> serviceMethodIndex,
                                              Map<String, MethodNode> repositoryMethodIndex,
                                              Map<String, MethodNode> allMethodIndex,
                                              Set<String> visited) {
        if (invocations == null) return;

        for (RawInvocation raw : invocations) {
            MethodNode target = resolveRawInvocation(raw, ownerComponent, parsedApp,
                    serviceMethodIndex, repositoryMethodIndex, allMethodIndex);

            if (target != null) {
                // Cycle detection
                String targetKey = (target.getClassName() != null ? target.getClassName() : "") + "#" + target.getMethodName();
                if (visited.contains(targetKey)) {
                    log.debug("[neo4j-builder] Cycle detected: {} -> {}", methodNode.getMethodName(), targetKey);
                    // Still add the edge but don't recurse
                    methodNode.getCalls().add(target);
                    continue;
                }

                methodNode.getCalls().add(target);

                // Recursively resolve the target's calls if it has unresolved invocations
                visited.add(targetKey);
                ParsedMethod targetParsedMethod = findParsedMethod(target, parsedApp);
                if (targetParsedMethod != null && !targetParsedMethod.getRawInvocations().isEmpty()) {
                    ParsedComponent targetComponent = findComponentByClassName(target.getClassName(), parsedApp);
                    if (targetComponent != null) {
                        resolveInvocationsForMethod(targetParsedMethod.getRawInvocations(), target,
                                targetComponent, parsedApp, serviceMethodIndex, repositoryMethodIndex,
                                allMethodIndex, new HashSet<>(visited));
                    }
                }
            } else {
                log.debug("[neo4j-builder] Could not resolve: {}.{} -> {}.{}",
                        ownerComponent.getClassName(), methodNode.getMethodName(),
                        raw.getDeclaredTypeSimple(), raw.getMethodName());
            }
        }
    }

    /**
     * Resolve a single raw invocation to a MethodNode.
     *
     * Resolution strategy (in order):
     * 1. Self-call: look up in the same class
     * 2. Field-based: use injected dependency map to find the concrete type
     * 3. Interface-based: use interfaceToImplMap
     * 4. Direct class lookup
     * 5. Method-name-only fallback (if unique)
     */
    private MethodNode resolveRawInvocation(RawInvocation raw, ParsedComponent ownerComponent,
                                             ParsedApplication parsedApp,
                                             Map<String, MethodNode> serviceMethodIndex,
                                             Map<String, MethodNode> repositoryMethodIndex,
                                             Map<String, MethodNode> allMethodIndex) {
        String methodName = raw.getMethodName();

        // 1. Self-call resolution
        if (raw.isSelfCall()) {
            String selfKey = ownerComponent.getClassName() + "#" + methodName;
            MethodNode selfTarget = serviceMethodIndex.get(selfKey);
            if (selfTarget != null) return selfTarget;

            String selfFqKey = ownerComponent.getQualifiedName() + "#" + methodName;
            selfTarget = serviceMethodIndex.get(selfFqKey);
            if (selfTarget != null) return selfTarget;
        }

        // 2. Field-based resolution using DI map
        if (raw.getTargetFieldName() != null) {
            InjectedDependency dep = ownerComponent.getInjectedDependencies().get(raw.getTargetFieldName());
            if (dep != null && dep.getResolvedTypeQualified() != null) {
                // Try resolved concrete type
                String resolvedKey = dep.getResolvedTypeSimple() + "#" + methodName;
                MethodNode target = allMethodIndex.get(resolvedKey);
                if (target != null) return target;

                String resolvedFqKey = dep.getResolvedTypeQualified() + "#" + methodName;
                target = allMethodIndex.get(resolvedFqKey);
                if (target != null) return target;

                // Also try repository index with resolved type
                target = repositoryMethodIndex.get(resolvedKey);
                if (target != null) return target;
                target = repositoryMethodIndex.get(resolvedFqKey);
                if (target != null) return target;
            }
        }

        // 3. Interface-based resolution
        String declaredSimple = raw.getDeclaredTypeSimple();
        String declaredQualified = raw.getDeclaredTypeQualified();

        // Try interface key directly
        String ifaceKey = declaredSimple + "#" + methodName;
        MethodNode ifaceTarget = serviceMethodIndex.get(ifaceKey);
        if (ifaceTarget != null) return ifaceTarget;

        ifaceKey = declaredQualified + "#" + methodName;
        ifaceTarget = serviceMethodIndex.get(ifaceKey);
        if (ifaceTarget != null) return ifaceTarget;

        // Look up interface -> implementation
        List<String> impls = parsedApp.getInterfaceToImplMap().get(declaredSimple);
        if (impls == null) impls = parsedApp.getInterfaceToImplMap().get(declaredQualified);
        if (impls != null && !impls.isEmpty()) {
            for (String implQualified : impls) {
                String implSimple = extractSimpleName(implQualified);
                MethodNode target = allMethodIndex.get(implSimple + "#" + methodName);
                if (target != null) return target;
                target = allMethodIndex.get(implQualified + "#" + methodName);
                if (target != null) return target;
            }
        }

        // 4. Direct class lookup
        MethodNode directTarget = allMethodIndex.get(declaredSimple + "#" + methodName);
        if (directTarget != null) return directTarget;
        directTarget = allMethodIndex.get(declaredQualified + "#" + methodName);
        if (directTarget != null) return directTarget;

        // Try repository index directly
        directTarget = repositoryMethodIndex.get(declaredSimple + "#" + methodName);
        if (directTarget != null) return directTarget;
        directTarget = repositoryMethodIndex.get(declaredQualified + "#" + methodName);
        if (directTarget != null) return directTarget;

        // 5. Method-name-only fallback (if unique)
        MethodNode byName = allMethodIndex.get(methodName);
        if (byName != null) return byName;

        return null;
    }

    // ========================= Controller Building =========================

    private Set<ControllerNode> buildControllerNodes(List<ParsedComponent> controllers,
                                                      String projectId, String appKey,
                                                      ParsedApplication parsedApp,
                                                      Map<String, MethodNode> serviceMethodIndex,
                                                      Map<String, MethodNode> allMethodIndex) {
        Set<ControllerNode> nodes = new HashSet<>();
        if (controllers == null) return nodes;

        for (ParsedComponent controller : controllers) {
            ControllerNode controllerNode = ControllerNode.builder()
                    .className(controller.getClassName())
                    .packageName(controller.getPackageName())
                    .canonicalId(canonicalIdGenerator.generateControllerCanonicalId(
                            controller.getPackageName(), controller.getClassName()))
                    .baseUrl(controller.getBaseUrl())
                    .projectId(projectId)
                    .appKey(appKey)
                    .lineStart(controller.getLineStart())
                    .lineEnd(controller.getLineEnd())
                    .endpoints(new HashSet<>())
                    .build();

            for (ParsedMethod pm : controller.getMethods()) {
                if (pm.getHttpMethod() == null) continue; // Skip non-endpoint methods

                EndpointNode endpointNode = EndpointNode.builder()
                        .httpMethod(pm.getHttpMethod())
                        .path(pm.getPath())
                        .fullPath(pm.getPath())
                        .canonicalId(canonicalIdGenerator.generateEndpointCanonicalId(
                                pm.getHttpMethod(), pm.getPath()))
                        .handlerMethod(pm.getMethodName())
                        .signature(pm.getSignature())
                        .projectId(projectId)
                        .appKey(appKey)
                        .controllerClass(controller.getClassName())
                        .lineStart(pm.getLineStart())
                        .lineEnd(pm.getLineEnd())
                        .requestBodyType(pm.getRequestBodyType())
                        .responseType(pm.getResponseType())
                        .calls(new HashSet<>())
                        .externalCalls(new HashSet<>())
                        .producesToTopics(new HashSet<>())
                        .build();

                // Resolve endpoint -> service method calls
                for (RawInvocation raw : pm.getRawInvocations()) {
                    MethodNode target = resolveRawInvocation(raw, controller, parsedApp,
                            serviceMethodIndex, new HashMap<>(), allMethodIndex);
                    if (target != null) {
                        endpointNode.getCalls().add(target);
                    }
                }

                // External calls from endpoint
                for (ParsedExternalCall ext : pm.getExternalCalls()) {
                    endpointNode.getExternalCalls().add(buildExternalCallNode(ext, projectId, appKey));
                }

                // Kafka calls from endpoint
                for (ParsedKafkaCall kc : pm.getKafkaCalls()) {
                    KafkaTopicNode topicNode = getOrCreateKafkaTopic(kc.getTopicName(), projectId, appKey);
                    String detail = String.format("%s.%s (line %d)", kc.getClassName(), kc.getMethodName(), kc.getLineStart());
                    if (!topicNode.getProducerDetails().contains(detail)) topicNode.getProducerDetails().add(detail);
                    endpointNode.getProducesToTopics().add(topicNode);
                }

                controllerNode.getEndpoints().add(endpointNode);
            }

            nodes.add(controllerNode);
        }
        return nodes;
    }

    // ========================= Kafka Listener Building =========================

    private Set<KafkaListenerNode> buildKafkaListenerNodes(List<ParsedComponent> kafkaListeners,
                                                            String projectId, String appKey,
                                                            ParsedApplication parsedApp,
                                                            Map<String, MethodNode> serviceMethodIndex,
                                                            Map<String, MethodNode> allMethodIndex) {
        Set<KafkaListenerNode> nodes = new HashSet<>();
        if (kafkaListeners == null) return nodes;

        for (ParsedComponent listener : kafkaListeners) {
            KafkaListenerNode listenerNode = KafkaListenerNode.builder()
                    .className(listener.getClassName())
                    .packageName(listener.getPackageName())
                    .projectId(projectId)
                    .appKey(appKey)
                    .lineStart(listener.getLineStart())
                    .lineEnd(listener.getLineEnd())
                    .listenerMethods(new HashSet<>())
                    .build();

            for (ParsedKafkaListenerMethod klm : listener.getKafkaListenerMethods()) {
                KafkaListenerMethodNode methodNode = KafkaListenerMethodNode.builder()
                        .methodName(klm.getMethodName())
                        .signature(klm.getSignature())
                        .topic(klm.getTopic())
                        .groupId(klm.getGroupId())
                        .projectId(projectId)
                        .appKey(appKey)
                        .listenerClass(listener.getClassName())
                        .lineStart(klm.getLineStart())
                        .lineEnd(klm.getLineEnd())
                        .calls(new HashSet<>())
                        .externalCalls(new HashSet<>())
                        .consumesFromTopics(new HashSet<>())
                        .producesToTopics(new HashSet<>())
                        .build();

                // Resolve method calls within kafka listener
                for (RawInvocation raw : klm.getRawInvocations()) {
                    MethodNode target = resolveRawInvocation(raw, listener, parsedApp,
                            serviceMethodIndex, new HashMap<>(), allMethodIndex);
                    if (target != null) {
                        methodNode.getCalls().add(target);
                    }
                }

                // External calls
                for (ParsedExternalCall ext : klm.getExternalCalls()) {
                    methodNode.getExternalCalls().add(buildExternalCallNode(ext, projectId, appKey));
                }

                // Kafka topic consumption
                if (klm.getTopic() != null && !klm.getTopic().isEmpty()) {
                    KafkaTopicNode topicNode = getOrCreateKafkaTopic(klm.getTopic(), projectId, appKey);
                    String detail = String.format("%s.%s (line %d)",
                            listener.getClassName(), klm.getMethodName(), klm.getLineStart());
                    if (!topicNode.getConsumerDetails().contains(detail)) {
                        topicNode.getConsumerDetails().add(detail);
                    }
                    if (!topicNode.getConsumerServiceNames().contains(listener.getClassName())) {
                        topicNode.getConsumerServiceNames().add(listener.getClassName());
                    }
                    methodNode.getConsumesFromTopics().add(topicNode);
                }

                // Kafka producer calls
                for (ParsedKafkaCall kc : klm.getKafkaCalls()) {
                    KafkaTopicNode topicNode = getOrCreateKafkaTopic(kc.getTopicName(), projectId, appKey);
                    String detail = String.format("%s.%s (line %d)", kc.getClassName(), kc.getMethodName(), kc.getLineStart());
                    if (!topicNode.getProducerDetails().contains(detail)) topicNode.getProducerDetails().add(detail);
                    methodNode.getProducesToTopics().add(topicNode);
                }

                listenerNode.getListenerMethods().add(methodNode);
            }

            nodes.add(listenerNode);
        }
        return nodes;
    }

    // ========================= Configuration Building =========================

    private Set<ConfigurationNode> buildConfigurationNodes(List<ParsedComponent> configurations,
                                                            String projectId, String appKey) {
        Set<ConfigurationNode> nodes = new HashSet<>();
        if (configurations == null) return nodes;

        for (ParsedComponent config : configurations) {
            ConfigurationNode configNode = ConfigurationNode.builder()
                    .className(config.getClassName())
                    .packageName(config.getPackageName())
                    .projectId(projectId)
                    .appKey(appKey)
                    .lineStart(config.getLineStart())
                    .lineEnd(config.getLineEnd())
                    .beans(new HashSet<>())
                    .build();

            for (ParsedBean bean : config.getBeans()) {
                BeanNode beanNode = BeanNode.builder()
                        .beanName(bean.getBeanName())
                        .methodName(bean.getMethodName())
                        .returnType(bean.getReturnType())
                        .projectId(projectId)
                        .appKey(appKey)
                        .lineStart(bean.getLineStart())
                        .lineEnd(bean.getLineEnd())
                        .build();
                configNode.getBeans().add(beanNode);
            }

            nodes.add(configNode);
        }
        return nodes;
    }

    // ========================= Helper Methods =========================

    private ExternalCallNode buildExternalCallNode(ParsedExternalCall ext, String projectId, String appKey) {
        return ExternalCallNode.builder()
                .clientType(ext.getClientType())
                .httpMethod(ext.getHttpMethod())
                .url(ext.getUrl())
                .canonicalId(canonicalIdGenerator.generateExternalCallCanonicalId(
                        ext.getHttpMethod(), ext.getUrl(), false))
                .targetClass(ext.getTargetClass())
                .targetMethod(ext.getTargetMethod())
                .projectId(projectId)
                .appKey(appKey)
                .lineStart(ext.getLineStart())
                .lineEnd(ext.getLineEnd())
                .resolved(false)
                .build();
    }

    private KafkaTopicNode getOrCreateKafkaTopic(String topicName, String projectId, String appKey) {
        if (topicName == null || topicName.isEmpty()) {
            topicName = "<unknown>";
        }
        final String finalTopicName = topicName;
        return kafkaTopicNodeRepository.findByProjectIdAndName(projectId, finalTopicName)
                .orElseGet(() -> {
                    KafkaTopicNode node = KafkaTopicNode.builder()
                            .name(finalTopicName)
                            .projectId(projectId)
                            .appKey(appKey)
                            .build();
                    node.setCanonicalId(canonicalIdGenerator.generateKafkaTopicCanonicalId(finalTopicName));
                    return node;
                });
    }

    private void indexMethod(Map<String, MethodNode> index, MethodNode methodNode, String qualifiedClassName) {
        // Index by simple class#method
        String simpleKey = methodNode.getClassName() + "#" + methodNode.getMethodName();
        index.putIfAbsent(simpleKey, methodNode);

        // Index by qualified class#method
        String fqKey = qualifiedClassName + "#" + methodNode.getMethodName();
        index.putIfAbsent(fqKey, methodNode);

        // Index by signature
        if (methodNode.getSignature() != null) {
            index.putIfAbsent(methodNode.getSignature(), methodNode);
        }

        // Index by method name only (used as last-resort fallback)
        index.putIfAbsent(methodNode.getMethodName(), methodNode);
    }

    private MethodNode findMethodNode(Map<String, MethodNode> index, ParsedComponent component, ParsedMethod pm) {
        String key = component.getClassName() + "#" + pm.getMethodName();
        MethodNode node = index.get(key);
        if (node != null) return node;

        key = component.getQualifiedName() + "#" + pm.getMethodName();
        node = index.get(key);
        if (node != null) return node;

        if (pm.getSignature() != null) {
            return index.get(pm.getSignature());
        }
        return null;
    }

    private ParsedMethod findParsedMethod(MethodNode methodNode, ParsedApplication parsedApp) {
        // Find the ParsedComponent that contains this method
        String className = methodNode.getClassName();
        if (className == null) return null;

        ParsedComponent component = parsedApp.getComponentIndex().get(className);
        if (component == null) return null;

        return component.getMethods().stream()
                .filter(pm -> pm.getMethodName().equals(methodNode.getMethodName()))
                .findFirst()
                .orElse(null);
    }

    private ParsedComponent findComponentByClassName(String className, ParsedApplication parsedApp) {
        if (className == null) return null;
        return parsedApp.getComponentIndex().get(className);
    }

    private String buildAppKey(String repoUrl, ParsedApplication parsedApp) {
        if (parsedApp.isSpringBoot()
                && parsedApp.getMainClassName() != null
                && parsedApp.getMainClassPackage() != null) {
            return parsedApp.getMainClassPackage() + "." + parsedApp.getMainClassName();
        }
        return repoUrl + "::NON_SPRING";
    }

    private String extractSimpleName(String fullyQualifiedName) {
        if (fullyQualifiedName == null || fullyQualifiedName.isEmpty()) return "";
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot > 0 ? fullyQualifiedName.substring(lastDot + 1) : fullyQualifiedName;
    }
}
