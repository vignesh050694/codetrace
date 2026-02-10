package com.architecture.memory.orkestify.repository.graph;

import com.architecture.memory.orkestify.model.graph.nodes.EndpointNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EndpointNodeRepository extends Neo4jRepository<EndpointNode, String> {

    List<EndpointNode> findByProjectId(String projectId);

    @Query("MATCH (e:Endpoint {projectId: $projectId, fullPath: $fullPath}) " +
           "RETURN e LIMIT 1")
    Optional<EndpointNode> findByProjectIdAndFullPath(String projectId, String fullPath);

    List<EndpointNode> findByProjectIdAndHttpMethod(String projectId, String httpMethod);

    @Query("MATCH (e:Endpoint {projectId: $projectId}) " +
            "WHERE e.fullPath =~ $pathPattern " +
            "RETURN e")
    List<EndpointNode> findByPathPattern(String projectId, String pathPattern);

    @Query("MATCH (e:Endpoint {projectId: $projectId})-[:CALLS*1..5]->(m:Method) " +
            "RETURN e, collect(m) as calledMethods")
    List<EndpointNode> findByProjectIdWithCallChain(String projectId);

    @Query("MATCH (e:Endpoint {fullPath: $fullPath, httpMethod: $httpMethod, projectId: $projectId}) " +
            "RETURN e")
    Optional<EndpointNode> findByFullPathAndMethod(String projectId, String fullPath, String httpMethod);

    @Query("MATCH (e:Endpoint {projectId: $projectId})-[:MAKES_EXTERNAL_CALL]->(ec:ExternalCall) " +
            "RETURN e, collect(ec) as externalCalls")
    List<EndpointNode> findByProjectIdWithExternalCalls(String projectId);

    @Query("MATCH (e:Endpoint {projectId: $projectId})-[:PRODUCES_TO]->(t:KafkaTopic) " +
            "RETURN e, collect(t) as topics")
    List<EndpointNode> findByProjectIdWithKafkaProducers(String projectId);

    @Query("MATCH (e:Endpoint {id: $endpointId}) " +
            "OPTIONAL MATCH p1 = (e)-[:CALLS*1..5]->(m:Method) " +
            "OPTIONAL MATCH p2 = (e)-[:MAKES_EXTERNAL_CALL]->(ec:ExternalCall) " +
            "OPTIONAL MATCH p3 = (e)-[:PRODUCES_TO]->(t:KafkaTopic) " +
            "RETURN e, " +
            "collect(DISTINCT nodes(p1)), collect(DISTINCT relationships(p1)), " +
            "collect(DISTINCT nodes(p2)), collect(DISTINCT relationships(p2)), " +
            "collect(DISTINCT nodes(p3)), collect(DISTINCT relationships(p3))")
    Optional<EndpointNode> findByIdWithFullCallChain(String endpointId);

    List<EndpointNode> findByControllerClass(String controllerClass);

    List<EndpointNode> findByAppKey(String appKey);

    /**
     * @deprecated This method creates an incorrect MAKES_EXTERNAL_CALL relationship from the target endpoint
     * to the ExternalCall. The correct relationship structure is:
     * Source Endpoint -> MAKES_EXTERNAL_CALL -> ExternalCall -> CALLS_ENDPOINT -> Target Endpoint
     *
     * MAKES_EXTERNAL_CALL relationships are created during graph building (Neo4jGraphBuilder).
     * Do not use this method during resolution as it creates backwards relationships.
     */
    @Deprecated
    @Query("MATCH (e:Endpoint {id: $endpointId}), (ec:ExternalCall {id: $externalCallId}) " +
           "MERGE (e)-[:MAKES_EXTERNAL_CALL]->(ec) RETURN e")
    void createMakesExternalCallRel(String endpointId, String externalCallId);
}
