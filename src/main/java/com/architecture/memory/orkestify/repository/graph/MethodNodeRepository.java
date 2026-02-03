package com.architecture.memory.orkestify.repository.graph;

import com.architecture.memory.orkestify.model.graph.nodes.MethodNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MethodNodeRepository extends Neo4jRepository<MethodNode, String> {

    List<MethodNode> findByProjectId(String projectId);

    List<MethodNode> findByProjectIdAndClassName(String projectId, String className);

    Optional<MethodNode> findByProjectIdAndSignature(String projectId, String signature);

    @Query("MATCH (m:Method {className: $className, methodName: $methodName, projectId: $projectId}) " +
           "RETURN m")
    Optional<MethodNode> findByClassAndMethodName(String projectId, String className, String methodName);

    @Query("MATCH (caller)-[:CALLS*]->(m:Method {signature: $signature, projectId: $projectId}) " +
           "RETURN DISTINCT caller")
    List<MethodNode> findCallersOf(String projectId, String signature);

    @Query("MATCH (m:Method {signature: $signature, projectId: $projectId})-[:CALLS*]->(callee) " +
           "RETURN DISTINCT callee")
    List<MethodNode> findCalleesOf(String projectId, String signature);

    @Query("MATCH path = (e:Endpoint {projectId: $projectId})-[:CALLS*1..10]->(m:Method {signature: $signature}) " +
           "RETURN path")
    List<Object> findCallPathsToMethod(String projectId, String signature);

    @Query("MATCH (m:Method {projectId: $projectId})-[:MAKES_EXTERNAL_CALL]->(ec:ExternalCall) " +
           "RETURN m, collect(ec) as externalCalls")
    List<MethodNode> findMethodsWithExternalCalls(String projectId);
}
