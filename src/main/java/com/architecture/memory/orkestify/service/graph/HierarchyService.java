package com.architecture.memory.orkestify.service.graph;

import com.architecture.memory.orkestify.dto.graph.*;
import com.architecture.memory.orkestify.model.graph.nodes.*;
import com.architecture.memory.orkestify.repository.graph.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for hierarchical drill-down API.
 * Provides level-by-level exploration of the codebase graph.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HierarchyService {

    private final ApplicationNodeRepository applicationNodeRepository;
    private final ControllerNodeRepository controllerNodeRepository;
    private final EndpointNodeRepository endpointNodeRepository;
    private final ServiceNodeRepository serviceNodeRepository;
    private final RepositoryClassNodeRepository repositoryClassNodeRepository;
    private final KafkaTopicNodeRepository kafkaTopicNodeRepository;
    private final KafkaListenerNodeRepository kafkaListenerNodeRepository;

    /**
     * Level 1: Get list of all applications with summary counts.
     */
    public ApplicationListResponse getApplications(String projectId) {
        log.info("Getting applications list for project: {}", projectId);
        try {
            List<ApplicationNode> apps = applicationNodeRepository.findByProjectId(projectId);
            List<ControllerNode> allControllers = controllerNodeRepository.findByProjectId(projectId);
            List<EndpointNode> allEndpoints = endpointNodeRepository.findByProjectId(projectId);
            List<ServiceNode> allServices = serviceNodeRepository.findByProjectId(projectId);
            List<RepositoryClassNode> allRepositories = repositoryClassNodeRepository.findByProjectIdWithDatabaseTables(projectId);
            List<KafkaListenerNode> allListeners = kafkaListenerNodeRepository.findByProjectIdWithListenerMethods(projectId);

            List<ApplicationListResponse.ApplicationItem> applicationItems = apps.stream()
                    .map(app -> {
                        String appKey = app.getAppKey();

                        int controllersCount = (int) allControllers.stream()
                                .filter(c -> appKey.equals(c.getAppKey())).count();
                        int servicesCount = (int) allServices.stream()
                                .filter(s -> appKey.equals(s.getAppKey())).count();
                        int repositoriesCount = (int) allRepositories.stream()
                                .filter(r -> appKey.equals(r.getAppKey())).count();
                        int endpointsCount = (int) allEndpoints.stream()
                                .filter(e -> appKey.equals(e.getAppKey())).count();

                        // Count kafka producers (endpoints with producesToTopics)
                        int kafkaProducersCount = (int) allEndpoints.stream()
                                .filter(e -> appKey.equals(e.getAppKey()))
                                .filter(e -> e.getProducesToTopics() != null && !e.getProducesToTopics().isEmpty())
                                .count();

                        // Count kafka consumers (listener methods)
                        int kafkaConsumersCount = (int) allListeners.stream()
                                .filter(l -> appKey.equals(l.getAppKey()))
                                .mapToLong(l -> l.getListenerMethods() != null ? l.getListenerMethods().size() : 0)
                                .sum();

                        // Count database tables
                        int databaseTablesCount = (int) allRepositories.stream()
                                .filter(r -> appKey.equals(r.getAppKey()))
                                .filter(r -> r.getAccessesTable() != null)
                                .count();

                        return ApplicationListResponse.ApplicationItem.builder()
                                .id(app.getId())
                                .name(app.getMainClassName())
                                .packageName(app.getMainClassPackage())
                                .repoUrl(app.getRepoUrl())
                                .isSpringBoot(app.isSpringBoot())
                                .summary(ApplicationListResponse.ApplicationSummary.builder()
                                        .controllersCount(controllersCount)
                                        .servicesCount(servicesCount)
                                        .repositoriesCount(repositoriesCount)
                                        .kafkaProducersCount(kafkaProducersCount)
                                        .kafkaConsumersCount(kafkaConsumersCount)
                                        .databaseTablesCount(databaseTablesCount)
                                        .endpointsCount(endpointsCount)
                                        .build())
                                .build();
                    })
                    .collect(Collectors.toList());

            return ApplicationListResponse.builder()
                    .projectId(projectId)
                    .applications(applicationItems)
                    .build();
        } catch (DataAccessException dae) {
            log.error("DB error getting applications for project {}", projectId, dae);
            throw dae;
        } catch (Exception ex) {
            log.error("Unexpected error getting applications for project {}", projectId, ex);
            throw ex;
        }
    }

    /**
     * Level 2: Get application details with all components.
     */
    public ApplicationDetailResponse getApplicationDetail(String projectId, String appId) {
        log.info("Getting application detail for project: {}, appId: {}", projectId, appId);
        try {
            ApplicationNode app = applicationNodeRepository.findById(appId)
                    .orElseThrow(() -> new IllegalArgumentException("Application not found: " + appId));

            String appKey = app.getAppKey();

            // Get controllers with endpoints
            List<ControllerNode> controllers = controllerNodeRepository.findByProjectIdWithEndpoints(projectId).stream()
                    .filter(c -> appKey.equals(c.getAppKey()))
                    .collect(Collectors.toList());

            List<ApplicationDetailResponse.ControllerItem> controllerItems = controllers.stream()
                    .map(controller -> {
                        List<ApplicationDetailResponse.EndpointSummary> endpointSummaries = new ArrayList<>();
                        if (controller.getEndpoints() != null) {
                            endpointSummaries = controller.getEndpoints().stream()
                                    .map(e -> ApplicationDetailResponse.EndpointSummary.builder()
                                            .id(e.getId())
                                            .method(e.getHttpMethod())
                                            .path(e.getFullPath())
                                            .handlerMethod(e.getHandlerMethod())
                                            .build())
                                    .collect(Collectors.toList());
                        }

                        return ApplicationDetailResponse.ControllerItem.builder()
                                .id(controller.getId())
                                .className(controller.getClassName())
                                .packageName(controller.getPackageName())
                                .baseUrl(controller.getBaseUrl())
                                .endpointsCount(endpointSummaries.size())
                                .endpoints(endpointSummaries)
                                .build();
                    })
                    .collect(Collectors.toList());

            // Get services
            List<ServiceNode> services = serviceNodeRepository.findByProjectIdWithMethods(projectId).stream()
                    .filter(s -> appKey.equals(s.getAppKey()))
                    .collect(Collectors.toList());

            List<ApplicationDetailResponse.ServiceItem> serviceItems = services.stream()
                    .map(service -> ApplicationDetailResponse.ServiceItem.builder()
                            .id(service.getId())
                            .className(service.getClassName())
                            .packageName(service.getPackageName())
                            .methodsCount(service.getMethods() != null ? service.getMethods().size() : 0)
                            .build())
                    .collect(Collectors.toList());

            // Get Kafka info
            List<KafkaListenerNode> listeners = kafkaListenerNodeRepository.findByProjectIdWithListenerMethods(projectId).stream()
                    .filter(l -> appKey.equals(l.getAppKey()))
                    .collect(Collectors.toList());

            List<EndpointNode> endpointsWithKafka = endpointNodeRepository.findByProjectIdWithKafkaProducers(projectId).stream()
                    .filter(e -> appKey.equals(e.getAppKey()))
                    .collect(Collectors.toList());

            List<ApplicationDetailResponse.ProducerItem> producers = new ArrayList<>();
            for (EndpointNode endpoint : endpointsWithKafka) {
                if (endpoint.getProducesToTopics() != null) {
                    for (KafkaTopicNode topic : endpoint.getProducesToTopics()) {
                        producers.add(ApplicationDetailResponse.ProducerItem.builder()
                                .id(endpoint.getId())
                                .topic(topic.getName())
                                .producerClass(endpoint.getControllerClass())
                                .producerMethod(endpoint.getHandlerMethod())
                                .lineNumber(endpoint.getLineStart())
                                .build());
                    }
                }
            }

            List<ApplicationDetailResponse.ConsumerItem> consumers = new ArrayList<>();
            for (KafkaListenerNode listener : listeners) {
                if (listener.getListenerMethods() != null) {
                    for (KafkaListenerMethodNode method : listener.getListenerMethods()) {
                        consumers.add(ApplicationDetailResponse.ConsumerItem.builder()
                                .id(method.getId())
                                .topic(method.getTopic())
                                .listenerClass(listener.getClassName())
                                .listenerMethod(method.getMethodName())
                                .groupId(method.getGroupId())
                                .build());
                    }
                }
            }

            // Get database access
            List<RepositoryClassNode> repositories = repositoryClassNodeRepository.findByProjectIdWithDatabaseTables(projectId).stream()
                    .filter(r -> appKey.equals(r.getAppKey()))
                    .collect(Collectors.toList());

            List<ApplicationDetailResponse.DatabaseItem> databaseItems = repositories.stream()
                    .filter(r -> r.getAccessesTable() != null)
                    .map(repo -> ApplicationDetailResponse.DatabaseItem.builder()
                            .id(repo.getAccessesTable().getId())
                            .tableName(repo.getAccessesTable().getTableName())
                            .repositoryClass(repo.getClassName())
                            .databaseType(repo.getRepositoryType())
                            .operations(repo.getAccessesTable().getOperations() != null ?
                                    new ArrayList<>(repo.getAccessesTable().getOperations()) : Collections.emptyList())
                            .build())
                    .collect(Collectors.toList());

            return ApplicationDetailResponse.builder()
                    .application(ApplicationDetailResponse.ApplicationInfo.builder()
                            .id(app.getId())
                            .name(app.getMainClassName())
                            .packageName(app.getMainClassPackage())
                            .repoUrl(app.getRepoUrl())
                            .isSpringBoot(app.isSpringBoot())
                            .lineRange(ApplicationDetailResponse.LineRange.builder()
                                    .start(app.getLineStart())
                                    .end(app.getLineEnd())
                                    .build())
                            .build())
                    .controllers(controllerItems)
                    .services(serviceItems)
                    .kafka(ApplicationDetailResponse.KafkaInfo.builder()
                            .producers(producers)
                            .consumers(consumers)
                            .build())
                    .databases(databaseItems)
                    .build();
        } catch (IllegalArgumentException iae) {
            log.warn("Bad request for application detail projectId={} appId={}", projectId, appId, iae);
            throw iae;
        } catch (DataAccessException dae) {
            log.error("DB error getting application detail projectId={} appId={}", projectId, appId, dae);
            throw dae;
        } catch (Exception ex) {
            log.error("Unexpected error getting application detail projectId={} appId={}", projectId, appId, ex);
            throw ex;
        }
    }

    /**
     * Level 3: Get controller details with endpoints and internal flow.
     */
    public ControllerDetailResponse getControllerDetail(String projectId, String controllerId) {
        log.info("Getting controller detail for project: {}, controllerId: {}", projectId, controllerId);
        try {
            ControllerNode controller = controllerNodeRepository.findByIdWithFullDetails(controllerId)
                    .orElseThrow(() -> new IllegalArgumentException("Controller not found: " + controllerId));

            List<ControllerDetailResponse.EndpointDetail> endpointDetails = new ArrayList<>();

            if (controller.getEndpoints() != null) {
                for (EndpointNode endpoint : controller.getEndpoints()) {
                    // Get internal flow
                    List<ControllerDetailResponse.ServiceCall> serviceCalls = new ArrayList<>();
                    List<ControllerDetailResponse.KafkaProduceInfo> kafkaProduces = new ArrayList<>();
                    List<ControllerDetailResponse.ExternalCallInfo> externalCalls = new ArrayList<>();

                    if (endpoint.getCalls() != null) {
                        for (MethodNode method : endpoint.getCalls()) {
                            serviceCalls.add(ControllerDetailResponse.ServiceCall.builder()
                                    .targetService(method.getClassName())
                                    .targetMethod(method.getMethodName())
                                    .lineNumber(method.getLineStart())
                                    .build());
                        }
                    }

                    if (endpoint.getProducesToTopics() != null) {
                        for (KafkaTopicNode topic : endpoint.getProducesToTopics()) {
                            kafkaProduces.add(ControllerDetailResponse.KafkaProduceInfo.builder()
                                    .topic(topic.getName())
                                    .lineNumber(endpoint.getLineStart())
                                    .build());
                        }
                    }

                    if (endpoint.getExternalCalls() != null) {
                        for (ExternalCallNode extCall : endpoint.getExternalCalls()) {
                            externalCalls.add(ControllerDetailResponse.ExternalCallInfo.builder()
                                    .url(extCall.getUrl())
                                    .httpMethod(extCall.getHttpMethod())
                                    .resolved(extCall.isResolved())
                                    .targetService(extCall.getTargetService())
                                    .lineNumber(extCall.getLineStart() + "-" + extCall.getLineEnd())
                                    .build());
                        }
                    }

                    endpointDetails.add(ControllerDetailResponse.EndpointDetail.builder()
                            .id(endpoint.getId())
                            .httpMethod(endpoint.getHttpMethod())
                            .path(endpoint.getFullPath())
                            .handlerMethod(endpoint.getHandlerMethod())
                            .signature(endpoint.getSignature())
                            .lineRange(ControllerDetailResponse.LineRange.builder()
                                    .start(endpoint.getLineStart())
                                    .end(endpoint.getLineEnd())
                                    .build())
                            .requestBody(endpoint.getRequestBodyType())
                            .responseType(endpoint.getResponseType())
                            .internalFlow(ControllerDetailResponse.InternalFlow.builder()
                                    .serviceCalls(serviceCalls)
                                    .kafkaProduces(kafkaProduces)
                                    .externalCalls(externalCalls)
                                    .build())
                            .build());
                }
            }

            return ControllerDetailResponse.builder()
                    .controller(ControllerDetailResponse.ControllerInfo.builder()
                            .id(controller.getId())
                            .className(controller.getClassName())
                            .packageName(controller.getPackageName())
                            .baseUrl(controller.getBaseUrl())
                            .lineRange(ControllerDetailResponse.LineRange.builder()
                                    .start(controller.getLineStart())
                                    .end(controller.getLineEnd())
                                    .build())
                            .build())
                    .endpoints(endpointDetails)
                    .build();
        } catch (IllegalArgumentException iae) {
            log.warn("Bad request for controller detail projectId={} controllerId={}", projectId, controllerId, iae);
            throw iae;
        } catch (DataAccessException dae) {
            log.error("DB error getting controller detail projectId={} controllerId={}", projectId, controllerId, dae);
            throw dae;
        } catch (Exception ex) {
            log.error("Unexpected error getting controller detail projectId={} controllerId={}", projectId, controllerId, ex);
            throw ex;
        }
    }

    /**
     * Level 3: Get service details with callers and methods.
     */
    public ServiceDetailResponse getServiceDetail(String projectId, String serviceId) {
        log.info("Getting service detail for project: {}, serviceId: {}", projectId, serviceId);
        try {
            ServiceNode service = serviceNodeRepository.findByIdWithFullDetails(serviceId)
                    .orElseThrow(() -> new IllegalArgumentException("Service not found: " + serviceId));

            // Get callers
            List<Object[]> callerData = serviceNodeRepository.findCallersOfService(serviceId);
            List<ServiceDetailResponse.CallerInfo> callers = callerData.stream()
                    .map(row -> ServiceDetailResponse.CallerInfo.builder()
                            .callerType((String) row[0])
                            .callerClass((String) row[1])
                            .callerMethod((String) row[2])
                            .lineNumber(row[3] != null ? ((Number) row[3]).intValue() : null)
                            .build())
                    .collect(Collectors.toList());

            // Get method details
            List<ServiceDetailResponse.MethodDetail> methodDetails = new ArrayList<>();
            if (service.getMethods() != null) {
                for (MethodNode method : service.getMethods()) {
                    List<ServiceDetailResponse.MethodCall> calls = new ArrayList<>();
                    List<ServiceDetailResponse.KafkaProduceInfo> kafkaProduces = new ArrayList<>();
                    List<ServiceDetailResponse.ExternalCallInfo> externalCalls = new ArrayList<>();

                    if (method.getCalls() != null) {
                        for (MethodNode calledMethod : method.getCalls()) {
                            calls.add(ServiceDetailResponse.MethodCall.builder()
                                    .targetClass(calledMethod.getClassName())
                                    .targetMethod(calledMethod.getMethodName())
                                    .lineNumber(calledMethod.getLineStart())
                                    .build());
                        }
                    }

                    if (method.getProducesToTopics() != null) {
                        for (KafkaTopicNode topic : method.getProducesToTopics()) {
                            kafkaProduces.add(ServiceDetailResponse.KafkaProduceInfo.builder()
                                    .topic(topic.getName())
                                    .lineNumber(method.getLineStart())
                                    .build());
                        }
                    }

                    if (method.getExternalCalls() != null) {
                        for (ExternalCallNode extCall : method.getExternalCalls()) {
                            externalCalls.add(ServiceDetailResponse.ExternalCallInfo.builder()
                                    .url(extCall.getUrl())
                                    .httpMethod(extCall.getHttpMethod())
                                    .resolved(extCall.isResolved())
                                    .lineNumber(extCall.getLineStart() + "-" + extCall.getLineEnd())
                                    .build());
                        }
                    }

                    methodDetails.add(ServiceDetailResponse.MethodDetail.builder()
                            .id(method.getId())
                            .methodName(method.getMethodName())
                            .signature(method.getSignature())
                            .lineRange(ServiceDetailResponse.LineRange.builder()
                                    .start(method.getLineStart())
                                    .end(method.getLineEnd())
                                    .build())
                            .calls(calls)
                            .kafkaProduces(kafkaProduces)
                            .externalCalls(externalCalls)
                            .build());
                }
            }

            // Get repositories used
            List<RepositoryClassNode> allRepos = repositoryClassNodeRepository.findByProjectIdWithDatabaseTables(service.getProjectId());
            List<ServiceDetailResponse.RepositoryUsage> repositoriesUsed = new ArrayList<>();

            // Find repositories that this service uses by checking method calls
            Set<String> repoClassNames = allRepos.stream()
                    .map(RepositoryClassNode::getClassName)
                    .collect(Collectors.toSet());

            if (service.getMethods() != null) {
                for (MethodNode method : service.getMethods()) {
                    if (method.getCalls() != null) {
                        for (MethodNode calledMethod : method.getCalls()) {
                            if (repoClassNames.contains(calledMethod.getClassName())) {
                                RepositoryClassNode repo = allRepos.stream()
                                        .filter(r -> calledMethod.getClassName().equals(r.getClassName()))
                                        .findFirst()
                                        .orElse(null);
                                if (repo != null) {
                                    repositoriesUsed.add(ServiceDetailResponse.RepositoryUsage.builder()
                                            .className(repo.getClassName())
                                            .tableName(repo.getAccessesTable() != null ?
                                                    repo.getAccessesTable().getTableName() : null)
                                            .build());
                                }
                            }
                        }
                    }
                }
            }

            // Remove duplicates
            repositoriesUsed = repositoriesUsed.stream()
                    .distinct()
                    .collect(Collectors.toList());

            return ServiceDetailResponse.builder()
                    .service(ServiceDetailResponse.ServiceInfo.builder()
                            .id(service.getId())
                            .className(service.getClassName())
                            .packageName(service.getPackageName())
                            .lineRange(ServiceDetailResponse.LineRange.builder()
                                    .start(service.getLineStart())
                                    .end(service.getLineEnd())
                                    .build())
                            .build())
                    .calledBy(callers)
                    .methods(methodDetails)
                    .repositoriesUsed(repositoriesUsed)
                    .build();
        } catch (IllegalArgumentException iae) {
            log.warn("Bad request for service detail projectId={} serviceId={}", projectId, serviceId, iae);
            throw iae;
        } catch (DataAccessException dae) {
            log.error("DB error getting service detail projectId={} serviceId={}", projectId, serviceId, dae);
            throw dae;
        } catch (Exception ex) {
            log.error("Unexpected error getting service detail projectId={} serviceId={}", projectId, serviceId, ex);
            throw ex;
        }
    }

    /**
     * Level 3: Get endpoint internal flow with call tree.
     */
    public EndpointFlowResponse getEndpointFlow(String projectId, String endpointId) {
        log.info("Getting endpoint flow for project: {}, endpointId: {}", projectId, endpointId);
        try {
            EndpointNode endpoint = endpointNodeRepository.findByIdWithFullCallChain(endpointId)
                    .orElseThrow(() -> new IllegalArgumentException("Endpoint not found: " + endpointId));

            // Build call tree
            List<EndpointFlowResponse.CallTreeNode> callTree = new ArrayList<>();
            EndpointFlowResponse.CallTreeNode rootNode = EndpointFlowResponse.CallTreeNode.builder()
                    .depth(0)
                    .type("Endpoint")
                    .className(endpoint.getControllerClass())
                    .methodName(endpoint.getHandlerMethod())
                    .name(endpoint.getHttpMethod() + " " + endpoint.getFullPath())
                    .lineNumber(endpoint.getLineStart())
                    .children(buildCallTreeChildren(endpoint.getCalls(), 1))
                    .build();
            callTree.add(rootNode);

            // Build Kafka interactions
            List<EndpointFlowResponse.KafkaProduceInfo> kafkaProduces = new ArrayList<>();
            List<EndpointFlowResponse.KafkaConsumeInfo> kafkaConsumes = new ArrayList<>();

            if (endpoint.getProducesToTopics() != null) {
                for (KafkaTopicNode topic : endpoint.getProducesToTopics()) {
                    kafkaProduces.add(EndpointFlowResponse.KafkaProduceInfo.builder()
                            .topic(topic.getName())
                            .lineNumber(endpoint.getLineStart())
                            .producerMethod(endpoint.getHandlerMethod())
                            .build());
                }
            }

            // Build external calls
            List<EndpointFlowResponse.ExternalCallInfo> externalCalls = new ArrayList<>();
            if (endpoint.getExternalCalls() != null) {
                for (ExternalCallNode extCall : endpoint.getExternalCalls()) {
                    externalCalls.add(EndpointFlowResponse.ExternalCallInfo.builder()
                            .url(extCall.getUrl())
                            .httpMethod(extCall.getHttpMethod())
                            .clientType(extCall.getClientType())
                            .resolved(extCall.isResolved())
                            .targetService(extCall.getTargetService())
                            .targetEndpoint(extCall.getTargetEndpoint())
                            .lineNumber(extCall.getLineStart() + "-" + extCall.getLineEnd())
                            .build());
                }
            }

            // Build database access
            List<EndpointFlowResponse.DatabaseAccess> databaseAccesses = new ArrayList<>();
            collectDatabaseAccesses(endpoint.getCalls(), databaseAccesses, projectId);

            return EndpointFlowResponse.builder()
                    .endpoint(EndpointFlowResponse.EndpointInfo.builder()
                            .id(endpoint.getId())
                            .httpMethod(endpoint.getHttpMethod())
                            .path(endpoint.getFullPath())
                            .controllerClass(endpoint.getControllerClass())
                            .handlerMethod(endpoint.getHandlerMethod())
                            .signature(endpoint.getSignature())
                            .lineRange(EndpointFlowResponse.LineRange.builder()
                                    .start(endpoint.getLineStart())
                                    .end(endpoint.getLineEnd())
                                    .build())
                            .requestBody(endpoint.getRequestBodyType())
                            .responseType(endpoint.getResponseType())
                            .build())
                    .callTree(callTree)
                    .kafkaInteractions(EndpointFlowResponse.KafkaInteractions.builder()
                            .produces(kafkaProduces)
                            .consumes(kafkaConsumes)
                            .build())
                    .externalCalls(externalCalls)
                    .databaseAccess(databaseAccesses)
                    .build();
        } catch (IllegalArgumentException iae) {
            log.warn("Bad request for endpoint flow projectId={} endpointId={}", projectId, endpointId, iae);
            throw iae;
        } catch (DataAccessException dae) {
            log.error("DB error getting endpoint flow projectId={} endpointId={}", projectId, endpointId, dae);
            throw dae;
        } catch (Exception ex) {
            log.error("Unexpected error getting endpoint flow projectId={} endpointId={}", projectId, endpointId, ex);
            throw ex;
        }
    }

    private List<EndpointFlowResponse.CallTreeNode> buildCallTreeChildren(Set<MethodNode> methods, int depth) {
        if (methods == null || methods.isEmpty() || depth > 5) {
            return Collections.emptyList();
        }

        return methods.stream()
                .map(method -> {
                    String type = determineMethodType(method);
                    return EndpointFlowResponse.CallTreeNode.builder()
                            .depth(depth)
                            .type(type)
                            .className(method.getClassName())
                            .methodName(method.getMethodName())
                            .name(method.getClassName() + "." + method.getMethodName())
                            .lineNumber(method.getLineStart())
                            .children(buildCallTreeChildren(method.getCalls(), depth + 1))
                            .build();
                })
                .collect(Collectors.toList());
    }

    private String determineMethodType(MethodNode method) {
        if (method.getMethodType() != null) {
            switch (method.getMethodType()) {
                case "SERVICE_METHOD":
                    return "ServiceCall";
                case "REPOSITORY_METHOD":
                    return "RepositoryCall";
                case "LISTENER_METHOD":
                    return "KafkaListenerCall";
                default:
                    return "MethodCall";
            }
        }
        return "MethodCall";
    }

    private void collectDatabaseAccesses(Set<MethodNode> methods, List<EndpointFlowResponse.DatabaseAccess> accesses, String projectId) {
        if (methods == null || methods.isEmpty()) {
            return;
        }

        List<RepositoryClassNode> repos = repositoryClassNodeRepository.findByProjectIdWithDatabaseTables(projectId);
        Map<String, RepositoryClassNode> repoByClassName = repos.stream()
                .collect(Collectors.toMap(RepositoryClassNode::getClassName, r -> r, (a, b) -> a));

        for (MethodNode method : methods) {
            if ("REPOSITORY_METHOD".equals(method.getMethodType())) {
                RepositoryClassNode repo = repoByClassName.get(method.getClassName());
                if (repo != null && repo.getAccessesTable() != null) {
                    String operation = inferOperation(method.getMethodName());
                    EndpointFlowResponse.DatabaseAccess access = EndpointFlowResponse.DatabaseAccess.builder()
                            .table(repo.getAccessesTable().getTableName())
                            .operation(operation)
                            .repository(repo.getClassName())
                            .build();
                    if (!accesses.contains(access)) {
                        accesses.add(access);
                    }
                }
            }

            // Recurse
            collectDatabaseAccesses(method.getCalls(), accesses, projectId);
        }
    }

    private String inferOperation(String methodName) {
        if (methodName == null) return "UNKNOWN";
        String lower = methodName.toLowerCase();
        if (lower.startsWith("find") || lower.startsWith("get") || lower.startsWith("read") ||
                lower.startsWith("select") || lower.startsWith("query") || lower.startsWith("count")) {
            return "READ";
        } else if (lower.startsWith("save") || lower.startsWith("insert") || lower.startsWith("create") ||
                lower.startsWith("add") || lower.startsWith("persist")) {
            return "WRITE";
        } else if (lower.startsWith("update") || lower.startsWith("modify") || lower.startsWith("set")) {
            return "UPDATE";
        } else if (lower.startsWith("delete") || lower.startsWith("remove")) {
            return "DELETE";
        }
        return "UNKNOWN";
    }
}
