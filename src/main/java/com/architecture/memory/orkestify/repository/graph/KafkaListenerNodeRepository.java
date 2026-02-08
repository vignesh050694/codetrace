package com.architecture.memory.orkestify.repository.graph;

import com.architecture.memory.orkestify.model.graph.nodes.KafkaListenerNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KafkaListenerNodeRepository extends Neo4jRepository<KafkaListenerNode, String> {

    List<KafkaListenerNode> findByProjectId(String projectId);

    List<KafkaListenerNode> findByProjectIdAndAppKey(String projectId, String appKey);

    @Query("MATCH (k:KafkaListener {projectId: $projectId, className: $className}) " +
           "RETURN k LIMIT 1")
    Optional<KafkaListenerNode> findByProjectIdAndClassName(String projectId, String className);

    @Query("MATCH (k:KafkaListener {className: $className, packageName: $packageName, projectId: $projectId}) " +
           "RETURN k")
    Optional<KafkaListenerNode> findByFullyQualifiedName(String projectId, String packageName, String className);

    @Query("MATCH (k:KafkaListener {projectId: $projectId})-[:HAS_LISTENER_METHOD]->(m:KafkaListenerMethod) " +
           "RETURN DISTINCT k")
    List<KafkaListenerNode> findByProjectIdWithListenerMethods(String projectId);

    default List<KafkaListenerNode> findByProjectIdWithMethods(String projectId) {
        return findByProjectIdWithListenerMethods(projectId);
    }

    @Query("MATCH (k:KafkaListener {projectId: $projectId})-[:HAS_LISTENER_METHOD]->(m:KafkaListenerMethod)-[:CONSUMES_FROM]->(t:KafkaTopic) " +
           "RETURN DISTINCT k, t")
    List<Object[]> findKafkaListenersWithTopics(String projectId);

    @Query("MATCH (k:KafkaListener {projectId: $projectId})-[:HAS_LISTENER_METHOD]->(m:KafkaListenerMethod)-[:MAKES_EXTERNAL_CALL]->(ec:ExternalCall) " +
           "RETURN DISTINCT k")
    List<KafkaListenerNode> findByProjectIdWithExternalCalls(String projectId);
}
