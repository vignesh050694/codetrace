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

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Node("Application")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationNode {

    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("repoUrl")
    private String repoUrl;

    @Property("appKey")
    private String appKey;

    @Property("mainClassName")
    private String mainClassName;

    @Property("mainClassPackage")
    private String mainClassPackage;

    @Property("isSpringBoot")
    private boolean isSpringBoot;

    @Property("rootPath")
    private String rootPath;

    @Property("lineStart")
    private Integer lineStart;

    @Property("lineEnd")
    private Integer lineEnd;

    @Property("analyzedAt")
    private LocalDateTime analyzedAt;

    @Property("status")
    private String status;

    @Relationship(type = "CONTAINS_CONTROLLER", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<ControllerNode> controllers = new HashSet<>();

    @Relationship(type = "CONTAINS_SERVICE", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<ServiceNode> services = new HashSet<>();

    @Relationship(type = "CONTAINS_REPOSITORY", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<RepositoryClassNode> repositories = new HashSet<>();

    @Relationship(type = "CONTAINS_KAFKA_LISTENER", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<KafkaListenerNode> kafkaListeners = new HashSet<>();

    @Relationship(type = "CONTAINS_CONFIGURATION", direction = Relationship.Direction.OUTGOING)
    @Builder.Default
    private Set<ConfigurationNode> configurations = new HashSet<>();
}
