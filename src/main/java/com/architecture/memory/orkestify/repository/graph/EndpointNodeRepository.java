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
}
