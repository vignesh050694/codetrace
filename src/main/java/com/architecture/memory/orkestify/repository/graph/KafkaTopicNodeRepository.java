package com.architecture.memory.orkestify.repository.graph;

import com.architecture.memory.orkestify.model.graph.nodes.KafkaTopicNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KafkaTopicNodeRepository extends Neo4jRepository<KafkaTopicNode, String> {

    Optional<KafkaTopicNode> findByName(String name);

    List<KafkaTopicNode> findByProjectId(String projectId);

    @Query("MERGE (t:KafkaTopic {name: $name, projectId: $projectId}) " +
           "RETURN t")
    KafkaTopicNode findOrCreate(String name, String projectId);

    @Query("MATCH (producer)-[:PRODUCES_TO]->(t:KafkaTopic {name: $topicName})<-[:CONSUMES_FROM]-(consumer) " +
           "RETURN t, collect(DISTINCT producer) as producers, collect(DISTINCT consumer) as consumers")
    Optional<KafkaTopicNode> findByNameWithProducersAndConsumers(String topicName);

    @Query("MATCH (t:KafkaTopic {projectId: $projectId}) " +
           "OPTIONAL MATCH (producer)-[:PRODUCES_TO]->(t) " +
           "OPTIONAL MATCH (consumer)-[:CONSUMES_FROM]->(t) " +
           "RETURN t, collect(DISTINCT producer) as producers, collect(DISTINCT consumer) as consumers")
    List<KafkaTopicNode> findAllByProjectIdWithProducersAndConsumers(String projectId);

    @Query("MATCH (t:KafkaTopic)<-[:PRODUCES_TO]-(producer) " +
           "WHERE NOT (t)<-[:CONSUMES_FROM]-() " +
           "AND t.projectId = $projectId " +
           "RETURN t")
    List<KafkaTopicNode> findTopicsWithoutConsumers(String projectId);

    @Query("MATCH (t:KafkaTopic)<-[:CONSUMES_FROM]-(consumer) " +
           "WHERE NOT (t)<-[:PRODUCES_TO]-() " +
           "AND t.projectId = $projectId " +
           "RETURN t")
    List<KafkaTopicNode> findTopicsWithoutProducers(String projectId);
}
