package com.architecture.memory.orkestify.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Qdrant vector database.
 *
 * NOTE: We're using REST API through QdrantRestClient instead of gRPC EmbeddingStore.
 * Connection details are read from application.yml and injected directly into QdrantRestClient.
 */
@Configuration
@Slf4j
public class QdrantConfig {
    // No beans needed - QdrantRestClient uses @Value injection directly
}
