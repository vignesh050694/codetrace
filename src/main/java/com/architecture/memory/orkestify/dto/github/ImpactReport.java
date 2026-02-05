package com.architecture.memory.orkestify.dto.github;

import com.architecture.memory.orkestify.dto.graph.CircularDependency;
import com.architecture.memory.orkestify.dto.graph.DiffSummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of impact analysis for a pull request.
 * Maps changed files to affected architecture components and their dependencies.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImpactReport {

    private String projectId;
    private String branchName;
    private int prNumber;
    private String prUrl;

    @Builder.Default
    private List<ChangedComponent> changedComponents = new ArrayList<>();

    @Builder.Default
    private List<AffectedEndpoint> affectedEndpoints = new ArrayList<>();

    @Builder.Default
    private List<AffectedFlow> affectedFlows = new ArrayList<>();

    @Builder.Default
    private List<CircularDependency> newCircularDependencies = new ArrayList<>();

    private DiffSummary diffSummary;

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /**
     * A component (controller, service, repository) directly changed in the PR.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChangedComponent {
        private String type;            // Controller, Service, Repository, KafkaListener
        private String className;
        private String packageName;
        private String fileStatus;      // added, modified, removed
        private int linesAdded;
        private int linesRemoved;

        @Builder.Default
        private List<String> upstreamCallers = new ArrayList<>();   // Who calls this component

        @Builder.Default
        private List<String> downstreamCallees = new ArrayList<>(); // What this component calls
    }

    /**
     * An API endpoint affected by the changes (directly or transitively).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AffectedEndpoint {
        private String httpMethod;
        private String path;
        private String controllerClass;
        private String methodName;
        private boolean directlyChanged;  // true if the controller itself was changed
        private String reason;            // Why this endpoint is affected
    }

    /**
     * A request flow (endpoint → service → repository → DB) affected by changes.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AffectedFlow {
        private String endpoint;        // "GET /api/users"
        private List<String> callChain; // ["UserController", "UserService", "UserRepository"]
        private String affectedAt;      // Which component in the chain was changed
    }
}
