package com.architecture.memory.orkestify.service.graph;

import com.architecture.memory.orkestify.dto.*;
import com.architecture.memory.orkestify.model.graph.nodes.*;
import com.architecture.memory.orkestify.repository.graph.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class GraphPersistenceService {

    private final ApplicationNodeRepository applicationNodeRepository;
    private final KafkaTopicNodeRepository kafkaTopicNodeRepository;

    /**
     * Persist a code analysis result to the Neo4j graph database.
     * This creates all nodes and relationships in a single transaction.
     */
    @Transactional
    public void persistAnalysis(String projectId, String userId, String repoUrl, CodeAnalysisResponse analysis) {
        log.info("[graph-persist] begin projectId={} repoUrl={} controllers={} services={} repos={} kafkaListeners={}",
                projectId, repoUrl,
                analysis.getControllers() != null ? analysis.getControllers().size() : 0,
                analysis.getServices() != null ? analysis.getServices().size() : 0,
                analysis.getRepositories() != null ? analysis.getRepositories().size() : 0,
                analysis.getKafkaListeners() != null ? analysis.getKafkaListeners().size() : 0);

        String appKey = buildAppKey(repoUrl, analysis.getApplicationInfo());

        // Check if application already exists (upsert logic)
        Optional<ApplicationNode> existingApp = applicationNodeRepository.findByProjectIdAndAppKey(projectId, appKey);

        ApplicationNode applicationNode;
        if (existingApp.isPresent()) {
            log.info("Updating existing application node: {}", appKey);
            applicationNode = existingApp.get();
            // Clear existing relationships for update
            applicationNode.getControllers().clear();
            applicationNode.getServices().clear();
            applicationNode.getRepositories().clear();
            applicationNode.getKafkaListeners().clear();
            applicationNode.getConfigurations().clear();
        } else {
            log.info("Creating new application node: {}", appKey);
            applicationNode = ApplicationNode.builder()
                    .projectId(projectId)
                    .repoUrl(repoUrl)
                    .appKey(appKey)
                    .controllers(new HashSet<>())
                    .services(new HashSet<>())
                    .repositories(new HashSet<>())
                    .kafkaListeners(new HashSet<>())
                    .configurations(new HashSet<>())
                    .isSpringBoot(true).build();
        }

        // Set application info
        ApplicationInfo appInfo = analysis.getApplicationInfo();
        if (appInfo != null) {
            applicationNode.setMainClassName(appInfo.getMainClassName());
            applicationNode.setMainClassPackage(appInfo.getMainClassPackage());
            applicationNode.setSpringBoot(appInfo.isSpringBootApplication());
            applicationNode.setRootPath(appInfo.getRootPath());
            if (appInfo.getLine() != null) {
                applicationNode.setLineStart(appInfo.getLine().getStart());
                applicationNode.setLineEnd(appInfo.getLine().getEnd());
            }
        }
        applicationNode.setAnalyzedAt(analysis.getAnalyzedAt());
        applicationNode.setStatus(analysis.getStatus());

        // Build and add minimal component nodes: controllers -> endpoints -> service methods
        Map<String, MethodNode> repositoryMethodIndex = new HashMap<>();
        Set<RepositoryClassNode> repositoryNodes = buildRepositoryNodes(analysis.getRepositories(), projectId, appKey, repositoryMethodIndex);
        applicationNode.getRepositories().addAll(repositoryNodes);

        Map<String, MethodNode> serviceMethodIndex = new HashMap<>();
        Set<ServiceNode> serviceNodes = buildServiceNodes(analysis.getServices(), projectId, appKey, serviceMethodIndex, repositoryMethodIndex);
        applicationNode.getServices().addAll(serviceNodes);

        Set<ControllerNode> controllerNodes = buildControllerNodes(analysis.getControllers(), projectId, appKey, serviceMethodIndex);
        applicationNode.getControllers().addAll(controllerNodes);

        // Build and add Kafka listeners
        Set<KafkaListenerNode> kafkaListenerNodes = buildKafkaListenerNodes(analysis.getKafkaListeners(), projectId, appKey);
        applicationNode.getKafkaListeners().addAll(kafkaListenerNodes);

        log.info("[graph-persist] saving application node {}", applicationNode.getAppKey());
        applicationNodeRepository.save(applicationNode);
        log.info("[graph-persist] saved application node {}", applicationNode.getAppKey());

        log.info("[graph-persist] persisting controllers:{} services:{} repositories:{} kafkaListeners:{} configurations:{}",
                controllerNodes.size(), serviceNodes.size(), repositoryNodes.size(), kafkaListenerNodes.size());

        // Save the entire graph (Neo4j will cascade save all related nodes)
        applicationNodeRepository.save(applicationNode);

        log.info("[graph-persist] end projectId={} appKey={}", projectId, applicationNode.getAppKey());
    }

    /**
     * Delete all graph data for a project
     */
    @Transactional
    public void deleteProjectGraph(String projectId) {
        log.info("Deleting Neo4j graph for project: {}", projectId);
        applicationNodeRepository.deleteApplicationGraphByProjectId(projectId);
    }

    // ========================= Controller Building =========================

    private Set<ControllerNode> buildControllerNodes(List<ControllerInfo> controllers, String projectId, String appKey, Map<String, MethodNode> serviceMethodIndex) {
        Set<ControllerNode> nodes = new HashSet<>();
        if (controllers == null) return nodes;

        for (ControllerInfo controller : controllers) {
            ControllerNode controllerNode = ControllerNode.builder()
                    .className(controller.getClassName())
                    .packageName(controller.getPackageName())
                    .projectId(projectId)
                    .appKey(appKey)
                    .endpoints(new HashSet<>())
                    .build();

            if (controller.getLine() != null) {
                controllerNode.setLineStart(controller.getLine().getStart());
                controllerNode.setLineEnd(controller.getLine().getEnd());
            }

            // Build endpoints
            Set<EndpointNode> endpointNodes = buildEndpointNodes(
                    controller.getEndpoints(), projectId, appKey, controller.getClassName(), serviceMethodIndex);
            controllerNode.getEndpoints().addAll(endpointNodes);

            // Extract base URL from first endpoint if available
            if (controller.getEndpoints() != null && !controller.getEndpoints().isEmpty()) {
                String firstPath = controller.getEndpoints().get(0).getPath();
                if (firstPath != null && firstPath.contains("/")) {
                    // Simple heuristic to extract base URL
                    controllerNode.setBaseUrl(extractBaseUrl(firstPath));
                }
            }

            nodes.add(controllerNode);
        }
        return nodes;
    }

    private Set<EndpointNode> buildEndpointNodes(List<EndpointInfo> endpoints, String projectId,
                                                   String appKey, String controllerClass, Map<String, MethodNode> serviceMethodIndex) {
        Set<EndpointNode> nodes = new HashSet<>();
        if (endpoints == null) return nodes;

        for (EndpointInfo endpoint : endpoints) {
            EndpointNode endpointNode = EndpointNode.builder()
                    .httpMethod(endpoint.getMethod())
                    .path(endpoint.getPath())
                    .fullPath(endpoint.getPath()) // Can be enhanced to include base URL
                    .handlerMethod(endpoint.getHandlerMethod())
                    .signature(endpoint.getSignature())
                    .projectId(projectId)
                    .appKey(appKey)
                    .controllerClass(controllerClass)
                    .calls(new HashSet<>())
                    .externalCalls(new HashSet<>())
                    .producesToTopics(new HashSet<>())
                    .build();

            if (endpoint.getLine() != null) {
                endpointNode.setLineStart(endpoint.getLine().getStart());
                endpointNode.setLineEnd(endpoint.getLine().getEnd());
            }

            if (endpoint.getRequestBody() != null) {
                endpointNode.setRequestBodyType(endpoint.getRequestBody().getType());
            }

            if (endpoint.getResponse() != null) {
                endpointNode.setResponseType(endpoint.getResponse().getType());
            }

            // Link endpoint directly to resolved service methods; ignore other call targets for this iteration
            if (endpoint.getCalls() != null) {
                for (MethodCall call : endpoint.getCalls()) {
                    MethodNode target = resolveServiceMethod(call, serviceMethodIndex);
                    if (target != null) {
                        endpointNode.getCalls().add(target);
                    } else {
                        log.debug("Skipped unresolved service call from endpoint {} {} to {}", endpoint.getMethod(), endpoint.getPath(), call.getSignature());
                    }
                }
            }

            nodes.add(endpointNode);
        }
        return nodes;
    }

    // ========================= Service Building =========================

    private Set<ServiceNode> buildServiceNodes(List<ServiceInfo> services, String projectId, String appKey, Map<String, MethodNode> serviceMethodIndex, Map<String, MethodNode> repositoryMethodIndex) {
        Set<ServiceNode> nodes = new HashSet<>();
        if (services == null) return nodes;
        log.info("++++++++++++++++++++++++++++++++++++");
        for (ServiceInfo service : services) {
            ServiceNode serviceNode = ServiceNode.builder()
                    .className(service.getClassName())
                    .packageName(service.getPackageName())
                    .projectId(projectId)
                    .appKey(appKey)
                    .methods(new HashSet<>())
                    .build();

            if (service.getLine() != null) {
                serviceNode.setLineStart(service.getLine().getStart());
                serviceNode.setLineEnd(service.getLine().getEnd());
            }

            // Build service methods (initially without resolving calls to repositories)
            Set<MethodNode> methodNodes = buildMethodNodesFromMethodInfo(
                    service.getMethods(), projectId, appKey, service.getClassName(), "SERVICE_METHOD");
            serviceNode.getMethods().addAll(methodNodes);

            String fullClassName = service.getPackageName() != null
                    ? service.getPackageName() + "." + service.getClassName()
                    : service.getClassName();
            // Index service methods by signature and class#method (simple + FQCN)
            for (MethodNode methodNode : methodNodes) {
                if (methodNode.getSignature() != null) {
                    serviceMethodIndex.put(methodNode.getSignature(), methodNode);
                }
                String key = buildMethodKey(methodNode.getClassName(), methodNode.getMethodName());
                if (key != null) {
                    serviceMethodIndex.put(key, methodNode);
                }
                String fqKey = buildMethodKey(fullClassName, methodNode.getMethodName());
                if (fqKey != null) {
                    serviceMethodIndex.put(fqKey, methodNode);
                }
                serviceMethodIndex.putIfAbsent(methodNode.getMethodName(), methodNode);
            }

            // Link service methods to repository or other service methods using captured MethodInfo calls
            if (service.getMethods() != null) {
                for (MethodInfo methodInfo : service.getMethods()) {
                    if (methodInfo.getMethodName().equals("validateExceeding100")){
                        System.out.println("In");
                    }
                    System.out.println("Started" +methodInfo.getMethodName());
                    MethodNode methodNode = resolveServiceMethodFromInfo(methodInfo, serviceMethodIndex, fullClassName, service.getClassName());
                    if (methodNode == null) continue;

                    methodNode.getCalls().clear();
                    if (methodInfo.getCalls() == null) continue;

                    for (MethodCall call : methodInfo.getCalls()) {
                        MethodNode repoTarget = resolveRepositoryMethod(call, repositoryMethodIndex);
                        if (repoTarget != null) {
                            methodNode.getCalls().add(repoTarget);
                            continue;
                        }
                        MethodNode serviceTarget = resolveServiceMethod(call, serviceMethodIndex);
                        if (serviceTarget != null) {
                            methodNode.getCalls().add(serviceTarget);
                        }
                    }
                    System.out.println("ended" +methodInfo.getMethodName());
                }
            }

            nodes.add(serviceNode);
        }
        return nodes;
    }

    // ========================= Repository Building =========================

    private Set<RepositoryClassNode> buildRepositoryNodes(List<RepositoryInfo> repositories,
                                                           String projectId, String appKey, Map<String, MethodNode> repositoryMethodIndex) {
        Set<RepositoryClassNode> nodes = new HashSet<>();
        if (repositories == null) return nodes;

        for (RepositoryInfo repository : repositories) {
            RepositoryClassNode repoNode = RepositoryClassNode.builder()
                    .className(repository.getClassName())
                    .packageName(repository.getPackageName())
                    .repositoryType(repository.getRepositoryType())
                    .extendsClass(repository.getExtendsClass())
                    .projectId(projectId)
                    .appKey(appKey)
                    .methods(new HashSet<>())
                    .build();

            if (repository.getLine() != null) {
                repoNode.setLineStart(repository.getLine().getStart());
                repoNode.setLineEnd(repository.getLine().getEnd());
            }

            // Build repository methods
            Set<MethodNode> methodNodes = buildMethodNodesFromMethodInfo(
                    repository.getMethods(), projectId, appKey, repository.getClassName(), "REPOSITORY_METHOD");
            repoNode.getMethods().addAll(methodNodes);

            // Build database table relationship
            if (repository.getDatabaseOperations() != null) {
                DatabaseTableNode tableNode = buildDatabaseTableNode(
                        repository.getDatabaseOperations(), projectId, appKey);
                repoNode.getAccessesTables().add(tableNode);
            }

            // Index repository methods by multiple keys for robust resolution
            String fullClassName = repository.getPackageName() != null
                    ? repository.getPackageName() + "." + repository.getClassName()
                    : repository.getClassName();
            for (MethodNode methodNode : methodNodes) {
                if (methodNode.getSignature() != null) {
                    repositoryMethodIndex.put(methodNode.getSignature(), methodNode);
                }
                String keySimple = buildMethodKey(methodNode.getClassName(), methodNode.getMethodName());
                if (keySimple != null) {
                    repositoryMethodIndex.put(keySimple, methodNode);
                }
                String keyFqcn = buildMethodKey(fullClassName, methodNode.getMethodName());
                if (keyFqcn != null) {
                    repositoryMethodIndex.put(keyFqcn, methodNode);
                }
                // Method-name-only fallback (may be overwritten; used only when unique)
                repositoryMethodIndex.putIfAbsent(methodNode.getMethodName(), methodNode);
            }

            nodes.add(repoNode);
        }
        return nodes;
    }

    private DatabaseTableNode buildDatabaseTableNode(DatabaseOperationInfo dbOps, String projectId, String appKey) {
        return DatabaseTableNode.builder()
                .tableName(dbOps.getTableName())
                .entityClass(dbOps.getEntityClass())
                .entitySimpleName(dbOps.getEntitySimpleName())
                .databaseType(dbOps.getDatabaseType())
                .tableSource(dbOps.getTableSource())
                .operations(dbOps.getOperations())
                .projectId(projectId)
                .appKey(appKey)
                .build();
    }

    // ========================= Kafka Listener Building =========================

    private Set<KafkaListenerNode> buildKafkaListenerNodes(List<KafkaListenerInfo> kafkaListeners,
                                                            String projectId, String appKey) {
        Set<KafkaListenerNode> nodes = new HashSet<>();
        if (kafkaListeners == null) return nodes;

        for (KafkaListenerInfo listener : kafkaListeners) {
            KafkaListenerNode listenerNode = KafkaListenerNode.builder()
                    .className(listener.getClassName())
                    .packageName(listener.getPackageName())
                    .projectId(projectId)
                    .appKey(appKey)
                    .listenerMethods(new HashSet<>())
                    .build();

            if (listener.getLine() != null) {
                listenerNode.setLineStart(listener.getLine().getStart());
                listenerNode.setLineEnd(listener.getLine().getEnd());
            }

            // Build listener methods
            Set<KafkaListenerMethodNode> listenerMethodNodes = buildKafkaListenerMethodNodes(
                    listener.getListeners(), projectId, appKey, listener.getClassName());
            listenerNode.getListenerMethods().addAll(listenerMethodNodes);

            nodes.add(listenerNode);
        }
        return nodes;
    }

    private Set<KafkaListenerMethodNode> buildKafkaListenerMethodNodes(List<KafkaListenerMethod> methods,
                                                                        String projectId, String appKey,
                                                                        String listenerClass) {
        Set<KafkaListenerMethodNode> nodes = new HashSet<>();
        if (methods == null) return nodes;

        for (KafkaListenerMethod method : methods) {
            KafkaListenerMethodNode methodNode = KafkaListenerMethodNode.builder()
                    .methodName(method.getMethodName())
                    .signature(method.getSignature())
                    .topic(method.getTopic())
                    .groupId(method.getGroupId())
                    .projectId(projectId)
                    .appKey(appKey)
                    .listenerClass(listenerClass)
                    .calls(new HashSet<>())
                    .externalCalls(new HashSet<>())
                    .consumesFromTopics(new HashSet<>())
                    .build();

            if (method.getLine() != null) {
                methodNode.setLineStart(method.getLine().getStart());
                methodNode.setLineEnd(method.getLine().getEnd());
            }

            // Build method calls
            Set<MethodNode> calledMethods = buildMethodNodesFromCalls(
                    method.getCalls(), projectId, appKey, "LISTENER_CALL");
            methodNode.getCalls().addAll(calledMethods);

            // Build external calls
            Set<ExternalCallNode> externalCallNodes = buildExternalCallNodes(
                    method.getExternalCalls(), projectId, appKey);
            methodNode.getExternalCalls().addAll(externalCallNodes);

            // Build Kafka topic consumption relationship
            if (method.getTopic() != null && !method.getTopic().isEmpty()) {
                KafkaTopicNode topicNode = getOrCreateKafkaTopic(method.getTopic(), projectId, appKey);

                // Add consumer metadata to topic node
                String detail = String.format("%s.%s (line %d)",
                        listenerClass,
                        method.getMethodName(),
                        method.getLine() != null ? method.getLine().getStart() : 0);
                if (!topicNode.getConsumerDetails().contains(detail)) {
                    topicNode.getConsumerDetails().add(detail);
                }
                if (!topicNode.getConsumerServiceNames().contains(listenerClass)) {
                    topicNode.getConsumerServiceNames().add(listenerClass);
                }

                methodNode.getConsumesFromTopics().add(topicNode);
            }

            nodes.add(methodNode);
        }
        return nodes;
    }

    // ========================= Configuration Building =========================

    private Set<ConfigurationNode> buildConfigurationNodes(List<ConfigurationInfo> configurations,
                                                            String projectId, String appKey) {
        Set<ConfigurationNode> nodes = new HashSet<>();
        if (configurations == null) return nodes;

        for (ConfigurationInfo config : configurations) {
            ConfigurationNode configNode = ConfigurationNode.builder()
                    .className(config.getClassName())
                    .packageName(config.getPackageName())
                    .projectId(projectId)
                    .appKey(appKey)
                    .beans(new HashSet<>())
                    .build();

            if (config.getLine() != null) {
                configNode.setLineStart(config.getLine().getStart());
                configNode.setLineEnd(config.getLine().getEnd());
            }

            // Build bean nodes
            Set<BeanNode> beanNodes = buildBeanNodes(config.getBeans(), projectId, appKey);
            configNode.getBeans().addAll(beanNodes);

            nodes.add(configNode);
        }
        return nodes;
    }

    private Set<BeanNode> buildBeanNodes(List<BeanInfo> beans, String projectId, String appKey) {
        Set<BeanNode> nodes = new HashSet<>();
        if (beans == null) return nodes;

        for (BeanInfo bean : beans) {
            BeanNode beanNode = BeanNode.builder()
                    .beanName(bean.getBeanName())
                    .methodName(bean.getMethodName())
                    .returnType(bean.getReturnType())
                    .projectId(projectId)
                    .appKey(appKey)
                    .build();

            if (bean.getLine() != null) {
                beanNode.setLineStart(bean.getLine().getStart());
                beanNode.setLineEnd(bean.getLine().getEnd());
            }

            nodes.add(beanNode);
        }
        return nodes;
    }

    // ========================= Method Call Building =========================

    private Set<MethodNode> buildMethodNodesFromCalls(List<MethodCall> calls, String projectId,
                                                       String appKey, String methodType) {
        return buildMethodNodesFromCalls(calls, projectId, appKey, methodType, new HashSet<>());
    }

    private Set<MethodNode> buildMethodNodesFromCalls(List<MethodCall> calls, String projectId,
                                                       String appKey, String methodType, Set<String> seen) {
        Set<MethodNode> nodes = new HashSet<>();
        if (calls == null) return nodes;

        for (MethodCall call : calls) {
            String key = buildMethodKey(call.getClassName(), call.getHandlerMethod());
            if (key == null) key = call.getSignature();
            if (key != null && !seen.add(key)) {
                // Already visited this method in the current path; avoid cycles
                continue;
            }

            MethodNode methodNode = MethodNode.builder()
                    .className(call.getClassName())
                    .methodName(call.getHandlerMethod())
                    .signature(call.getSignature())
                    .projectId(projectId)
                    .appKey(appKey)
                    .methodType(methodType)
                    .calls(new HashSet<>())
                    .externalCalls(new HashSet<>())
                    .producesToTopics(new HashSet<>())
                    .build();

            if (call.getLine() != null) {
                methodNode.setLineStart(call.getLine().getStart());
                methodNode.setLineEnd(call.getLine().getEnd());
            }

            // Recursively build nested calls with cycle detection
            Set<MethodNode> nestedCalls = buildMethodNodesFromCalls(
                    call.getCalls(), projectId, appKey, methodType, new HashSet<>(seen));
            methodNode.getCalls().addAll(nestedCalls);

            // Build external calls
            Set<ExternalCallNode> externalCallNodes = buildExternalCallNodes(
                    call.getExternalCalls(), projectId, appKey);
            methodNode.getExternalCalls().addAll(externalCallNodes);

            // Build Kafka producer relationships
            Set<KafkaTopicNode> producerTopics = buildKafkaTopicNodesFromCalls(
                    call.getKafkaCalls(), projectId, "PRODUCER");
            methodNode.getProducesToTopics().addAll(producerTopics);

            nodes.add(methodNode);
        }
        return nodes;
    }

    private Set<MethodNode> buildMethodNodesFromMethodInfo(List<MethodInfo> methods, String projectId,
                                                            String appKey, String className, String methodType) {
        Set<MethodNode> nodes = new HashSet<>();
        if (methods == null) return nodes;

        for (MethodInfo method : methods) {
            MethodNode methodNode = MethodNode.builder()
                    .className(className)
                    .methodName(method.getMethodName())
                    .signature(method.getSignature())
                    .projectId(projectId)
                    .appKey(appKey)
                    .methodType(methodType)
                    .calls(new HashSet<>())
                    .externalCalls(new HashSet<>())
                    .producesToTopics(new HashSet<>())
                    .build();

            if (method.getLine() != null) {
                methodNode.setLineStart(method.getLine().getStart());
                methodNode.setLineEnd(method.getLine().getEnd());
            }

            // Build nested calls
            Set<MethodNode> nestedCalls = buildMethodNodesFromCalls(
                    method.getCalls(), projectId, appKey, methodType);
            methodNode.getCalls().addAll(nestedCalls);

            // Build external calls
            Set<ExternalCallNode> externalCallNodes = buildExternalCallNodes(
                    method.getExternalCalls(), projectId, appKey);
            methodNode.getExternalCalls().addAll(externalCallNodes);

            // Build Kafka producer relationships
            Set<KafkaTopicNode> producerTopics = buildKafkaTopicNodesFromCalls(
                    method.getKafkaCalls(), projectId, "PRODUCER");
            methodNode.getProducesToTopics().addAll(producerTopics);

            nodes.add(methodNode);
        }
        return nodes;
    }

    // ========================= External Call Building =========================

    private Set<ExternalCallNode> buildExternalCallNodes(List<ExternalCallInfo> externalCalls,
                                                          String projectId, String appKey) {
        Set<ExternalCallNode> nodes = new HashSet<>();
        if (externalCalls == null) return nodes;

        for (ExternalCallInfo extCall : externalCalls) {
            ExternalCallNode extCallNode = ExternalCallNode.builder()
                    .clientType(extCall.getClientType())
                    .httpMethod(extCall.getHttpMethod())
                    .url(extCall.getUrl())
                    .targetClass(extCall.getTargetClass())
                    .targetMethod(extCall.getTargetMethod())
                    .projectId(projectId)
                    .appKey(appKey)
                    .targetService(extCall.getTargetService())
                    .targetEndpoint(extCall.getTargetEndpoint())
                    .targetControllerClass(extCall.getTargetControllerClass())
                    .targetHandlerMethod(extCall.getTargetHandlerMethod())
                    .resolved(extCall.isResolved())
                    .resolutionReason(extCall.getResolutionReason())
                    .build();

            if (extCall.getLine() != null) {
                extCallNode.setLineStart(extCall.getLine().getStart());
                extCallNode.setLineEnd(extCall.getLine().getEnd());
            }

            nodes.add(extCallNode);
        }
        return nodes;
    }

    // ========================= Kafka Topic Building =========================

    private Set<KafkaTopicNode> buildKafkaTopicNodesFromCalls(List<KafkaCallInfo> kafkaCalls,
                                                               String projectId, String direction) {
        Set<KafkaTopicNode> nodes = new HashSet<>();
        if (kafkaCalls == null) return nodes;

        for (KafkaCallInfo kafkaCall : kafkaCalls) {
            if (!direction.equals(kafkaCall.getDirection())) continue;

            String topicName = kafkaCall.getResolvedTopic() != null && !kafkaCall.getResolvedTopic().isEmpty()
                    ? kafkaCall.getResolvedTopic()
                    : kafkaCall.getRawTopic();
            if (topicName == null || topicName.isEmpty()) continue;

            KafkaTopicNode topicNode = getOrCreateKafkaTopic(topicName, projectId, kafkaCall.getClientType());

            // Add producer/consumer metadata to the topic node
            String detail = String.format("%s.%s (line %d)",
                    kafkaCall.getClassName(),
                    kafkaCall.getMethodName(),
                    kafkaCall.getLine() != null ? kafkaCall.getLine().getStart() : 0);

            if ("PRODUCER".equals(direction)) {
                if (!topicNode.getProducerDetails().contains(detail)) {
                    topicNode.getProducerDetails().add(detail);
                }
                String serviceName = kafkaCall.getClassName();
                if (serviceName != null && !topicNode.getProducerServiceNames().contains(serviceName)) {
                    topicNode.getProducerServiceNames().add(serviceName);
                }
            } else if ("CONSUMER".equals(direction)) {
                if (!topicNode.getConsumerDetails().contains(detail)) {
                    topicNode.getConsumerDetails().add(detail);
                }
                String serviceName = kafkaCall.getClassName();
                if (serviceName != null && !topicNode.getConsumerServiceNames().contains(serviceName)) {
                    topicNode.getConsumerServiceNames().add(serviceName);
                }
            }

            nodes.add(topicNode);
        }
        return nodes;
    }

    private KafkaTopicNode getOrCreateKafkaTopic(String topicName, String projectId, String appKey) {
        return kafkaTopicNodeRepository.findByProjectIdAndName(projectId, topicName)
                .orElseGet(() -> KafkaTopicNode.builder()
                        .name(topicName)
                        .projectId(projectId)
                        .appKey(appKey)
                        .build());
    }

    // ========================= Utility Methods =========================

    private String buildAppKey(String repoUrl, ApplicationInfo appInfo) {
        if (appInfo != null && appInfo.isSpringBootApplication()
                && appInfo.getMainClassName() != null && appInfo.getMainClassPackage() != null) {
            return appInfo.getMainClassPackage() + "." + appInfo.getMainClassName();
        }
        return repoUrl + "::NON_SPRING";
    }

    private String extractBaseUrl(String path) {
        if (path == null || path.isEmpty()) return null;

        // Remove leading slash if present
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;

        // Take first segment as base URL
        int firstSlash = cleanPath.indexOf('/');
        if (firstSlash > 0) {
            return "/" + cleanPath.substring(0, firstSlash);
        }
        return "/" + cleanPath;
    }

    private MethodNode resolveServiceMethod(MethodCall call, Map<String, MethodNode> serviceMethodIndex) {
        if (call == null || serviceMethodIndex == null) {
            return null;
        }

        if (call.getSignature() != null) {
            MethodNode bySignature = serviceMethodIndex.get(call.getSignature());
            if (bySignature != null) {
                return bySignature;
            }
        }

        String key = buildMethodKey(call.getClassName(), call.getHandlerMethod());
        if (key != null) {
            MethodNode byKey = serviceMethodIndex.get(key);
            if (byKey != null) {
                return byKey;
            }
        }

        // Fallback: if className is absent but method name is unique across services, use that
        if (call.getHandlerMethod() != null) {
            List<MethodNode> byName = serviceMethodIndex.values().stream()
                    .filter(node -> Objects.equals(node.getMethodName(), call.getHandlerMethod()))
                    .distinct()
                    .toList();
            if (byName.size() == 1) {
                return byName.get(0);
            }
        }

        return serviceMethodIndex.values().stream()
                .filter(node -> Objects.equals(node.getClassName(), call.getClassName())
                        && Objects.equals(node.getMethodName(), call.getHandlerMethod()))
                .findFirst()
                .orElse(null);
    }

    private MethodNode resolveServiceMethodFromInfo(MethodInfo methodInfo, Map<String, MethodNode> serviceMethodIndex, String primaryClassName, String secondaryClassName) {
        if (methodInfo == null || serviceMethodIndex == null) {
            return null;
        }

        // First, try to resolve by signature
        if (methodInfo.getSignature() != null) {
            MethodNode bySignature = serviceMethodIndex.get(methodInfo.getSignature());
            if (bySignature != null) {
                return bySignature;
            }
        }

        // Then, try to resolve by className and methodName using both class qualifiers
        String keyPrimary = buildMethodKey(primaryClassName, methodInfo.getMethodName());
        if (keyPrimary != null) {
            MethodNode byKey = serviceMethodIndex.get(keyPrimary);
            if (byKey != null) {
                return byKey;
            }
        }

        String keySecondary = buildMethodKey(secondaryClassName, methodInfo.getMethodName());
        if (keySecondary != null) {
            MethodNode byKey = serviceMethodIndex.get(keySecondary);
            if (byKey != null) {
                return byKey;
            }
        }

        // Fallback: unique method-name match
        return serviceMethodIndex.values().stream()
                .filter(node -> Objects.equals(node.getMethodName(), methodInfo.getMethodName()))
                .reduce((a, b) -> null)
                .orElse(null);
    }

    private MethodNode resolveRepositoryMethod(MethodCall call, Map<String, MethodNode> repositoryMethodIndex) {
        if (call == null || repositoryMethodIndex == null) {
            return null;
        }

        // Try to resolve repository method by signature
        if (call.getSignature() != null) {
            MethodNode bySignature = repositoryMethodIndex.get(call.getSignature());
            if (bySignature != null) {
                return bySignature;
            }
        }

        // Try to resolve repository method by className and methodName
        String key = buildMethodKey(call.getClassName(), call.getHandlerMethod());
        if (key != null) {
            MethodNode byKey = repositoryMethodIndex.get(key);
            if (byKey != null) {
                return byKey;
            }
        }

        return null;
    }

    private String buildMethodKey(String className, String methodName) {
        if (className == null || methodName == null) {
            return null;
        }
        return className + "#" + methodName;
    }
}
