package com.architecture.memory.orkestify.repository;

import com.architecture.memory.orkestify.model.ShadowGraph;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShadowGraphRepository extends MongoRepository<ShadowGraph, String> {

    List<ShadowGraph> findByProjectIdOrderByCreatedAtDesc(String projectId);

    Optional<ShadowGraph> findByProjectIdAndShadowId(String projectId, String shadowId);

    List<ShadowGraph> findByStatus(String status);

    List<ShadowGraph> findByExpiresAtBefore(LocalDateTime dateTime);

    void deleteByProjectIdAndShadowId(String projectId, String shadowId);

    void deleteByProjectId(String projectId);

    long countByProjectIdAndStatus(String projectId, String status);

    // Webhook support: find most recent shadow graph for a PR
    Optional<ShadowGraph> findTopByProjectIdAndPrNumberOrderByCreatedAtDesc(String projectId, String prNumber);
}
