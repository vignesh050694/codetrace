package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * High-level architecture summary for a project.
 * Provides counts and statistics for the project's codebase.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchitectureSummaryResponse {

    private String projectId;
    private int totalApplications;
    private int totalControllers;
    private int totalEndpoints;
    private int totalServices;
    private int totalRepositories;
    private int totalKafkaTopics;
    private int totalKafkaListeners;
    private int totalConfigurations;
    private int totalDatabaseTables;

    private Map<String, Long> endpointsByMethod;     // GET: 25, POST: 15, etc.
    private Map<String, Long> repositoriesByType;    // JPA: 10, MongoDB: 5, etc.

    private List<ApplicationSummary> applications;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApplicationSummary {
        private String id;
        private String appKey;
        private String mainClassName;
        private String packageName;
        private String repoUrl;
        private boolean isSpringBoot;
        private int controllersCount;
        private int servicesCount;
        private int repositoriesCount;
        private int kafkaListenersCount;
        private int configurationsCount;
    }
}
