package com.architecture.memory.orkestify.repository.graph;

import com.architecture.memory.orkestify.model.graph.nodes.RepositoryClassNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepositoryClassNodeRepository extends Neo4jRepository<RepositoryClassNode, String> {

    List<RepositoryClassNode> findByProjectId(String projectId);

    List<RepositoryClassNode> findByProjectIdAndAppKey(String projectId, String appKey);

    Optional<RepositoryClassNode> findByProjectIdAndClassName(String projectId, String className);

    List<RepositoryClassNode> findByProjectIdAndRepositoryType(String projectId, String repositoryType);

    @Query("MATCH (r:RepositoryClass {className: $className, packageName: $packageName, projectId: $projectId}) " +
           "RETURN r")
    Optional<RepositoryClassNode> findByFullyQualifiedName(String projectId, String packageName, String className);

    @Query("MATCH (r:RepositoryClass {projectId: $projectId})-[:ACCESSES]->(t:DatabaseTable) " +
           "RETURN r, collect(t) as tables")
    List<RepositoryClassNode> findByProjectIdWithDatabaseTables(String projectId);

    @Query("MATCH (r:RepositoryClass {projectId: $projectId})-[:HAS_METHOD]->(m:Method) " +
           "RETURN r, collect(m) as methods")
    List<RepositoryClassNode> findByProjectIdWithMethods(String projectId);
}
