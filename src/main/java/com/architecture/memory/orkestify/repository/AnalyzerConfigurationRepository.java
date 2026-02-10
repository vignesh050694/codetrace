package com.architecture.memory.orkestify.repository;

import com.architecture.memory.orkestify.model.AnalyzerConfiguration;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for managing analyzer configurations stored in MongoDB.
 */
@Repository
public interface AnalyzerConfigurationRepository extends MongoRepository<AnalyzerConfiguration, String> {

    /**
     * Find active configuration by type
     */
    Optional<AnalyzerConfiguration> findByConfigTypeAndActive(String configType, boolean active);

    /**
     * Find all active configurations
     */
    List<AnalyzerConfiguration> findByActive(boolean active);

    /**
     * Find configurations by type
     */
    List<AnalyzerConfiguration> findByConfigType(String configType);
}
