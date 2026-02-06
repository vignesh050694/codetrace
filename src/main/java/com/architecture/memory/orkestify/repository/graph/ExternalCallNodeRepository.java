package com.architecture.memory.orkestify.repository.graph;

import com.architecture.memory.orkestify.model.graph.nodes.ExternalCallNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExternalCallNodeRepository extends Neo4jRepository<ExternalCallNode, String> {
    List<ExternalCallNode> findByProjectId(String projectId);

    @Query("MATCH (m:Method {projectId: $projectId})-[:MAKES_EXTERNAL_CALL]->(ec:ExternalCall) " +
           "WHERE m.id = $methodId RETURN ec")
    List<ExternalCallNode> findByMethodId(String projectId, String methodId);

    @Query("MATCH (e:Endpoint {projectId: $projectId})-[:MAKES_EXTERNAL_CALL]->(ec:ExternalCall) " +
           "WHERE e.id = $endpointId RETURN ec")
    List<ExternalCallNode> findByEndpointId(String projectId, String endpointId);
}
