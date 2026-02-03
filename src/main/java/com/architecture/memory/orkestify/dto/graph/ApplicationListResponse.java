package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Level 1: List of applications with summary counts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationListResponse {

    private String projectId;
    private List<ApplicationItem> applications;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApplicationItem {
        private String id;
        private String name;
        private String packageName;
        private String repoUrl;
        private boolean isSpringBoot;
        private ApplicationSummary summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApplicationSummary {
        private int controllersCount;
        private int servicesCount;
        private int repositoriesCount;
        private int kafkaProducersCount;
        private int kafkaConsumersCount;
        private int databaseTablesCount;
        private int endpointsCount;
    }
}
