package com.architecture.memory.orkestify.repository.graph;

import com.architecture.memory.orkestify.model.graph.nodes.ApplicationNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationNodeRepository extends Neo4jRepository<ApplicationNode, String> {

    List<ApplicationNode> findByProjectId(String projectId);

    Optional<ApplicationNode> findByProjectIdAndAppKey(String projectId, String appKey);

    Optional<ApplicationNode> findByAppKey(String appKey);

    void deleteByProjectId(String projectId);

    @Query("MATCH (a:Application {projectId: $projectId})-[r]->(n) DETACH DELETE a, n")
    void deleteApplicationGraphByProjectId(String projectId);

    @Query("MATCH (a:Application {projectId: $projectId}) " +
           "OPTIONAL MATCH (a)-[:CONTAINS_CONTROLLER]->(c:Controller) " +
           "OPTIONAL MATCH (a)-[:CONTAINS_SERVICE]->(s:Service) " +
           "OPTIONAL MATCH (a)-[:CONTAINS_REPOSITORY]->(r:RepositoryClass) " +
           "OPTIONAL MATCH (a)-[:CONTAINS_KAFKA_LISTENER]->(k:KafkaListener) " +
           "RETURN a, collect(DISTINCT c) as controllers, collect(DISTINCT s) as services, " +
           "collect(DISTINCT r) as repositories, collect(DISTINCT k) as kafkaListeners")
    List<ApplicationNode> findByProjectIdWithRelationships(String projectId);

    @Query("MATCH (a:Application {appKey: $appKey}) " +
           "OPTIONAL MATCH (a)-[*1..3]->(n) " +
           "RETURN a, collect(DISTINCT n) as relatedNodes")
    Optional<ApplicationNode> findByAppKeyWithFullGraph(String appKey);
}
