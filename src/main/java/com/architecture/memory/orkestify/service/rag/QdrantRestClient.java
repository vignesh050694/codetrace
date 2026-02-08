package com.architecture.memory.orkestify.service.rag;

import com.architecture.memory.orkestify.dto.rag.CodeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST-based Qdrant client for MVP.
 * Uses HTTP REST API (port 6333) instead of gRPC (port 6334).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QdrantRestClient {

    @Value("${qdrant.host}")
    private String host;

    @Value("${qdrant.rest-port:6333}")
    private int restPort;

    @Value("${qdrant.api-key:}")
    private String apiKey;

    @Value("${qdrant.collection:code-embeddings}")
    private String collectionName;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Test connection to Qdrant REST API
     */
    public boolean testConnection() {
        try {
            String url = String.format("http://%s:%d/collections", host, restPort);
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.error("[Qdrant REST] Connection test failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Create collection if it doesn't exist
     */
    public void ensureCollection(int vectorSize) {
        try {
            String url = String.format("http://%s:%d/collections/%s", host, restPort, collectionName);

            // Check if collection exists
            try {
                restTemplate.getForEntity(url, String.class);
                log.info("[Qdrant REST] Collection '{}' already exists", collectionName);
                return;
            } catch (Exception e) {
                // Collection doesn't exist, create it
                log.info("[Qdrant REST] Creating collection '{}'", collectionName);
            }

            // Create collection
            Map<String, Object> createRequest = Map.of(
                "vectors", Map.of(
                    "size", vectorSize,
                    "distance", "Cosine"
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isBlank()) {
                headers.set("api-key", apiKey);
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(createRequest, headers);
            restTemplate.exchange(
                String.format("http://%s:%d/collections/%s", host, restPort, collectionName),
                HttpMethod.PUT,
                entity,
                String.class
            );

            log.info("[Qdrant REST] Collection '{}' created successfully", collectionName);
        } catch (Exception e) {
            log.error("[Qdrant REST] Failed to ensure collection: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create Qdrant collection: " + e.getMessage());
        }
    }

    /**
     * Add embeddings to Qdrant via REST API
     */
    public void addEmbeddings(List<EmbeddingPoint> points) {
        try {
            String url = String.format("http://%s:%d/collections/%s/points", host, restPort, collectionName);

            Map<String, Object> upsertRequest = Map.of("points", points);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isBlank()) {
                headers.set("api-key", apiKey);
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(upsertRequest, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.PUT,
                entity,
                String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("[Qdrant REST] Successfully added {} embeddings", points.size());
            } else {
                log.warn("[Qdrant REST] Unexpected response: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[Qdrant REST] Failed to add embeddings: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to add embeddings: " + e.getMessage(), e);
        }
    }

    /**
     * Search for similar embeddings
     */
    public List<SearchResult> search(List<Float> queryVector, int limit, Map<String, Object> filter) {
        try {
            String url = String.format("http://%s:%d/collections/%s/points/search", host, restPort, collectionName);

            Map<String, Object> searchRequest = new HashMap<>();
            searchRequest.put("vector", queryVector);
            searchRequest.put("limit", limit);
            searchRequest.put("with_payload", true);

            if (filter != null && !filter.isEmpty()) {
                searchRequest.put("filter", filter);
            }

            log.debug("[Qdrant REST] Searching collection: {}, limit: {}, filter: {}", collectionName, limit, filter);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isBlank()) {
                headers.set("api-key", apiKey);
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(searchRequest, headers);

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

            if (response.getBody() != null && response.getBody().containsKey("result")) {
                List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("result");
                log.debug("[Qdrant REST] Search returned {} results", results.size());

                // Log first few results for debugging
                if (!results.isEmpty() && log.isDebugEnabled()) {
                    for (int i = 0; i < Math.min(3, results.size()); i++) {
                        Map<String, Object> r = results.get(i);
                        log.debug("[Qdrant REST] Result {}: score={}, id={}", i, r.get("score"), r.get("id"));
                    }
                }

                return results.stream()
                    .map(this::mapToSearchResult)
                    .collect(Collectors.toList());
            }

            log.warn("[Qdrant REST] Search response body is empty or missing 'result' key");
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("[Qdrant REST] Search failed: {}", e.getMessage(), e);
            throw new RuntimeException("Search failed: " + e.getMessage(), e);
        }
    }

    private SearchResult mapToSearchResult(Map<String, Object> result) {
        String id = result.get("id").toString();
        double score = ((Number) result.get("score")).doubleValue();
        Map<String, Object> payload = (Map<String, Object>) result.get("payload");

        return SearchResult.builder()
            .id(id)
            .score(score)
            .payload(payload)
            .build();
    }

    /**
     * Embedding point for Qdrant
     */
    @lombok.Data
    @lombok.Builder
    public static class EmbeddingPoint {
        private String id;
        private List<Float> vector;
        private Map<String, Object> payload;
    }

    /**
     * Search result from Qdrant
     */
    @lombok.Data
    @lombok.Builder
    public static class SearchResult {
        private String id;
        private double score;
        private Map<String, Object> payload;
    }
}
