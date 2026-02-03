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
        log.info("Persisting analysis to Neo4j graph for project: {}, repo: {}", projectId, repoUrl);

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

        // Build and add all component nodes
        Set<ControllerNode> controllerNodes = buildControllerNodes(analysis.getControllers(), projectId, appKey);
        applicationNode.getControllers().addAll(controllerNodes);

        Set<ServiceNode> serviceNodes = buildServiceNodes(analysis.getServices(), projectId, appKey);
        applicationNode.getServices().addAll(serviceNodes);

        Set<RepositoryClassNode> repositoryNodes = buildRepositoryNodes(analysis.getRepositories(), projectId, appKey);
        applicationNode.getRepositories().addAll(repositoryNodes);

        Set<KafkaListenerNode> kafkaListenerNodes = buildKafkaListenerNodes(analysis.getKafkaListeners(), projectId, appKey);
        applicationNode.getKafkaListeners().addAll(kafkaListenerNodes);

        Set<ConfigurationNode> configurationNodes = buildConfigurationNodes(analysis.getConfigurations(), projectId, appKey);
        applicationNode.getConfigurations().addAll(configurationNodes);

        // Save the entire graph (Neo4j will cascade save all related nodes)
        applicationNodeRepository.save(applicationNode);

        log.info("Successfully persisted analysis to Neo4j graph. Controllers: {}, Services: {}, Repositories: {}, KafkaListeners: {}, Configurations: {}",
                controllerNodes.size(), serviceNodes.size(), repositoryNodes.size(),
                kafkaListenerNodes.size(), configurationNodes.size());
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

    private Set<ControllerNode> buildControllerNodes(List<ControllerInfo> controllers, String projectId, String appKey) {
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
                    controller.getEndpoints(), projectId, appKey, controller.getClassName());
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
                                                   String appKey, String controllerClass) {
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

            // Build method calls
            Set<MethodNode> calledMethods = buildMethodNodesFromCalls(
                    endpoint.getCalls(), projectId, appKey, "ENDPOINT_CALL");
            endpointNode.getCalls().addAll(calledMethods);

            // Build external calls
            Set<ExternalCallNode> externalCallNodes = buildExternalCallNodes(
                    endpoint.getExternalCalls(), projectId, appKey);
            endpointNode.getExternalCalls().addAll(externalCallNodes);

            // Build Kafka producer relationships
            Set<KafkaTopicNode> producerTopics = buildKafkaTopicNodesFromCalls(
                    endpoint.getKafkaCalls(), projectId, "PRODUCER");
            endpointNode.getProducesToTopics().addAll(producerTopics);

            nodes.add(endpointNode);
        }
        return nodes;
    }

    // ========================= Service Building =========================

    private Set<ServiceNode> buildServiceNodes(List<ServiceInfo> services, String projectId, String appKey) {
        Set<ServiceNode> nodes = new HashSet<>();
        if (services == null) return nodes;

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

            // Build service methods
            Set<MethodNode> methodNodes = buildMethodNodesFromMethodInfo(
                    service.getMethods(), projectId, appKey, service.getClassName(), "SERVICE_METHOD");
            serviceNode.getMethods().addAll(methodNodes);

            nodes.add(serviceNode);
        }
        return nodes;
    }

    // ========================= Repository Building =========================

    private Set<RepositoryClassNode> buildRepositoryNodes(List<RepositoryInfo> repositories,
                                                           String projectId, String appKey) {
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
                repoNode.setAccessesTable(tableNode);
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
                KafkaTopicNode topicNode = getOrCreateKafkaTopic(method.getTopic(), projectId);
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
        Set<MethodNode> nodes = new HashSet<>();
        if (calls == null) return nodes;

        for (MethodCall call : calls) {
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

            // Recursively build nested calls
            Set<MethodNode> nestedCalls = buildMethodNodesFromCalls(
                    call.getCalls(), projectId, appKey, methodType);
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
            if (direction.equals(kafkaCall.getDirection()) && kafkaCall.getTopic() != null) {
                KafkaTopicNode topicNode = getOrCreateKafkaTopic(kafkaCall.getTopic(), projectId);
                nodes.add(topicNode);
            }
        }
        return nodes;
    }

    private KafkaTopicNode getOrCreateKafkaTopic(String topicName, String projectId) {
        // Try to find existing topic or create new one
        return kafkaTopicNodeRepository.findByName(topicName)
                .orElseGet(() -> KafkaTopicNode.builder()
                        .name(topicName)
                        .projectId(projectId)
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
}
