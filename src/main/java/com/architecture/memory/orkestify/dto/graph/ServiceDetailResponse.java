package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Level 3: Service details with callers and methods.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceDetailResponse {

    private ServiceInfo service;
    private List<CallerInfo> calledBy;
    private List<MethodDetail> methods;
    private List<RepositoryUsage> repositoriesUsed;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceInfo {
        private String id;
        private String className;
        private String packageName;
        private LineRange lineRange;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallerInfo {
        private String callerType;  // Controller, Service, KafkaListener
        private String callerClass;
        private String callerMethod;
        private Integer lineNumber;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MethodDetail {
        private String id;
        private String methodName;
        private String signature;
        private LineRange lineRange;
        private List<MethodCall> calls;
        private List<KafkaProduceInfo> kafkaProduces;
        private List<ExternalCallInfo> externalCalls;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MethodCall {
        private String targetClass;
        private String targetMethod;
        private Integer lineNumber;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KafkaProduceInfo {
        private String topic;
        private Integer lineNumber;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExternalCallInfo {
        private String url;
        private String httpMethod;
        private boolean resolved;
        private Integer lineNumber;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RepositoryUsage {
        private String className;
        private String tableName;
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
