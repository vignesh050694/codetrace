package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Level 2: Application details with all components.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationDetailResponse {

    private ApplicationInfo application;
    private List<ControllerItem> controllers;
    private List<ServiceItem> services;
    private KafkaInfo kafka;
    private List<DatabaseItem> databases;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApplicationInfo {
        private String id;
        private String name;
        private String packageName;
        private String repoUrl;
        private boolean isSpringBoot;
        private LineRange lineRange;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ControllerItem {
        private String id;
        private String className;
        private String packageName;
        private String baseUrl;
        private int endpointsCount;
        private List<EndpointSummary> endpoints;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EndpointSummary {
        private String id;
        private String method;
        private String path;
        private String handlerMethod;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceItem {
        private String id;
        private String className;
        private String packageName;
        private int methodsCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KafkaInfo {
        private List<ProducerItem> producers;
        private List<ConsumerItem> consumers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProducerItem {
        private String id;
        private String topic;
        private String producerClass;
        private String producerMethod;
        private Integer lineNumber;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsumerItem {
        private String id;
        private String topic;
        private String listenerClass;
        private String listenerMethod;
        private String groupId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DatabaseItem {
        private String id;
        private String tableName;
        private String repositoryClass;
        private String databaseType;
        private List<String> operations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineRange {
        private Integer start;
        private Integer end;
    }
}
