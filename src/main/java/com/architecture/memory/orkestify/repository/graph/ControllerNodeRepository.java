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

    Optional<ControllerNode> findByProjectIdAndClassName(String projectId, String className);

    @Query("MATCH (c:Controller {projectId: $projectId})-[:HAS_ENDPOINT]->(e:Endpoint) " +
           "RETURN c, collect(e) as endpoints")
    List<ControllerNode> findByProjectIdWithEndpoints(String projectId);

    @Query("MATCH (c:Controller {className: $className, packageName: $packageName, projectId: $projectId}) " +
           "RETURN c")
    Optional<ControllerNode> findByFullyQualifiedName(String projectId, String packageName, String className);

    @Query("MATCH (c:Controller {id: $controllerId}) " +
           "OPTIONAL MATCH p1 = (c)-[:HAS_ENDPOINT]->(e:Endpoint) " +
           "OPTIONAL MATCH p2 = (e)-[:CALLS]->(m:Method) " +
           "OPTIONAL MATCH p3 = (e)-[:MAKES_EXTERNAL_CALL]->(ec:ExternalCall) " +
           "OPTIONAL MATCH p4 = (e)-[:PRODUCES_TO]->(t:KafkaTopic) " +
           "RETURN c, " +
           "collect(DISTINCT nodes(p1)), collect(DISTINCT relationships(p1)), " +
           "collect(DISTINCT nodes(p2)), collect(DISTINCT relationships(p2)), " +
           "collect(DISTINCT nodes(p3)), collect(DISTINCT relationships(p3)), " +
           "collect(DISTINCT nodes(p4)), collect(DISTINCT relationships(p4))")
    Optional<ControllerNode> findByIdWithFullDetails(String controllerId);

    List<ControllerNode> findByAppKey(String appKey);
}
