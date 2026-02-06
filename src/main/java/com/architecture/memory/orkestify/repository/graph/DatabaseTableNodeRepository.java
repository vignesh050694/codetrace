package com.architecture.memory.orkestify.repository.graph;

import com.architecture.memory.orkestify.model.graph.nodes.DatabaseTableNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DatabaseTableNodeRepository extends Neo4jRepository<DatabaseTableNode, String> {

    List<DatabaseTableNode> findByProjectId(String projectId);

    @Query("MATCH (r:RepositoryClass {projectId: $projectId, className: $className})-[:ACCESSES]->(t:DatabaseTable) " +
           "RETURN t")
    List<DatabaseTableNode> findByRepositoryClassName(String projectId, String className);
}
