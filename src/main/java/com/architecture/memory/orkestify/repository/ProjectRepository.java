package com.architecture.memory.orkestify.repository;

import com.architecture.memory.orkestify.model.Project;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends MongoRepository<Project, String> {

    List<Project> findByArchivedFalse();

    List<Project> findByArchivedTrue();

    List<Project> findByNameContainingIgnoreCase(String name);

    // User-specific queries
    List<Project> findByUserId(String userId);

    List<Project> findByUserIdAndArchivedFalse(String userId);

    List<Project> findByUserIdAndArchivedTrue(String userId);

    List<Project> findByUserIdAndNameContainingIgnoreCase(String userId, String name);

    Optional<Project> findByIdAndUserId(String id, String userId);

    boolean existsByIdAndUserId(String id, String userId);

    // Webhook support: find project by GitHub repository URL
    List<Project> findByGithubUrlsContaining(String githubUrl);
}
