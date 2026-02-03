package com.architecture.memory.orkestify.model.graph.nodes;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import java.util.HashSet;
import java.util.Set;

@Node("Endpoint")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndpointNode {

    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;

    @Property("httpMethod")
    private String httpMethod;

    @Property("path")
    private String path;

    @Property("fullPath")
    private String fullPath;

    @Property("handlerMethod")
    private String handlerMethod;

    @Property("signature")
    private String signature;

    @Property("lineStart")
    private Integer lineStart;

    @Property("lineEnd")
    private Integer lineEnd;

    @Property("projectId")
    private String projectId;

    @Property("appKey")
    private String appKey;

    @Property("controllerClass")
    private String controllerClass;

    // Request/Response info
    @Property("requestBodyType")
    private String requestBodyType;

    @Property("responseType")
    private String responseType;

    @Relationship(type = "CALLS", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<MethodNode> calls = new HashSet<>();

    @Relationship(type = "MAKES_EXTERNAL_CALL", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<ExternalCallNode> externalCalls = new HashSet<>();

    @Relationship(type = "PRODUCES_TO", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<KafkaTopicNode> producesToTopics = new HashSet<>();
}
