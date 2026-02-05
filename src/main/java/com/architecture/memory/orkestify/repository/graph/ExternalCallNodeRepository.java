package com.architecture.memory.orkestify.repository.graph;

import com.architecture.memory.orkestify.model.graph.nodes.ExternalCallNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExternalCallNodeRepository extends Neo4jRepository<ExternalCallNode, String> {
    List<ExternalCallNode> findByProjectId(String projectId);
}
