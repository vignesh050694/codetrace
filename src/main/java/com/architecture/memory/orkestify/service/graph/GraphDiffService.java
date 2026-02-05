package com.architecture.memory.orkestify.service.graph;

import com.architecture.memory.orkestify.dto.graph.*;
import com.architecture.memory.orkestify.model.graph.nodes.*;
import com.architecture.memory.orkestify.repository.graph.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes the diff between a production graph and a shadow (PR branch) graph.
 *
 * Compares nodes by semantic keys (className+packageName for services,
 * httpMethod+path for endpoints, etc.) and detects added, modified, and removed elements.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GraphDiffService {

    private final ApplicationNodeRepository applicationNodeRepository;
    private final ControllerNodeRepository controllerNodeRepository;
    private final ServiceNodeRepository serviceNodeRepository;
    private final EndpointNodeRepository endpointNodeRepository;
    private final RepositoryClassNodeRepository repositoryClassNodeRepository;
    private final KafkaListenerNodeRepository kafkaListenerNodeRepository;
    private final KafkaTopicNodeRepository kafkaTopicNodeRepository;

    /**
     * Compute the full diff between production and shadow graphs.
     */
    public ShadowGraphDiff computeDiff(String productionProjectId, String shadowProjectId) {
        log.info("Computing diff: production={} vs shadow={}", productionProjectId, shadowProjectId);

        List<NodeChange> addedNodes = new ArrayList<>();
        List<NodeChange> modifiedNodes = new ArrayList<>();
        List<NodeChange> removedNodes = new ArrayList<>();
        List<RelationshipChange> addedRelationships = new ArrayList<>();
        List<RelationshipChange> removedRelationships = new ArrayList<>();

        // Compare each node type
        diffControllers(productionProjectId, shadowProjectId, addedNodes, modifiedNodes, removedNodes);
        diffEndpoints(productionProjectId, shadowProjectId, addedNodes, modifiedNodes, removedNodes,
                addedRelationships, removedRelationships);
        diffServices(productionProjectId, shadowProjectId, addedNodes, modifiedNodes, removedNodes);
        diffRepositories(productionProjectId, shadowProjectId, addedNodes, modifiedNodes, removedNodes,
                addedRelationships, removedRelationships);
        diffKafkaListeners(productionProjectId, shadowProjectId, addedNodes, modifiedNodes, removedNodes);
        diffKafkaTopics(productionProjectId, shadowProjectId, addedNodes, modifiedNodes, removedNodes);

        // Diff method-level call relationships
        diffMethodCalls(productionProjectId, shadowProjectId, addedRelationships, removedRelationships);

        DiffSummary summary = buildSummary(addedNodes, modifiedNodes, removedNodes,
                addedRelationships, removedRelationships);

        log.info("Diff complete: {} added, {} modified, {} removed, {} rel added, {} rel removed",
                addedNodes.size(), modifiedNodes.size(), removedNodes.size(),
                addedRelationships.size(), removedRelationships.size());

        return ShadowGraphDiff.builder()
                .addedNodes(addedNodes)
                .modifiedNodes(modifiedNodes)
                .removedNodes(removedNodes)
                .addedRelationships(addedRelationships)
                .removedRelationships(removedRelationships)
                .summary(summary)
                .build();
    }

    // ========================= CONTROLLER DIFF =========================

    private void diffControllers(String prodId, String shadowId,
                                  List<NodeChange> added, List<NodeChange> modified, List<NodeChange> removed) {
        Map<String, ControllerNode> prodMap = buildControllerMap(prodId);
        Map<String, ControllerNode> shadowMap = buildControllerMap(shadowId);

        diffMaps(prodMap, shadowMap, "Controller", added, modified, removed,
                this::controllerToProperties, this::controllerDisplayName);
    }

    private Map<String, ControllerNode> buildControllerMap(String projectId) {
        return controllerNodeRepository.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(
                        c -> c.getPackageName() + "." + c.getClassName(),
                        c -> c,
                        (a, b) -> a));
    }

    private Map<String, Object> controllerToProperties(ControllerNode c) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("className", c.getClassName());
        props.put("packageName", c.getPackageName());
        props.put("baseUrl", c.getBaseUrl());
        props.put("endpointCount", c.getEndpoints() != null ? c.getEndpoints().size() : 0);
        return props;
    }

    private String controllerDisplayName(ControllerNode c) {
        return c.getClassName();
    }

    // ========================= ENDPOINT DIFF =========================

    private void diffEndpoints(String prodId, String shadowId,
                                List<NodeChange> added, List<NodeChange> modified, List<NodeChange> removed,
                                List<RelationshipChange> addedRels, List<RelationshipChange> removedRels) {
        Map<String, EndpointNode> prodMap = buildEndpointMap(prodId);
        Map<String, EndpointNode> shadowMap = buildEndpointMap(shadowId);

        diffMaps(prodMap, shadowMap, "Endpoint", added, modified, removed,
                this::endpointToProperties, this::endpointDisplayName);

        // Diff external calls and kafka producers per endpoint
        for (String key : shadowMap.keySet()) {
            if (prodMap.containsKey(key)) {
                diffEndpointRelationships(prodMap.get(key), shadowMap.get(key), key,
                        addedRels, removedRels);
            }
        }
    }

    private Map<String, EndpointNode> buildEndpointMap(String projectId) {
        return endpointNodeRepository.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(
                        e -> e.getControllerClass() + "::" + e.getHttpMethod() + "::" + e.getPath(),
                        e -> e,
                        (a, b) -> a));
    }

    private Map<String, Object> endpointToProperties(EndpointNode e) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("httpMethod", e.getHttpMethod());
        props.put("path", e.getPath());
        props.put("handlerMethod", e.getHandlerMethod());
        props.put("controllerClass", e.getControllerClass());
        props.put("requestBodyType", e.getRequestBodyType());
        props.put("responseType", e.getResponseType());
        return props;
    }

    private String endpointDisplayName(EndpointNode e) {
        return e.getHttpMethod() + " " + e.getPath();
    }

    private void diffEndpointRelationships(EndpointNode prod, EndpointNode shadow, String endpointKey,
                                            List<RelationshipChange> addedRels,
                                            List<RelationshipChange> removedRels) {
        // Diff external calls
        Set<String> prodExtCalls = extractExternalCallKeys(prod.getExternalCalls());
        Set<String> shadowExtCalls = extractExternalCallKeys(shadow.getExternalCalls());

        for (String key : shadowExtCalls) {
            if (!prodExtCalls.contains(key)) {
                addedRels.add(RelationshipChange.builder()
                        .changeType(RelationshipChange.ChangeType.ADDED)
                        .relationshipType("MAKES_EXTERNAL_CALL")
                        .sourceNodeKey(endpointKey)
                        .sourceNodeType("Endpoint")
                        .targetNodeKey(key)
                        .targetNodeType("ExternalCall")
                        .displayDescription(endpointDisplayName(shadow) + " -> " + key)
                        .build());
            }
        }
        for (String key : prodExtCalls) {
            if (!shadowExtCalls.contains(key)) {
                removedRels.add(RelationshipChange.builder()
                        .changeType(RelationshipChange.ChangeType.REMOVED)
                        .relationshipType("MAKES_EXTERNAL_CALL")
                        .sourceNodeKey(endpointKey)
                        .sourceNodeType("Endpoint")
                        .targetNodeKey(key)
                        .targetNodeType("ExternalCall")
                        .displayDescription(endpointDisplayName(prod) + " -> " + key)
                        .build());
            }
        }
    }

    private Set<String> extractExternalCallKeys(Set<ExternalCallNode> calls) {
        if (calls == null) return Collections.emptySet();
        return calls.stream()
                .map(ec -> ec.getClientType() + "::" + ec.getHttpMethod() + "::" + ec.getUrl())
                .collect(Collectors.toSet());
    }

    // ========================= SERVICE DIFF =========================

    private void diffServices(String prodId, String shadowId,
                               List<NodeChange> added, List<NodeChange> modified, List<NodeChange> removed) {
        Map<String, ServiceNode> prodMap = buildServiceMap(prodId);
        Map<String, ServiceNode> shadowMap = buildServiceMap(shadowId);

        diffMaps(prodMap, shadowMap, "Service", added, modified, removed,
                this::serviceToProperties, this::serviceDisplayName);
    }

    private Map<String, ServiceNode> buildServiceMap(String projectId) {
        return serviceNodeRepository.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(
                        s -> s.getPackageName() + "." + s.getClassName(),
                        s -> s,
                        (a, b) -> a));
    }

    private Map<String, Object> serviceToProperties(ServiceNode s) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("className", s.getClassName());
        props.put("packageName", s.getPackageName());
        props.put("methodCount", s.getMethods() != null ? s.getMethods().size() : 0);
        return props;
    }

    private String serviceDisplayName(ServiceNode s) {
        return s.getClassName();
    }

    // ========================= REPOSITORY DIFF =========================

    private void diffRepositories(String prodId, String shadowId,
                                   List<NodeChange> added, List<NodeChange> modified, List<NodeChange> removed,
                                   List<RelationshipChange> addedRels, List<RelationshipChange> removedRels) {
        Map<String, RepositoryClassNode> prodMap = buildRepositoryMap(prodId);
        Map<String, RepositoryClassNode> shadowMap = buildRepositoryMap(shadowId);

        diffMaps(prodMap, shadowMap, "Repository", added, modified, removed,
                this::repositoryToProperties, this::repositoryDisplayName);

        // Diff database table access
        for (String key : shadowMap.keySet()) {
            RepositoryClassNode shadowRepo = shadowMap.get(key);
            RepositoryClassNode prodRepo = prodMap.get(key);

            String shadowTable = shadowRepo.getAccessesTables() != null && !shadowRepo.getAccessesTables().isEmpty()
                    ? shadowRepo.getAccessesTables().iterator().next().getTableName() : null;

            if (prodRepo != null) {
                String prodTable = prodRepo.getAccessesTables() != null && !prodRepo.getAccessesTables().isEmpty()
                        ? prodRepo.getAccessesTables().iterator().next().getTableName() : null;

                if (shadowTable != null && !shadowTable.equals(prodTable)) {
                    addedRels.add(RelationshipChange.builder()
                            .changeType(RelationshipChange.ChangeType.ADDED)
                            .relationshipType("ACCESSES")
                            .sourceNodeKey(key)
                            .sourceNodeType("Repository")
                            .targetNodeKey(shadowTable)
                            .targetNodeType("DatabaseTable")
                            .displayDescription(shadowRepo.getClassName() + " -> " + shadowTable)
                            .build());
                }
            } else if (shadowTable != null) {
                addedRels.add(RelationshipChange.builder()
                        .changeType(RelationshipChange.ChangeType.ADDED)
                        .relationshipType("ACCESSES")
                        .sourceNodeKey(key)
                        .sourceNodeType("Repository")
                        .targetNodeKey(shadowTable)
                        .targetNodeType("DatabaseTable")
                        .displayDescription(shadowRepo.getClassName() + " -> " + shadowTable)
                        .build());
            }
        }
    }

    private Map<String, RepositoryClassNode> buildRepositoryMap(String projectId) {
        return repositoryClassNodeRepository.findByProjectIdWithDatabaseTables(projectId).stream()
                .collect(Collectors.toMap(
                        r -> r.getPackageName() + "." + r.getClassName(),
                        r -> r,
                        (a, b) -> a));
    }

    private Map<String, Object> repositoryToProperties(RepositoryClassNode r) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("className", r.getClassName());
        props.put("packageName", r.getPackageName());
        props.put("repositoryType", r.getRepositoryType());
        props.put("extendsClass", r.getExtendsClass());
        if (r.getAccessesTables() != null && !r.getAccessesTables().isEmpty()) {
            DatabaseTableNode table = r.getAccessesTables().iterator().next();
            props.put("tableName", table.getTableName());
            props.put("entityClass", table.getEntityClass());
        }
        return props;
    }

    private String repositoryDisplayName(RepositoryClassNode r) {
        return r.getClassName();
    }

    // ========================= KAFKA LISTENER DIFF =========================

    private void diffKafkaListeners(String prodId, String shadowId,
                                     List<NodeChange> added, List<NodeChange> modified, List<NodeChange> removed) {
        Map<String, KafkaListenerNode> prodMap = buildKafkaListenerMap(prodId);
        Map<String, KafkaListenerNode> shadowMap = buildKafkaListenerMap(shadowId);

        diffMaps(prodMap, shadowMap, "KafkaListener", added, modified, removed,
                this::kafkaListenerToProperties, this::kafkaListenerDisplayName);
    }

    private Map<String, KafkaListenerNode> buildKafkaListenerMap(String projectId) {
        return kafkaListenerNodeRepository.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(
                        k -> k.getPackageName() + "." + k.getClassName(),
                        k -> k,
                        (a, b) -> a));
    }

    private Map<String, Object> kafkaListenerToProperties(KafkaListenerNode k) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("className", k.getClassName());
        props.put("packageName", k.getPackageName());
        props.put("listenerMethodCount", k.getListenerMethods() != null ? k.getListenerMethods().size() : 0);
        return props;
    }

    private String kafkaListenerDisplayName(KafkaListenerNode k) {
        return k.getClassName();
    }

    // ========================= KAFKA TOPIC DIFF =========================

    private void diffKafkaTopics(String prodId, String shadowId,
                                  List<NodeChange> added, List<NodeChange> modified, List<NodeChange> removed) {
        Map<String, KafkaTopicNode> prodMap = buildKafkaTopicMap(prodId);
        Map<String, KafkaTopicNode> shadowMap = buildKafkaTopicMap(shadowId);

        diffMaps(prodMap, shadowMap, "KafkaTopic", added, modified, removed,
                this::kafkaTopicToProperties, this::kafkaTopicDisplayName);
    }

    private Map<String, KafkaTopicNode> buildKafkaTopicMap(String projectId) {
        return kafkaTopicNodeRepository.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(
                        KafkaTopicNode::getName,
                        t -> t,
                        (a, b) -> a));
    }

    private Map<String, Object> kafkaTopicToProperties(KafkaTopicNode t) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", t.getName());
        return props;
    }

    private String kafkaTopicDisplayName(KafkaTopicNode t) {
        return t.getName();
    }

    // ========================= METHOD CALL DIFF =========================

    private void diffMethodCalls(String prodId, String shadowId,
                                  List<RelationshipChange> addedRels, List<RelationshipChange> removedRels) {
        // Compare service methods with external calls
        List<ServiceNode> prodServices = serviceNodeRepository.findByProjectIdWithMethods(prodId);
        List<ServiceNode> shadowServices = serviceNodeRepository.findByProjectIdWithMethods(shadowId);

        Map<String, Set<String>> prodMethodCalls = extractServiceMethodCalls(prodServices);
        Map<String, Set<String>> shadowMethodCalls = extractServiceMethodCalls(shadowServices);

        // Find added calls
        for (Map.Entry<String, Set<String>> entry : shadowMethodCalls.entrySet()) {
            Set<String> prodCalls = prodMethodCalls.getOrDefault(entry.getKey(), Collections.emptySet());
            for (String call : entry.getValue()) {
                if (!prodCalls.contains(call)) {
                    addedRels.add(RelationshipChange.builder()
                            .changeType(RelationshipChange.ChangeType.ADDED)
                            .relationshipType("CALLS")
                            .sourceNodeKey(entry.getKey())
                            .sourceNodeType("Method")
                            .targetNodeKey(call)
                            .targetNodeType("Method")
                            .displayDescription(entry.getKey() + " -> " + call)
                            .build());
                }
            }
        }

        // Find removed calls
        for (Map.Entry<String, Set<String>> entry : prodMethodCalls.entrySet()) {
            Set<String> shadowCalls = shadowMethodCalls.getOrDefault(entry.getKey(), Collections.emptySet());
            for (String call : entry.getValue()) {
                if (!shadowCalls.contains(call)) {
                    removedRels.add(RelationshipChange.builder()
                            .changeType(RelationshipChange.ChangeType.REMOVED)
                            .relationshipType("CALLS")
                            .sourceNodeKey(entry.getKey())
                            .sourceNodeType("Method")
                            .targetNodeKey(call)
                            .targetNodeType("Method")
                            .displayDescription(entry.getKey() + " -> " + call)
                            .build());
                }
            }
        }
    }

    private Map<String, Set<String>> extractServiceMethodCalls(List<ServiceNode> services) {
        Map<String, Set<String>> methodCalls = new HashMap<>();
        for (ServiceNode service : services) {
            if (service.getMethods() == null) continue;
            for (MethodNode method : service.getMethods()) {
                String methodKey = service.getClassName() + "." + method.getMethodName();
                Set<String> calls = new HashSet<>();
                if (method.getCalls() != null) {
                    for (MethodNode called : method.getCalls()) {
                        calls.add(called.getClassName() + "." + called.getMethodName());
                    }
                }
                methodCalls.put(methodKey, calls);
            }
        }
        return methodCalls;
    }

    // ========================= GENERIC DIFF ENGINE =========================

    /**
     * Generic diff for any node type using semantic keys and property maps.
     */
    private <T> void diffMaps(Map<String, T> prodMap, Map<String, T> shadowMap,
                               String nodeType,
                               List<NodeChange> added, List<NodeChange> modified, List<NodeChange> removed,
                               java.util.function.Function<T, Map<String, Object>> toProperties,
                               java.util.function.Function<T, String> toDisplayName) {
        // Added: in shadow but not in production
        for (Map.Entry<String, T> entry : shadowMap.entrySet()) {
            if (!prodMap.containsKey(entry.getKey())) {
                added.add(NodeChange.builder()
                        .changeType(NodeChange.ChangeType.ADDED)
                        .nodeType(nodeType)
                        .nodeKey(entry.getKey())
                        .displayName(toDisplayName.apply(entry.getValue()))
                        .shadowProperties(toProperties.apply(entry.getValue()))
                        .build());
            }
        }

        // Removed: in production but not in shadow
        for (Map.Entry<String, T> entry : prodMap.entrySet()) {
            if (!shadowMap.containsKey(entry.getKey())) {
                removed.add(NodeChange.builder()
                        .changeType(NodeChange.ChangeType.REMOVED)
                        .nodeType(nodeType)
                        .nodeKey(entry.getKey())
                        .displayName(toDisplayName.apply(entry.getValue()))
                        .productionProperties(toProperties.apply(entry.getValue()))
                        .build());
            }
        }

        // Modified: in both, check for property differences
        for (Map.Entry<String, T> entry : shadowMap.entrySet()) {
            if (!prodMap.containsKey(entry.getKey())) continue;

            Map<String, Object> prodProps = toProperties.apply(prodMap.get(entry.getKey()));
            Map<String, Object> shadowProps = toProperties.apply(entry.getValue());

            List<NodeChange.PropertyDiff> diffs = compareProperties(prodProps, shadowProps);
            if (!diffs.isEmpty()) {
                modified.add(NodeChange.builder()
                        .changeType(NodeChange.ChangeType.MODIFIED)
                        .nodeType(nodeType)
                        .nodeKey(entry.getKey())
                        .displayName(toDisplayName.apply(entry.getValue()))
                        .propertyDiffs(diffs)
                        .productionProperties(prodProps)
                        .shadowProperties(shadowProps)
                        .build());
            }
        }
    }

    private List<NodeChange.PropertyDiff> compareProperties(Map<String, Object> prod, Map<String, Object> shadow) {
        List<NodeChange.PropertyDiff> diffs = new ArrayList<>();

        for (String key : shadow.keySet()) {
            Object prodVal = prod.get(key);
            Object shadowVal = shadow.get(key);
            if (!Objects.equals(prodVal, shadowVal)) {
                diffs.add(NodeChange.PropertyDiff.builder()
                        .property(key)
                        .oldValue(prodVal)
                        .newValue(shadowVal)
                        .build());
            }
        }

        // Check for properties removed in shadow
        for (String key : prod.keySet()) {
            if (!shadow.containsKey(key)) {
                diffs.add(NodeChange.PropertyDiff.builder()
                        .property(key)
                        .oldValue(prod.get(key))
                        .newValue(null)
                        .build());
            }
        }

        return diffs;
    }

    // ========================= SUMMARY =========================

    private DiffSummary buildSummary(List<NodeChange> added, List<NodeChange> modified, List<NodeChange> removed,
                                      List<RelationshipChange> addedRels, List<RelationshipChange> removedRels) {
        return DiffSummary.builder()
                .totalChanges(added.size() + modified.size() + removed.size()
                        + addedRels.size() + removedRels.size())
                .nodesAdded(added.size())
                .nodesModified(modified.size())
                .nodesRemoved(removed.size())
                .relationshipsAdded(addedRels.size())
                .relationshipsRemoved(removedRels.size())
                .addedByType(countByType(added))
                .modifiedByType(countByType(modified))
                .removedByType(countByType(removed))
                .build();
    }

    private Map<String, Integer> countByType(List<NodeChange> changes) {
        return changes.stream()
                .collect(Collectors.groupingBy(NodeChange::getNodeType,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
    }
}
