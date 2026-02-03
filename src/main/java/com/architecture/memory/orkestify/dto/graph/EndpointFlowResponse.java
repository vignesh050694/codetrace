package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Level 3: Endpoint internal flow with call tree.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndpointFlowResponse {

    private EndpointInfo endpoint;
    private List<CallTreeNode> callTree;
    private KafkaInteractions kafkaInteractions;
    private List<ExternalCallInfo> externalCalls;
    private List<DatabaseAccess> databaseAccess;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EndpointInfo {
        private String id;
        private String httpMethod;
        private String path;
        private String controllerClass;
        private String handlerMethod;
        private String signature;
        private LineRange lineRange;
        private String requestBody;
        private String responseType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallTreeNode {
        private int depth;
        private String type;  // Endpoint, ServiceCall, RepositoryCall, KafkaProduce, ExternalCall
        private String className;
        private String methodName;
        private String name;  // Display name
        private Integer lineNumber;
        private List<CallTreeNode> children;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KafkaInteractions {
        private List<KafkaProduceInfo> produces;
        private List<KafkaConsumeInfo> consumes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KafkaProduceInfo {
        private String topic;
        private Integer lineNumber;
        private String producerMethod;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KafkaConsumeInfo {
        private String topic;
        private String listenerClass;
        private String listenerMethod;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExternalCallInfo {
        private String url;
        private String httpMethod;
        private String clientType;
        private boolean resolved;
        private String targetService;
        private String targetEndpoint;
        private Integer lineNumber;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DatabaseAccess {
        private String table;
        private String operation;  // READ, WRITE, UPDATE, DELETE
        private String repository;
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
