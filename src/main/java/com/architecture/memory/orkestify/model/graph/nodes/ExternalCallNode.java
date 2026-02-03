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

@Node("ExternalCall")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalCallNode {

    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;

    @Property("clientType")
    private String clientType; // RestTemplate, WebClient, Feign

    @Property("httpMethod")
    private String httpMethod;

    @Property("url")
    private String url;

    @Property("targetClass")
    private String targetClass;

    @Property("targetMethod")
    private String targetMethod;

    @Property("lineStart")
    private Integer lineStart;

    @Property("lineEnd")
    private Integer lineEnd;

    @Property("projectId")
    private String projectId;

    @Property("appKey")
    private String appKey;

    // Resolved target information
    @Property("targetService")
    private String targetService;

    @Property("targetEndpoint")
    private String targetEndpoint;

    @Property("targetControllerClass")
    private String targetControllerClass;

    @Property("targetHandlerMethod")
    private String targetHandlerMethod;

    @Property("resolved")
    private boolean resolved;

    @Property("resolutionReason")
    private String resolutionReason;

    // Link to the resolved endpoint if found within the same project
    @Relationship(type = "CALLS_ENDPOINT", direction = Relationship.Direction.OUTGOING)
    private EndpointNode targetEndpointNode;
}
