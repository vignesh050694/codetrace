package com.architecture.memory.orkestify.repository.graph;

import com.architecture.memory.orkestify.model.graph.nodes.ControllerNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ControllerNodeRepository extends Neo4jRepository<ControllerNode, String> {

    List<ControllerNode> findByProjectId(String projectId);

    List<ControllerNode> findByProjectIdAndAppKey(String projectId, String appKey);

    @Query("MATCH (c:Controller {projectId: $projectId, className: $className}) " +
           "RETURN c LIMIT 1")
    Optional<ControllerNode> findByProjectIdAndClassName(String projectId, String className);

    @Query("MATCH (c:Controller {projectId: $projectId})-[:HAS_ENDPOINT]->(e:Endpoint) " +
           "RETURN DISTINCT c")
    List<ControllerNode> findByProjectIdWithEndpoints(String projectId);

    @Query("MATCH (c:Controller {className: $className, packageName: $packageName, projectId: $projectId}) " +
           "RETURN c")
    Optional<ControllerNode> findByFullyQualifiedName(String projectId, String packageName, String className);

    @Query("MATCH (c:Controller {id: $controllerId}) " +
           "OPTIONAL MATCH (c)-[:HAS_ENDPOINT]->(e:Endpoint) " +
           "OPTIONAL MATCH (e)-[:CALLS]->(m:Method) " +
           "OPTIONAL MATCH (e)-[:MAKES_EXTERNAL_CALL]->(ec:ExternalCall) " +
           "OPTIONAL MATCH (e)-[:PRODUCES_TO]->(t:KafkaTopic) " +
           "RETURN c LIMIT 1")
    Optional<ControllerNode> findByIdWithFullDetails(String controllerId);

    List<ControllerNode> findByAppKey(String appKey);
}
