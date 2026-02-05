package com.architecture.memory.orkestify.service.graph;

import com.architecture.memory.orkestify.dto.graph.CircularDependency;
import com.architecture.memory.orkestify.model.graph.nodes.*;
import com.architecture.memory.orkestify.repository.graph.ServiceNodeRepository;
import com.architecture.memory.orkestify.repository.graph.EndpointNodeRepository;
import com.architecture.memory.orkestify.repository.graph.KafkaListenerNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects circular dependencies in the graph using DFS cycle detection.
 *
 * Analyzes two types of cycles:
 * 1. Service-level: ServiceA -> ServiceB -> ServiceC -> ServiceA (cross-service circular dependency)
 * 2. Method-level: method call chains that form cycles
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CircularDependencyDetector {

    private final ServiceNodeRepository serviceNodeRepository;
    private final EndpointNodeRepository endpointNodeRepository;
    private final KafkaListenerNodeRepository kafkaListenerNodeRepository;

    /**
     * Detect all circular dependencies for a given project.
     */
    public List<CircularDependency> detectCircularDependencies(String projectId) {
        log.info("Detecting circular dependencies for project: {}", projectId);

        List<CircularDependency> results = new ArrayList<>();

        // 1. Detect service-level circular dependencies
        results.addAll(detectServiceLevelCycles(projectId));

        // 2. Detect method-level circular call chains
        results.addAll(detectMethodLevelCycles(projectId));

        log.info("Found {} circular dependencies for project: {}", results.size(), projectId);
        return results;
    }

    /**
     * Compare circular dependencies between production and shadow to mark new ones.
     */
    public List<CircularDependency> detectAndCompare(String productionProjectId, String shadowProjectId) {
        List<CircularDependency> prodCycles = detectCircularDependencies(productionProjectId);
        List<CircularDependency> shadowCycles = detectCircularDependencies(shadowProjectId);

        // Build set of production cycle signatures for comparison
        Set<String> prodCycleSignatures = prodCycles.stream()
                .map(this::cycleSignature)
                .collect(Collectors.toSet());

        // Mark shadow cycles that are new (not present in production)
        for (CircularDependency cycle : shadowCycles) {
            String sig = cycleSignature(cycle);
            cycle.setNewInShadow(!prodCycleSignatures.contains(sig));
        }

        return shadowCycles;
    }

    // ========================= SERVICE-LEVEL CYCLES =========================

    /**
     * Detect cycles at the service class level.
     * Builds a directed graph: ServiceA -> ServiceB if any method in ServiceA
     * calls a method that belongs to ServiceB.
     */
    private List<CircularDependency> detectServiceLevelCycles(String projectId) {
        List<ServiceNode> services = serviceNodeRepository.findByProjectIdWithMethods(projectId);

        // Build adjacency list: serviceName -> set of called service names
        Map<String, Set<String>> adjacency = new HashMap<>();
        // Track detailed edges for cycle reporting
        Map<String, List<CircularDependency.CycleEdge>> edgeDetails = new HashMap<>();

        for (ServiceNode service : services) {
            String serviceKey = service.getClassName();
            adjacency.putIfAbsent(serviceKey, new HashSet<>());

            if (service.getMethods() == null) continue;

            for (MethodNode method : service.getMethods()) {
                if (method.getCalls() == null) continue;
                for (MethodNode called : method.getCalls()) {
                    String calledService = called.getClassName();
                    // Only track cross-service calls
                    if (calledService != null && !calledService.equals(serviceKey)) {
                        adjacency.computeIfAbsent(serviceKey, k -> new HashSet<>()).add(calledService);

                        String edgeKey = serviceKey + "->" + calledService;
                        edgeDetails.computeIfAbsent(edgeKey, k -> new ArrayList<>())
                                .add(CircularDependency.CycleEdge.builder()
                                        .fromClass(serviceKey)
                                        .fromMethod(method.getMethodName())
                                        .toClass(calledService)
                                        .toMethod(called.getMethodName())
                                        .relationshipType("CALLS")
                                        .build());
                    }
                }
            }
        }

        // Run DFS cycle detection on the adjacency graph
        List<List<String>> cycles = findCyclesDFS(adjacency);

        return cycles.stream()
                .map(cycle -> {
                    List<CircularDependency.CycleEdge> edges = new ArrayList<>();
                    for (int i = 0; i < cycle.size() - 1; i++) {
                        String edgeKey = cycle.get(i) + "->" + cycle.get(i + 1);
                        List<CircularDependency.CycleEdge> edgeList = edgeDetails.get(edgeKey);
                        if (edgeList != null && !edgeList.isEmpty()) {
                            edges.add(edgeList.get(0));
                        }
                    }

                    return CircularDependency.builder()
                            .severity(CircularDependency.Severity.ERROR)
                            .description("Circular dependency between services: "
                                    + String.join(" -> ", cycle))
                            .cycle(cycle)
                            .cycleEdges(edges)
                            .newInShadow(false)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ========================= METHOD-LEVEL CYCLES =========================

    /**
     * Detect cycles at the method call level.
     * Finds recursive call chains where MethodA -> MethodB -> ... -> MethodA.
     */
    private List<CircularDependency> detectMethodLevelCycles(String projectId) {
        List<ServiceNode> services = serviceNodeRepository.findByProjectIdWithMethods(projectId);

        // Build method-level adjacency
        Map<String, Set<String>> adjacency = new HashMap<>();
        Map<String, String> methodToService = new HashMap<>();

        for (ServiceNode service : services) {
            if (service.getMethods() == null) continue;
            for (MethodNode method : service.getMethods()) {
                String methodKey = service.getClassName() + "." + method.getMethodName();
                methodToService.put(methodKey, service.getClassName());
                adjacency.putIfAbsent(methodKey, new HashSet<>());

                if (method.getCalls() == null) continue;
                for (MethodNode called : method.getCalls()) {
                    String calledKey = called.getClassName() + "." + called.getMethodName();
                    adjacency.computeIfAbsent(methodKey, k -> new HashSet<>()).add(calledKey);
                }
            }
        }

        List<List<String>> cycles = findCyclesDFS(adjacency);

        return cycles.stream()
                .filter(cycle -> {
                    // Only report method-level cycles that span multiple services
                    Set<String> involvedServices = cycle.stream()
                            .map(m -> methodToService.getOrDefault(m, "unknown"))
                            .collect(Collectors.toSet());
                    return involvedServices.size() > 1;
                })
                .map(cycle -> {
                    List<CircularDependency.CycleEdge> edges = new ArrayList<>();
                    for (int i = 0; i < cycle.size() - 1; i++) {
                        String from = cycle.get(i);
                        String to = cycle.get(i + 1);
                        String[] fromParts = from.split("\\.", 2);
                        String[] toParts = to.split("\\.", 2);
                        edges.add(CircularDependency.CycleEdge.builder()
                                .fromClass(fromParts[0])
                                .fromMethod(fromParts.length > 1 ? fromParts[1] : from)
                                .toClass(toParts[0])
                                .toMethod(toParts.length > 1 ? toParts[1] : to)
                                .relationshipType("CALLS")
                                .build());
                    }

                    return CircularDependency.builder()
                            .severity(CircularDependency.Severity.WARNING)
                            .description("Circular method call chain: "
                                    + String.join(" -> ", cycle))
                            .cycle(cycle)
                            .cycleEdges(edges)
                            .newInShadow(false)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ========================= DFS CYCLE DETECTION =========================

    /**
     * Find all unique cycles in a directed graph using DFS.
     * Returns list of cycles, where each cycle is a list of node keys
     * with the first and last element being the same (to show the cycle).
     */
    private List<List<String>> findCyclesDFS(Map<String, Set<String>> adjacency) {
        List<List<String>> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        Map<String, String> parent = new HashMap<>();
        Set<String> reportedCycles = new HashSet<>();

        for (String node : adjacency.keySet()) {
            if (!visited.contains(node)) {
                dfs(node, adjacency, visited, inStack, parent, cycles, reportedCycles);
            }
        }

        return cycles;
    }

    private void dfs(String node, Map<String, Set<String>> adjacency,
                     Set<String> visited, Set<String> inStack,
                     Map<String, String> parent,
                     List<List<String>> cycles, Set<String> reportedCycles) {
        visited.add(node);
        inStack.add(node);

        Set<String> neighbors = adjacency.getOrDefault(node, Collections.emptySet());
        for (String neighbor : neighbors) {
            if (!adjacency.containsKey(neighbor)) continue; // skip external nodes

            if (!visited.contains(neighbor)) {
                parent.put(neighbor, node);
                dfs(neighbor, adjacency, visited, inStack, parent, cycles, reportedCycles);
            } else if (inStack.contains(neighbor)) {
                // Found a cycle - reconstruct it
                List<String> cycle = reconstructCycle(neighbor, node, parent);
                String cycleKey = normalizeCycleKey(cycle);
                if (reportedCycles.add(cycleKey)) {
                    cycles.add(cycle);
                }
            }
        }

        inStack.remove(node);
    }

    private List<String> reconstructCycle(String start, String end, Map<String, String> parent) {
        List<String> cycle = new ArrayList<>();
        cycle.add(end);

        String current = end;
        int maxDepth = 100; // safety limit
        while (!current.equals(start) && maxDepth-- > 0) {
            current = parent.getOrDefault(current, start);
            cycle.add(current);
        }

        Collections.reverse(cycle);
        cycle.add(start); // close the cycle
        return cycle;
    }

    /**
     * Normalize a cycle key so that the same cycle starting from different nodes
     * produces the same signature.
     */
    private String normalizeCycleKey(List<String> cycle) {
        if (cycle.size() <= 1) return cycle.toString();
        // Remove the closing element (duplicate of first)
        List<String> core = cycle.subList(0, cycle.size() - 1);
        // Find the minimum element as the canonical start
        String min = Collections.min(core);
        int minIdx = core.indexOf(min);
        // Rotate so minimum is first
        List<String> normalized = new ArrayList<>();
        for (int i = 0; i < core.size(); i++) {
            normalized.add(core.get((minIdx + i) % core.size()));
        }
        return normalized.toString();
    }

    private String cycleSignature(CircularDependency dep) {
        if (dep.getCycle() == null) return "";
        return normalizeCycleKey(dep.getCycle());
    }
}
