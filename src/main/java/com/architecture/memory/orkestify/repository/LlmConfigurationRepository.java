package com.architecture.memory.orkestify.repository;

import com.architecture.memory.orkestify.model.LlmConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LlmConfigurationRepository extends JpaRepository<LlmConfiguration, String> {

    /**
     * Find LLM configuration by feature name
     */
    Optional<LlmConfiguration> findByFeatureAndActiveTrue(String feature);

    /**
     * Find by provider
     */
    Optional<LlmConfiguration> findByProvider(String provider);
}
