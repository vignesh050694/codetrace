package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Level 3: Controller details with endpoints and internal flow.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ControllerDetailResponse {

    private ControllerInfo controller;
    private List<EndpointDetail> endpoints;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ControllerInfo {
        private String id;
        private String className;
        private String packageName;
        private String baseUrl;
        private LineRange lineRange;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EndpointDetail {
        private String id;
        private String httpMethod;
        private String path;
        private String handlerMethod;
        private String signature;
        private LineRange lineRange;
        private String requestBody;
        private String responseType;
        private InternalFlow internalFlow;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InternalFlow {
        private List<ServiceCall> serviceCalls;
        private List<KafkaProduceInfo> kafkaProduces;
        private List<ExternalCallInfo> externalCalls;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceCall {
        private String targetService;
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
        private String targetService;
        private Integer lineNumber;
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
