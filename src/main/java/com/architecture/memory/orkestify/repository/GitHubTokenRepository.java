package com.architecture.memory.orkestify.repository;

import com.architecture.memory.orkestify.model.GitHubToken;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GitHubTokenRepository extends MongoRepository<GitHubToken, String> {

    Optional<GitHubToken> findByUserId(String userId);

    void deleteByUserId(String userId);
}
