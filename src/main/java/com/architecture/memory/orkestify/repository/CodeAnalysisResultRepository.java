package com.architecture.memory.orkestify.repository;

import com.architecture.memory.orkestify.model.CodeAnalysisResult;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeAnalysisResultRepository extends MongoRepository<CodeAnalysisResult, String> {

    List<CodeAnalysisResult> findByProjectId(String projectId);

    List<CodeAnalysisResult> findByProjectIdAndUserId(String projectId, String userId);

    List<CodeAnalysisResult> findByUserId(String userId);

    Optional<CodeAnalysisResult> findByIdAndUserId(String id, String userId);

    List<CodeAnalysisResult> findByProjectIdAndRepoUrl(String projectId, String repoUrl);

    Optional<CodeAnalysisResult> findByProjectIdAndUserIdAndRepoUrlAndAppKey(
            String projectId, String userId, String repoUrl, String appKey);

    void deleteByProjectId(String projectId);

    void deleteByProjectIdAndUserId(String projectId, String userId);
}
