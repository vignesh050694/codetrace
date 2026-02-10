package com.architecture.memory.orkestify.model.graph.nodes;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import java.util.HashSet;
import java.util.Set;

@Node("Service")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"methods"})
public class ServiceNode {

    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;

    @Property("canonicalId")
    private String canonicalId;

    @Property("className")
    private String className;

    @Property("packageName")
    private String packageName;

    @Property("lineStart")
    private Integer lineStart;

    @Property("lineEnd")
    private Integer lineEnd;

    @Property("projectId")
    private String projectId;

    @Property("appKey")
    private String appKey;

    @Relationship(type = "HAS_METHOD", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<MethodNode> methods = new HashSet<>();
}
