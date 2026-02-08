package com.architecture.memory.orkestify.controller;

import com.architecture.memory.orkestify.dto.rag.GraphRagAnswer;
import com.architecture.memory.orkestify.dto.rag.GraphRagQueryRequest;
import com.architecture.memory.orkestify.service.rag.GraphEmbeddingService;
import com.architecture.memory.orkestify.service.rag.GraphRAGQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for Graph RAG operations.
 * Provides endpoints for:
 * - Natural language queries over the code architecture
 * - Embedding generation and management
 */
@RestController
@RequestMapping("/api/projects/{projectId}/graph/rag")
@RequiredArgsConstructor
@Slf4j
public class GraphRagController {

    private final GraphRAGQueryService graphRAGQueryService;
    private final GraphEmbeddingService graphEmbeddingService;

    /**
     * Answer a natural language question about the codebase architecture.
     * Uses semantic search over Qdrant embeddings + Neo4j graph expansion.
     *
     * POST /api/projects/{projectId}/graph/rag/query
     *
     * Request body:
     * {
     *   "question": "Which services handle user authentication?",
     *   "maxResults": 10,
     *   "includeGraphContext": true,
     *   "minScore": 0.7
     * }
     *
     * @param projectId Project identifier
     * @param request Query request with question and parameters
     * @return Answer with referenced nodes and similarity scores
     */
    @PostMapping("/query")
    public ResponseEntity<GraphRagAnswer> queryCodebase(
            @PathVariable String projectId,
            @RequestBody GraphRagQueryRequest request) {

        log.info("[Graph RAG Controller] Query for project {}: {}", projectId, request.getQuestion());

        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            GraphRagAnswer answer = graphRAGQueryService.answerQuestion(
                    projectId,
                    request.getQuestion(),
                    request.getMaxResults(),
                    request.getMinScore()
            );

            return ResponseEntity.ok(answer);

        } catch (Exception e) {
            log.error("[Graph RAG Controller] Error processing query: {}", e.getMessage(), e);

            GraphRagAnswer errorAnswer = GraphRagAnswer.builder()
                    .answer("An error occurred while processing your question: " + e.getMessage())
                    .nodeIds(java.util.List.of())
                    .matchedChunks(java.util.List.of())
                    .resultCount(0)
                    .processingTimeMs(0)
                    .build();

            return ResponseEntity.status(500).body(errorAnswer);
        }
    }

    /**
     * Rebuild embeddings for a project.
     * Loads all code nodes from Neo4j, generates embeddings, and stores in Qdrant.
     *
     * POST /api/projects/{projectId}/graph/rag/embeddings/rebuild
     *
     * @param projectId Project identifier
     * @return Success message with processing details
     */
    @PostMapping("/embeddings/rebuild")
    public ResponseEntity<Map<String, Object>> rebuildEmbeddings(@PathVariable String projectId) {
        log.info("[Graph RAG Controller] Rebuild embeddings for project: {}", projectId);

        try {
            long startTime = System.currentTimeMillis();

            graphEmbeddingService.generateEmbeddingsForProject(projectId);

            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> response = Map.of(
                    "status", "success",
                    "message", "Embeddings generated successfully",
                    "projectId", projectId,
                    "durationMs", duration
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Graph RAG Controller] Error rebuilding embeddings: {}", e.getMessage(), e);

            Map<String, Object> errorResponse = Map.of(
                    "status", "error",
                    "message", "Failed to rebuild embeddings: " + e.getMessage(),
                    "projectId", projectId
            );

            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * Check embedding generation status for a project.
     * (Placeholder - can be extended to track async embedding jobs)
     *
     * GET /api/projects/{projectId}/graph/rag/embeddings/status
     *
     * @param projectId Project identifier
     * @return Status information
     */
    @GetMapping("/embeddings/status")
    public ResponseEntity<Map<String, Object>> getEmbeddingStatus(@PathVariable String projectId) {
        log.info("[Graph RAG Controller] Get embedding status for project: {}", projectId);

        // TODO: Implement actual status tracking (e.g., using Redis or MongoDB)
        // For now, return a simple response

        Map<String, Object> response = Map.of(
                "projectId", projectId,
                "status", "ready",
                "message", "Embeddings are ready for queries. Use /rebuild to regenerate."
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Test Qdrant connection health.
     *
     * GET /api/projects/{projectId}/graph/rag/test-connection
     *
     * @param projectId Project identifier (not used, but kept for consistency)
     * @return Connection test result
     */
    @GetMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testQdrantConnection(@PathVariable String projectId) {
        log.info("[Graph RAG Controller] Testing Qdrant connection");

        try {
            boolean isConnected = graphEmbeddingService.testQdrantConnection();

            Map<String, Object> response = Map.of(
                    "connected", isConnected,
                    "status", isConnected ? "success" : "failed",
                    "message", isConnected
                        ? "Qdrant REST API connection is working correctly"
                        : "Failed to connect to Qdrant. Check logs for details.",
                    "hint", "Using REST API on port 6333 (HTTP)"
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[Graph RAG Controller] Error testing connection: {}", e.getMessage(), e);

            Map<String, Object> errorResponse = Map.of(
                    "connected", false,
                    "status", "error",
                    "message", "Error testing Qdrant connection: " + e.getMessage(),
                    "hint", "Check application.yml: qdrant.rest-port should be 6333 (HTTP REST API)"
            );

            return ResponseEntity.status(500).body(errorResponse);
        }
    }
}
