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

@Node("Method")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodNode {

    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;

    @Property("methodName")
    private String methodName;

    @Property("className")
    private String className;

    @Property("packageName")
    private String packageName;

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

    @Property("methodType")
    private String methodType; // SERVICE_METHOD, REPOSITORY_METHOD, LISTENER_METHOD

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
