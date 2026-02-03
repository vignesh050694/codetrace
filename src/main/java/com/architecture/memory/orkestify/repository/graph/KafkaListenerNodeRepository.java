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

    Optional<KafkaListenerNode> findByProjectIdAndClassName(String projectId, String className);

    @Query("MATCH (k:KafkaListener {className: $className, packageName: $packageName, projectId: $projectId}) " +
           "RETURN k")
    Optional<KafkaListenerNode> findByFullyQualifiedName(String projectId, String packageName, String className);

    @Query("MATCH (k:KafkaListener {projectId: $projectId})-[:HAS_LISTENER_METHOD]->(m:KafkaListenerMethod) " +
           "RETURN k, collect(m) as listenerMethods")
    List<KafkaListenerNode> findByProjectIdWithListenerMethods(String projectId);

    default List<KafkaListenerNode> findByProjectIdWithMethods(String projectId) {
        return findByProjectIdWithListenerMethods(projectId);
    }

    @Query("MATCH (k:KafkaListener {projectId: $projectId})-[:HAS_LISTENER_METHOD]->(m:KafkaListenerMethod)-[:CONSUMES_FROM]->(t:KafkaTopic) " +
           "RETURN k, collect(DISTINCT t.name) as consumedTopics")
    List<Object[]> findKafkaListenersWithTopics(String projectId);
}
