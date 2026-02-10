package com.architecture.memory.orkestify.service.github;

import com.architecture.memory.orkestify.dto.github.ImpactReport;
import com.architecture.memory.orkestify.dto.github.ImpactReport.AffectedEndpoint;
import com.architecture.memory.orkestify.dto.github.ImpactReport.AffectedFlow;
import com.architecture.memory.orkestify.dto.github.ImpactReport.ChangedComponent;
import com.architecture.memory.orkestify.dto.github.PullRequestFile;
import com.architecture.memory.orkestify.dto.graph.CircularDependency;
import com.architecture.memory.orkestify.dto.graph.ShadowGraphDiff;
import com.architecture.memory.orkestify.model.graph.nodes.*;
import com.architecture.memory.orkestify.repository.graph.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Analyzes the impact of PR changes on the architecture graph.
 * Maps changed Java files to graph nodes and traces upstream/downstream dependencies.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImpactAnalysisService {

    private final ControllerNodeRepository controllerNodeRepository;
    private final ServiceNodeRepository serviceNodeRepository;
    private final RepositoryClassNodeRepository repositoryClassNodeRepository;
    private final EndpointNodeRepository endpointNodeRepository;
    private final KafkaListenerNodeRepository kafkaListenerNodeRepository;
    private final MethodNodeRepository methodNodeRepository;
    private final ApiBreakingChangeDetector apiBreakingChangeDetector;

    /**
     * Analyze the impact of changed files on the architecture.
     *
     * @param projectId    The production project ID
     * @param changedFiles List of changed files from the PR
     * @param diff         The computed shadow graph diff (may be null if analysis failed)
     * @param circularDeps Circular dependencies detected (may be null)
     * @return Impact report describing affected components and flows
     */
    public ImpactReport analyzeImpact(
            String projectId,
            List<PullRequestFile> changedFiles,
            ShadowGraphDiff diff,
            List<CircularDependency> circularDeps) {

        log.info("Analyzing impact for project {} with {} changed files", projectId, changedFiles.size());

        // Filter to Java main source files only
        List<PullRequestFile> javaFiles = changedFiles.stream()
                .filter(PullRequestFile::isJavaFile)
                .filter(PullRequestFile::isMainSource)
                .toList();

        log.info("Found {} Java main source files in PR", javaFiles.size());

        List<ChangedComponent> changedComponents = new ArrayList<>();
        Set<String> affectedClassNames = new HashSet<>();

        // Map each changed file to graph nodes
        for (PullRequestFile file : javaFiles) {
            String className = file.extractSimpleClassName();
            String packageName = file.extractPackageName();
            if (className == null) continue;

            affectedClassNames.add(className);
            ChangedComponent component = mapFileToComponent(projectId, file, className, packageName);
            if (component != null) {
                changedComponents.add(component);
            }
        }

        // Find affected endpoints (directly changed or transitively via changed services)
        List<AffectedEndpoint> affectedEndpoints = findAffectedEndpoints(projectId, affectedClassNames);

        // Build affected flows
        List<AffectedFlow> affectedFlows = buildAffectedFlows(projectId, affectedEndpoints, affectedClassNames);

        // Detect API URL changes in service clients
        List<ApiBreakingChangeDetector.ApiUrlChange> urlChanges = apiBreakingChangeDetector.detectApiUrlChanges(changedFiles);
        List<String> urlChangeDescriptions = urlChanges.stream()
                .map(ApiBreakingChangeDetector.ApiUrlChange::getDescription)
                .toList();

        // Build detailed URL change information with consumer analysis
        List<ImpactReport.ApiUrlChangeDetail> urlChangeDetails = buildApiUrlChangeDetails(
                projectId, urlChanges);

        // Filter to only new circular dependencies
        List<CircularDependency> newCircularDeps = circularDeps != null
                ? circularDeps.stream().filter(CircularDependency::isNewInShadow).toList()
                : List.of();

        // Build warnings
        List<String> warnings = buildWarnings(changedFiles, javaFiles, changedComponents, newCircularDeps, urlChanges);

        ImpactReport report = ImpactReport.builder()
                .changedComponents(changedComponents)
                .affectedEndpoints(affectedEndpoints)
                .affectedFlows(affectedFlows)
                .newCircularDependencies(newCircularDeps)
                .diffSummary(diff != null ? diff.getSummary() : null)
                .warnings(warnings)
                .apiUrlChanges(urlChangeDescriptions)
                .apiUrlChangeDetails(urlChangeDetails)
                .build();

        log.info("Impact analysis complete: {} components, {} endpoints, {} flows, {} new circular deps",
                changedComponents.size(), affectedEndpoints.size(),
                affectedFlows.size(), newCircularDeps.size());

        return report;
    }

    /**
     * Map a changed file to a graph component (controller, service, repository, or kafka listener).
     */
    private ChangedComponent mapFileToComponent(String projectId, PullRequestFile file,
                                                 String className, String packageName) {
        // Try each node type
        Optional<ControllerNode> controller = controllerNodeRepository
                .findByProjectIdAndClassName(projectId, className);
        if (controller.isPresent()) {
            return buildChangedComponent("Controller", className, packageName, file,
                    findUpstreamCallers(projectId, className),
                    findControllerDownstream(projectId, className));
        }

        Optional<ServiceNode> service = serviceNodeRepository
                .findByProjectIdAndClassName(projectId, className);
        if (service.isPresent()) {
            return buildChangedComponent("Service", className, packageName, file,
                    findUpstreamCallers(projectId, className),
                    findServiceDownstream(projectId, className));
        }

        Optional<RepositoryClassNode> repo = repositoryClassNodeRepository
                .findByProjectIdAndClassName(projectId, className);
        if (repo.isPresent()) {
            return buildChangedComponent("Repository", className, packageName, file,
                    findUpstreamCallers(projectId, className),
                    List.of());
        }

        Optional<KafkaListenerNode> listener = kafkaListenerNodeRepository
                .findByProjectIdAndClassName(projectId, className);
        if (listener.isPresent()) {
            return buildChangedComponent("KafkaListener", className, packageName, file,
                    List.of(), findServiceDownstream(projectId, className));
        }

        // Not a known graph node type — could be a DTO, config, utility, etc.
        log.debug("Changed file {} does not map to a known graph component", className);
        return ChangedComponent.builder()
                .type("Other")
                .className(className)
                .packageName(packageName)
                .fileStatus(file.getStatus())
                .linesAdded(file.getAdditions())
                .linesRemoved(file.getDeletions())
                .build();
    }

    private ChangedComponent buildChangedComponent(String type, String className, String packageName,
                                                    PullRequestFile file, List<String> callers, List<String> callees) {
        return ChangedComponent.builder()
                .type(type)
                .className(className)
                .packageName(packageName)
                .fileStatus(file.getStatus())
                .linesAdded(file.getAdditions())
                .linesRemoved(file.getDeletions())
                .upstreamCallers(callers)
                .downstreamCallees(callees)
                .build();
    }

    /**
     * Find classes that call the given className (upstream callers).
     * Uses MethodNode relationships to trace which classes have methods calling into the target.
     */
    private List<String> findUpstreamCallers(String projectId, String className) {
        List<String> callers = new ArrayList<>();

        // Check if any endpoint's controllerClass matches, find callers via method nodes
        List<MethodNode> methods = methodNodeRepository.findByProjectIdAndClassName(projectId, className);

        // Collect unique caller class names from methods that call methods in this class
        Set<String> callerClasses = new HashSet<>();

        // Check controllers that have endpoints calling this service
        List<EndpointNode> endpoints = endpointNodeRepository.findByProjectId(projectId);
        for (EndpointNode ep : endpoints) {
            // If endpoint's controller is calling the target class, the controller is a caller
            if (ep.getControllerClass() != null && !ep.getControllerClass().equals(className)) {
                // Check if methods from this endpoint's controller call the target class
                List<MethodNode> controllerMethods = methodNodeRepository
                        .findByProjectIdAndClassName(projectId, ep.getControllerClass());
                for (MethodNode m : controllerMethods) {
                    if (m.getSignature() != null && m.getSignature().contains(className)) {
                        callerClasses.add(ep.getControllerClass());
                    }
                }
            }
        }

        // Check services that call this class
        List<ServiceNode> services = serviceNodeRepository.findByProjectId(projectId);
        for (ServiceNode svc : services) {
            if (svc.getClassName().equals(className)) continue;
            List<MethodNode> svcMethods = methodNodeRepository
                    .findByProjectIdAndClassName(projectId, svc.getClassName());
            for (MethodNode m : svcMethods) {
                if (m.getSignature() != null && m.getSignature().contains(className)) {
                    callerClasses.add(svc.getClassName());
                }
            }
        }

        callers.addAll(callerClasses);
        return callers;
    }

    /**
     * Find what a controller calls downstream (services via endpoints).
     */
    private List<String> findControllerDownstream(String projectId, String className) {
        Set<String> downstream = new LinkedHashSet<>();

        List<MethodNode> methods = methodNodeRepository.findByProjectIdAndClassName(projectId, className);
        for (MethodNode m : methods) {
            if (m.getMethodType() != null && m.getMethodType().equals("SERVICE_METHOD")) continue;
            // Methods called from this class
            if (m.getClassName() != null && !m.getClassName().equals(className)) {
                downstream.add(m.getClassName());
            }
        }

        return new ArrayList<>(downstream);
    }

    /**
     * Find what a service calls downstream (other services, repositories).
     */
    private List<String> findServiceDownstream(String projectId, String className) {
        Set<String> downstream = new LinkedHashSet<>();

        List<MethodNode> methods = methodNodeRepository.findByProjectIdAndClassName(projectId, className);
        for (MethodNode m : methods) {
            if (m.getClassName() != null && !m.getClassName().equals(className)) {
                downstream.add(m.getClassName());
            }
        }

        // Check repository calls
        List<RepositoryClassNode> repos = repositoryClassNodeRepository.findByProjectId(projectId);
        for (RepositoryClassNode repo : repos) {
            // If any method in this service references the repository
            for (MethodNode m : methods) {
                if (m.getSignature() != null && m.getSignature().contains(repo.getClassName())) {
                    downstream.add(repo.getClassName());
                }
            }
        }

        return new ArrayList<>(downstream);
    }

    /**
     * Find all endpoints affected by the changed class names.
     * An endpoint is affected if:
     * 1. Its controller class was directly changed
     * 2. A service it calls was changed
     */
    private List<AffectedEndpoint> findAffectedEndpoints(String projectId, Set<String> changedClassNames) {
        List<AffectedEndpoint> affected = new ArrayList<>();
        List<EndpointNode> endpoints = endpointNodeRepository.findByProjectId(projectId);

        for (EndpointNode ep : endpoints) {
            boolean directlyChanged = changedClassNames.contains(ep.getControllerClass());

            if (directlyChanged) {
                affected.add(AffectedEndpoint.builder()
                        .httpMethod(ep.getHttpMethod())
                        .path(ep.getFullPath())
                        .controllerClass(ep.getControllerClass())
                        .methodName(ep.getHandlerMethod())
                        .directlyChanged(true)
                        .reason("Controller class modified")
                        .build());
                continue;
            }

            // Check if any service in the endpoint's call chain was changed
            List<MethodNode> callChain = methodNodeRepository
                    .findByProjectIdAndClassName(projectId, ep.getControllerClass());
            for (MethodNode m : callChain) {
                if (m.getClassName() != null && changedClassNames.contains(m.getClassName())) {
                    affected.add(AffectedEndpoint.builder()
                            .httpMethod(ep.getHttpMethod())
                            .path(ep.getFullPath())
                            .controllerClass(ep.getControllerClass())
                            .methodName(ep.getHandlerMethod())
                            .directlyChanged(false)
                            .reason("Depends on modified " + m.getClassName())
                            .build());
                    break;
                }
            }
        }

        return affected;
    }

    /**
     * Build affected flows showing endpoint → service → repository chains that are impacted.
     */
    private List<AffectedFlow> buildAffectedFlows(String projectId,
                                                    List<AffectedEndpoint> affectedEndpoints,
                                                    Set<String> changedClassNames) {
        List<AffectedFlow> flows = new ArrayList<>();

        for (AffectedEndpoint ep : affectedEndpoints) {
            String endpointLabel = ep.getHttpMethod() + " " + ep.getPath();
            List<String> callChain = new ArrayList<>();
            callChain.add(ep.getControllerClass());

            // Trace the chain: Controller → Service → Repository
            List<MethodNode> methods = methodNodeRepository
                    .findByProjectIdAndClassName(projectId, ep.getControllerClass());
            Set<String> visited = new HashSet<>();
            visited.add(ep.getControllerClass());

            for (MethodNode m : methods) {
                if (m.getClassName() != null && !visited.contains(m.getClassName())) {
                    callChain.add(m.getClassName());
                    visited.add(m.getClassName());
                }
            }

            // Find which component in the chain was changed
            String affectedAt = callChain.stream()
                    .filter(changedClassNames::contains)
                    .findFirst()
                    .orElse(ep.getControllerClass());

            flows.add(AffectedFlow.builder()
                    .endpoint(endpointLabel)
                    .callChain(callChain)
                    .affectedAt(affectedAt)
                    .build());
        }

        return flows;
    }

    /**
     * Build warning messages for the report.
     */
    private List<String> buildWarnings(List<PullRequestFile> allFiles,
                                        List<PullRequestFile> javaFiles,
                                        List<ChangedComponent> components,
                                        List<CircularDependency> newCircularDeps,
                                        List<ApiBreakingChangeDetector.ApiUrlChange> urlChanges) {
        List<String> warnings = new ArrayList<>();

        // API URL changes (CRITICAL WARNING)
        if (!urlChanges.isEmpty()) {
            long highSeverityChanges = urlChanges.stream()
                    .filter(c -> c.getSeverity() == ApiBreakingChangeDetector.Severity.HIGH)
                    .count();
            if (highSeverityChanges > 0) {
                warnings.add("⚠️ CRITICAL: " + highSeverityChanges + " service-to-service API endpoint URL(s) changed - this WILL break integration!");
            }
        }

        // Non-Java files changed
        long nonJavaCount = allFiles.size() - javaFiles.size();
        if (nonJavaCount > 0) {
            warnings.add(nonJavaCount + " non-Java file(s) changed (not analyzed for architecture impact)");
        }

        // Files that don't map to graph components
        long unmapped = components.stream().filter(c -> "Other".equals(c.getType())).count();
        if (unmapped > 0) {
            warnings.add(unmapped + " Java file(s) do not map to known architecture components (DTOs, configs, utilities)");
        }

        // New circular dependencies
        if (!newCircularDeps.isEmpty()) {
            warnings.add("WARNING: " + newCircularDeps.size() + " new circular dependency/dependencies introduced!");
        }

        return warnings;
    }

    /**
     * Build detailed API URL change information including consumer analysis
     */
    private List<ImpactReport.ApiUrlChangeDetail> buildApiUrlChangeDetails(
            String projectId,
            List<ApiBreakingChangeDetector.ApiUrlChange> urlChanges) {

        List<ImpactReport.ApiUrlChangeDetail> details = new ArrayList<>();

        for (ApiBreakingChangeDetector.ApiUrlChange change : urlChanges) {
            // Find potential consumers of the old URL
            // This looks for any component that might be calling this endpoint
            List<String> consumers = findUrlConsumers(projectId, change.getOldUrl());

            details.add(ImpactReport.ApiUrlChangeDetail.builder()
                    .className(change.getClassName())
                    .oldUrl(change.getOldUrl())
                    .newUrl(change.getNewUrl())
                    .changeType(change.getChangeType().name())
                    .breakingChangesPoints(change.getBreakingChangesPoints())
                    .consumers(consumers)
                    .build());

            if (!consumers.isEmpty()) {
                log.info("URL change in {} affects {} consumer(s): {}",
                        change.getClassName(), consumers.size(), consumers);
            } else {
                log.warn("URL change in {} has NO detected consumers - may be dead code or external call",
                        change.getClassName());
            }
        }

        return details;
    }

    /**
     * Find components that may be consuming the given URL
     * This is a best-effort search based on the endpoint path
     */
    private List<String> findUrlConsumers(String projectId, String url) {
        List<String> consumers = new ArrayList<>();

        // Extract the path for matching (e.g., "/api/users/roll/" -> "/api/users")
        String pathPrefix = extractPathPrefix(url);

        if (pathPrefix == null) {
            return consumers;
        }

        // Look for endpoints in the graph that match this path
        // These would be the providers of this API
        List<EndpointNode> matchingEndpoints = endpointNodeRepository.findByProjectId(projectId)
                .stream()
                .filter(ep -> ep.getFullPath() != null &&
                        (ep.getFullPath().startsWith(pathPrefix) || pathPrefix.startsWith(ep.getFullPath())))
                .toList();

        // For each matching endpoint, find what calls it
        for (EndpointNode endpoint : matchingEndpoints) {
            String controllerClass = endpoint.getControllerClass();
            if (controllerClass != null) {
                // Find upstream callers of this controller
                List<String> callers = findUpstreamCallers(projectId, controllerClass);
                consumers.addAll(callers);
            }
        }

        return consumers.stream().distinct().toList();
    }

    /**
     * Extract a path prefix for matching (e.g., "/api/users/roll/" -> "/api/users")
     */
    private String extractPathPrefix(String url) {
        if (url == null || !url.contains("/")) {
            return null;
        }

        // Remove trailing slash
        String path = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;

        // Get up to the second-to-last segment
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) {
            return path.substring(0, lastSlash);
        }

        return path;
    }
}
