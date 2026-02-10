package com.architecture.memory.orkestify.repository.graph;

import com.architecture.memory.orkestify.model.graph.nodes.ExternalCallNode;
import com.architecture.memory.orkestify.model.graph.nodes.EndpointNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExternalCallNodeRepository extends Neo4jRepository<ExternalCallNode, String> {
    List<ExternalCallNode> findByProjectId(String projectId);

    @Query("MATCH (m:Method {projectId: $projectId})-[:MAKES_EXTERNAL_CALL]->(ec:ExternalCall) " +
           "WHERE m.id = $methodId RETURN ec")
    List<ExternalCallNode> findByMethodId(String projectId, String methodId);

    @Query("MATCH (e:Endpoint {projectId: $projectId})-[:MAKES_EXTERNAL_CALL]->(ec:ExternalCall) " +
           "WHERE e.id = $endpointId RETURN ec")
    List<ExternalCallNode> findByEndpointId(String projectId, String endpointId);

    /**
     * Create CALLS_ENDPOINT relationship from ExternalCall to target Endpoint.
     * This links a resolved external call to its target endpoint without re-persisting the endpoint.
     */
    @Query("MATCH (ec:ExternalCall {id: $externalCallId}), (e:Endpoint {id: $targetEndpointId}) " +
           "MERGE (ec)-[:CALLS_ENDPOINT]->(e) RETURN ec")
    void createCallsEndpointRelationship(String externalCallId, String targetEndpointId);

    /**
     * Find the target endpoint that this external call points to via CALLS_ENDPOINT relationship.
     */
    @Query("MATCH (ec:ExternalCall {id: $externalCallId})-[:CALLS_ENDPOINT]->(e:Endpoint) " +
           "RETURN e")
    Optional<EndpointNode> findTargetEndpoint(String externalCallId);

    /**
     * Find the target endpoint for an external call by querying CALLS_ENDPOINT relationship
     * within a specific project.
     */
    @Query("MATCH (ec:ExternalCall {id: $externalCallId})-[:CALLS_ENDPOINT]->(e:Endpoint {projectId: $projectId}) " +
           "RETURN e")
    Optional<EndpointNode> findTargetEndpointInProject(String externalCallId, String projectId);
}
