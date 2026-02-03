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

    Optional<ServiceNode> findByProjectIdAndClassName(String projectId, String className);

    @Query("MATCH (s:Service {className: $className, packageName: $packageName, projectId: $projectId}) " +
           "RETURN s")
    Optional<ServiceNode> findByFullyQualifiedName(String projectId, String packageName, String className);

    @Query("MATCH (s:Service {projectId: $projectId})-[:HAS_METHOD]->(m:Method) " +
           "RETURN s, collect(m) as methods")
    List<ServiceNode> findByProjectIdWithMethods(String projectId);

    @Query("MATCH (s:Service {projectId: $projectId})-[:HAS_METHOD]->(m:Method)-[:CALLS*1..3]->(target:Method) " +
           "RETURN s.className as serviceName, collect(DISTINCT target.className) as dependencies")
    List<Object[]> findServiceDependencies(String projectId);
}
