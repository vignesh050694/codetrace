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
           "OPTIONAL MATCH p1 = (a)-[:CONTAINS_CONTROLLER]->(c:Controller) " +
           "OPTIONAL MATCH p2 = (a)-[:CONTAINS_SERVICE]->(s:Service) " +
           "OPTIONAL MATCH p3 = (a)-[:CONTAINS_REPOSITORY]->(r:RepositoryClass) " +
           "OPTIONAL MATCH p4 = (a)-[:CONTAINS_KAFKA_LISTENER]->(k:KafkaListener) " +
           "OPTIONAL MATCH p5 = (a)-[:CONTAINS_CONFIGURATION]->(cfg:Configuration) " +
           "RETURN a, " +
           "collect(DISTINCT nodes(p1)) as controllerNodes, collect(DISTINCT relationships(p1)) as controllerRels, " +
           "collect(DISTINCT nodes(p2)) as serviceNodes, collect(DISTINCT relationships(p2)) as serviceRels, " +
           "collect(DISTINCT nodes(p3)) as repoNodes, collect(DISTINCT relationships(p3)) as repoRels, " +
           "collect(DISTINCT nodes(p4)) as kafkaNodes, collect(DISTINCT relationships(p4)) as kafkaRels, " +
           "collect(DISTINCT nodes(p5)) as configNodes, collect(DISTINCT relationships(p5)) as configRels")
    List<ApplicationNode> findByProjectIdWithRelationships(String projectId);

    @Query("MATCH (a:Application {id: $appId}) " +
           "OPTIONAL MATCH p1 = (a)-[:CONTAINS_CONTROLLER]->(c:Controller)-[:HAS_ENDPOINT]->(e:Endpoint) " +
           "OPTIONAL MATCH p2 = (a)-[:CONTAINS_SERVICE]->(s:Service) " +
           "OPTIONAL MATCH p3 = (a)-[:CONTAINS_REPOSITORY]->(r:RepositoryClass)-[:ACCESSES]->(t:DatabaseTable) " +
           "OPTIONAL MATCH p4 = (a)-[:CONTAINS_KAFKA_LISTENER]->(k:KafkaListener)-[:HAS_LISTENER_METHOD]->(lm:KafkaListenerMethod) " +
           "OPTIONAL MATCH p5 = (a)-[:CONTAINS_CONFIGURATION]->(cfg:Configuration) " +
           "RETURN a, " +
           "collect(DISTINCT nodes(p1)), collect(DISTINCT relationships(p1)), " +
           "collect(DISTINCT nodes(p2)), collect(DISTINCT relationships(p2)), " +
           "collect(DISTINCT nodes(p3)), collect(DISTINCT relationships(p3)), " +
           "collect(DISTINCT nodes(p4)), collect(DISTINCT relationships(p4)), " +
           "collect(DISTINCT nodes(p5)), collect(DISTINCT relationships(p5))")
    ApplicationNode findByIdWithFullRelationships(String appId);

    @Query("MATCH (a:Application {appKey: $appKey}) " +
           "OPTIONAL MATCH (a)-[*1..3]->(n) " +
           "RETURN a, collect(DISTINCT n) as relatedNodes")
    Optional<ApplicationNode> findByAppKeyWithFullGraph(String appKey);
}
