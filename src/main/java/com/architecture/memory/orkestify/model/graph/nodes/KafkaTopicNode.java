package com.architecture.memory.orkestify.model.graph.nodes;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import java.util.ArrayList;
import java.util.List;

@Node("KafkaTopic")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KafkaTopicNode {

    @Id
    @GeneratedValue(generatorClass = UUIDStringGenerator.class)
    private String id;

    @Property("canonicalId")
    private String canonicalId;

    @Property("name")
    private String name;

    @Property("projectId")
    private String projectId;

    @Property("appKey")
    private String appKey;

    // Resolved producer details: "ServiceClass.methodName" for each producer
    @Property("producerDetails")
    @Builder.Default
    private List<String> producerDetails = new ArrayList<>();

    // Resolved consumer details: "ListenerClass.methodName" for each consumer
    @Property("consumerDetails")
    @Builder.Default
    private List<String> consumerDetails = new ArrayList<>();

    // Service class names that produce to this topic
    @Property("producerServiceNames")
    @Builder.Default
    private List<String> producerServiceNames = new ArrayList<>();

    // Service class names that consume from this topic
    @Property("consumerServiceNames")
    @Builder.Default
    private List<String> consumerServiceNames = new ArrayList<>();
}
