package com.architecture.memory.orkestify.repository.graph;

import com.architecture.memory.orkestify.model.graph.nodes.ServiceNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceNodeRepository extends Neo4jRepository<ServiceNode, String> {

    List<ServiceNode> findByProjectId(String projectId);

    List<ServiceNode> findByProjectIdAndAppKey(String projectId, String appKey);

    @Query("MATCH (s:Service {projectId: $projectId, className: $className}) " +
           "RETURN s LIMIT 1")
    Optional<ServiceNode> findByProjectIdAndClassName(String projectId, String className);

    @Query("MATCH (s:Service {className: $className, packageName: $packageName, projectId: $projectId}) " +
           "RETURN s")
    Optional<ServiceNode> findByFullyQualifiedName(String projectId, String packageName, String className);

    @Query("MATCH (s:Service {projectId: $projectId})-[:HAS_METHOD]->(m:Method) " +
           "RETURN DISTINCT s")
    List<ServiceNode> findByProjectIdWithMethods(String projectId);

    @Query("MATCH (s:Service {projectId: $projectId})-[:HAS_METHOD]->(m:Method)-[:CALLS*1..3]->(target:Method) " +
           "RETURN s.className as serviceName, collect(DISTINCT target.className) as dependencies")
    List<Object[]> findServiceDependencies(String projectId);

    @Query("MATCH (s:Service {id: $serviceId}) " +
           "OPTIONAL MATCH (s)-[:HAS_METHOD]->(m:Method) " +
           "OPTIONAL MATCH (m)-[:CALLS]->(calledMethod:Method) " +
           "OPTIONAL MATCH (m)-[:PRODUCES_TO]->(t:KafkaTopic) " +
           "OPTIONAL MATCH (m)-[:MAKES_EXTERNAL_CALL]->(ec:ExternalCall) " +
           "RETURN s LIMIT 1")
    Optional<ServiceNode> findByIdWithFullDetails(String serviceId);

    @Query("MATCH (caller)-[:CALLS|HAS_METHOD*1..2]->(m:Method)<-[:HAS_METHOD]-(s:Service {id: $serviceId}) " +
           "WHERE caller:Controller OR caller:Service OR caller:KafkaListener " +
           "RETURN DISTINCT labels(caller)[0] as callerType, caller.className as callerClass, " +
           "m.methodName as callerMethod, m.lineStart as lineNumber")
    List<Object[]> findCallersOfService(String serviceId);

    List<ServiceNode> findByAppKey(String appKey);
}
