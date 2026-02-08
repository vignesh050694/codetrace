package com.architecture.memory.orkestify.service.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import com.architecture.memory.orkestify.dto.rag.CodeChunk;
import com.architecture.memory.orkestify.dto.rag.CodeChunkId;
import com.architecture.memory.orkestify.service.rag.QdrantRestClient.EmbeddingPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for generating and storing code embeddings in Qdrant.
 * Loads code graph nodes from Neo4j, generates semantic descriptions,
 * and pushes embeddings to Qdrant for semantic search.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GraphEmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final QdrantRestClient qdrantRestClient;
    private final Driver neo4jDriver;

    // Batch size for processing embeddings (reduced for better reliability)
    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int MAX_RETRIES = 3;

    /**
     * Test Qdrant connection by attempting a simple operation.
     * @return true if connection is successful, false otherwise
     */
    public boolean testQdrantConnection() {
        log.info("[Graph RAG] Testing Qdrant REST API connection...");
        try {
            boolean connected = qdrantRestClient.testConnection();
            if (connected) {
                log.info("[Graph RAG] Qdrant REST API connection test successful");
            } else {
                log.error("[Graph RAG] Qdrant REST API connection test failed");
            }
            return connected;
        } catch (Exception e) {
            log.error("[Graph RAG] Qdrant connection test failed: {}", e.getMessage());
            log.error("[Graph RAG] Make sure Qdrant is running and accessible at the configured host:port");
            return false;
        }
    }

    /**
     * Generate embeddings for all code nodes in a project and store in Qdrant.
     * This includes: Controllers, Services, Repositories, Methods, Endpoints.
     */
    public void generateEmbeddingsForProject(String projectId) {
        log.info("[Graph RAG] Starting embedding generation for project: {}", projectId);

        // Test connection first
        if (!testQdrantConnection()) {
            throw new RuntimeException("Failed to connect to Qdrant REST API. Please check configuration.");
        }

        long startTime = System.currentTimeMillis();

        try {
            // Load code chunks from Neo4j
            List<CodeChunk> chunks = loadCodeChunksFromNeo4j(projectId);
            log.info("[Graph RAG] Loaded {} code chunks from Neo4j", chunks.size());

            if (chunks.isEmpty()) {
                log.warn("[Graph RAG] No code chunks found for project: {}", projectId);
                return;
            }

            // Ensure collection exists (OpenAI text-embedding-3-small is 1536 dimensions)
            qdrantRestClient.ensureCollection(1536);

            // ...existing code...
            int batchSize = DEFAULT_BATCH_SIZE;
            int totalProcessed = 0;
            int failedCount = 0;

            for (int i = 0; i < chunks.size(); i += batchSize) {
                int end = Math.min(i + batchSize, chunks.size());
                List<CodeChunk> batch = chunks.subList(i, end);

                try {
                    processBatch(batch);
                    totalProcessed += batch.size();
                    log.info("[Graph RAG] Processed {}/{} chunks", totalProcessed, chunks.size());
                } catch (Exception e) {
                    failedCount += batch.size();
                    log.error("[Graph RAG] Failed to process batch {}-{}: {}", i, end, e.getMessage());
                    // Continue with next batch
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("[Graph RAG] Embedding generation complete for project: {}. " +
                    "Processed {}/{} chunks successfully in {}ms (failed: {})",
                    projectId, totalProcessed, chunks.size(), duration, failedCount);

            if (failedCount > 0) {
                log.warn("[Graph RAG] {} chunks failed to process for project: {}", failedCount, projectId);
            }

        } catch (Exception e) {
            log.error("[Graph RAG] Error generating embeddings for project: {}", projectId, e);
            throw new RuntimeException("Failed to generate embeddings: " + e.getMessage(), e);
        }
    }

    /**
     * Process a batch of code chunks: generate embeddings and store in Qdrant
     */
    private void processBatch(List<CodeChunk> batch) {
        try {
            // Generate text descriptions for each chunk
            List<String> texts = batch.stream()
                    .map(CodeChunk::getText)
                    .collect(Collectors.toList());

            // Generate embeddings using OpenAI (one by one for MVP simplicity)
            List<Embedding> embeddings = new ArrayList<>();
            for (String text : texts) {
                Embedding embedding = embeddingModel.embed(text).content();
                embeddings.add(embedding);
            }

            // Convert to Qdrant points
            List<EmbeddingPoint> points = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                CodeChunk chunk = batch.get(i);
                Embedding embedding = embeddings.get(i);

                // Convert double[] to List<Float>
                List<Float> vector = new ArrayList<>();
                for (float val : embedding.vector()) {
                    vector.add(val);
                }

                // Build payload (metadata)
                Map<String, Object> payload = new HashMap<>();
                payload.put("projectId", chunk.getProjectId());
                payload.put("nodeId", chunk.getOriginalNodeId());
                payload.put("nodeType", chunk.getNodeType());
                payload.put("level", chunk.getId().getLevel());
                payload.put("chunkId", chunk.getId().toString());
                payload.put("text", chunk.getText());

                if (chunk.getAppKey() != null) payload.put("appKey", chunk.getAppKey());
                if (chunk.getClassName() != null) payload.put("className", chunk.getClassName());
                if (chunk.getMethodName() != null) payload.put("methodName", chunk.getMethodName());
                if (chunk.getPackageName() != null) payload.put("packageName", chunk.getPackageName());
                if (chunk.getSignature() != null) payload.put("signature", chunk.getSignature());

                // Use only UUID (nodeId) as Qdrant point ID - Qdrant only accepts UUIDs or unsigned integers
                String pointId = chunk.getId().getNodeId();

                EmbeddingPoint point = EmbeddingPoint.builder()
                        .id(pointId)
                        .vector(vector)
                        .payload(payload)
                        .build();

                points.add(point);
            }

            // Store in Qdrant with retry logic
            storeInQdrantWithRetry(points, MAX_RETRIES);

            log.debug("[Graph RAG] Stored {} embeddings in Qdrant", batch.size());

        } catch (Exception e) {
            log.error("[Graph RAG] Error processing batch of size {}: {}", batch.size(), e.getMessage(), e);

            // Try processing in smaller chunks if batch fails
            if (batch.size() > 1) {
                log.warn("[Graph RAG] Attempting to process batch in smaller chunks");
                processInSmallerChunks(batch);
            } else {
                throw new RuntimeException("Failed to process single chunk: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Store embeddings in Qdrant with retry logic
     */
    private void storeInQdrantWithRetry(List<EmbeddingPoint> points, int maxRetries) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetries) {
            try {
                qdrantRestClient.addEmbeddings(points);
                return; // Success
            } catch (Exception e) {
                attempt++;
                lastException = e;

                if (attempt < maxRetries) {
                    long waitTime = (long) Math.pow(2, attempt) * 1000; // Exponential backoff
                    log.warn("[Graph RAG] Qdrant store failed (attempt {}/{}), retrying in {}ms: {}",
                             attempt, maxRetries, waitTime, e.getMessage());
                    try {
                        Thread.sleep(waitTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                }
            }
        }

        throw new RuntimeException("Failed to store in Qdrant after " + maxRetries + " attempts", lastException);
    }

    /**
     * Process batch in smaller chunks when large batch fails
     */
    private void processInSmallerChunks(List<CodeChunk> batch) {
        int smallerBatchSize = Math.max(1, batch.size() / 2);
        log.info("[Graph RAG] Processing {} items in smaller batches of {}", batch.size(), smallerBatchSize);

        for (int i = 0; i < batch.size(); i += smallerBatchSize) {
            int end = Math.min(i + smallerBatchSize, batch.size());
            List<CodeChunk> smallBatch = batch.subList(i, end);

            try {
                processBatch(smallBatch);
            } catch (Exception e) {
                log.error("[Graph RAG] Failed to process smaller batch at index {}: {}", i, e.getMessage());
                // Continue with next batch instead of failing completely
            }
        }
    }


    /**
     * Load code chunks from Neo4j graph database.
     * Queries for methods, services, controllers, repositories, and endpoints.
     */
    private List<CodeChunk> loadCodeChunksFromNeo4j(String projectId) {
        List<CodeChunk> chunks = new ArrayList<>();

        try (Session session = neo4jDriver.session()) {
            // Load Method nodes with their relationships
            chunks.addAll(loadMethodChunks(session, projectId));

            // Load Service nodes
            chunks.addAll(loadServiceChunks(session, projectId));

            // Load Controller nodes
            chunks.addAll(loadControllerChunks(session, projectId));

            // Load Repository nodes
            chunks.addAll(loadRepositoryChunks(session, projectId));

            // Load Endpoint nodes
            chunks.addAll(loadEndpointChunks(session, projectId));

        } catch (Exception e) {
            log.error("[Graph RAG] Error loading chunks from Neo4j for project: {}", projectId, e);
            throw new RuntimeException("Failed to load chunks from Neo4j: " + e.getMessage(), e);
        }

        return chunks;
    }

    /**
     * Load method nodes with call chains, Kafka topics, and database accesses
     */
    private List<CodeChunk> loadMethodChunks(Session session, String projectId) {
        String cypher = """
            MATCH (m:Method {projectId: $projectId})
            OPTIONAL MATCH (m)-[:CALLS]->(callee)
            OPTIONAL MATCH (m)-[:PUBLISHES_TO]->(k:KafkaTopic)
            OPTIONAL MATCH (m)-[:ACCESSES]->(d:DatabaseTable)
            OPTIONAL MATCH (m)-[:EXTERNAL_CALL]->(ext:ExternalCall)
            RETURN m,
                   collect(DISTINCT callee) AS callees,
                   collect(DISTINCT k) AS topics,
                   collect(DISTINCT d) AS tables,
                   collect(DISTINCT ext) AS externalCalls
            """;

        Result result = session.run(cypher, Map.of("projectId", projectId));
        List<CodeChunk> chunks = new ArrayList<>();

        while (result.hasNext()) {
            Record record = result.next();
            try {
                CodeChunk chunk = buildMethodChunk(record, projectId);
                chunks.add(chunk);
            } catch (Exception e) {
                log.warn("[Graph RAG] Error building method chunk: {}", e.getMessage());
            }
        }

        log.debug("[Graph RAG] Loaded {} method chunks", chunks.size());
        return chunks;
    }

    /**
     * Build a CodeChunk from a Method node record
     */
    private CodeChunk buildMethodChunk(Record record, String projectId) {
        Value methodNode = record.get("m");
        String nodeId = methodNode.get("id").asString();
        String className = methodNode.get("className").asString("");
        String methodName = methodNode.get("methodName").asString("");
        String signature = methodNode.get("signature").asString("");
        String methodType = methodNode.get("methodType").asString("");
        String appKey = methodNode.get("appKey").asString("");

        // Build description
        String description = buildMethodDescription(record, className, methodName, signature, methodType);

        CodeChunkId chunkId = CodeChunkId.builder()
                .projectId(projectId)
                .nodeId(nodeId)
                .level("METHOD")
                .build();

        return CodeChunk.builder()
                .id(chunkId)
                .text(description)
                .originalNodeId(nodeId)
                .nodeType("METHOD")
                .projectId(projectId)
                .appKey(appKey)
                .className(className)
                .methodName(methodName)
                .signature(signature)
                .build();
    }

    /**
     * Build human-readable description for a method with its relationships
     */
    private String buildMethodDescription(Record record, String className, String methodName,
                                          String signature, String methodType) {
        StringBuilder desc = new StringBuilder();

        desc.append("Method: ").append(className).append(".").append(methodName).append("\n");
        desc.append("Type: ").append(methodType).append("\n");

        if (signature != null && !signature.isBlank()) {
            desc.append("Signature: ").append(signature).append("\n");
        }

        // Add callees
        List<Value> callees = record.get("callees").asList(v -> v);
        if (!callees.isEmpty()) {
            desc.append("Calls: ");
            List<String> calleeNames = callees.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> {
                        String calleeClass = v.get("className").asString("");
                        String calleeMethod = v.get("methodName").asString("");
                        return calleeClass + "." + calleeMethod;
                    })
                    .limit(10)
                    .collect(Collectors.toList());
            desc.append(String.join(", ", calleeNames)).append("\n");
        }

        // Add Kafka topics
        List<Value> topics = record.get("topics").asList(v -> v);
        if (!topics.isEmpty()) {
            desc.append("Kafka Topics: ");
            List<String> topicNames = topics.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("topicName").asString(""))
                    .collect(Collectors.toList());
            desc.append(String.join(", ", topicNames)).append("\n");
        }

        // Add database tables
        List<Value> tables = record.get("tables").asList(v -> v);
        if (!tables.isEmpty()) {
            desc.append("Database Tables: ");
            List<String> tableNames = tables.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("tableName").asString(""))
                    .collect(Collectors.toList());
            desc.append(String.join(", ", tableNames)).append("\n");
        }

        // Add external calls
        List<Value> externalCalls = record.get("externalCalls").asList(v -> v);
        if (!externalCalls.isEmpty()) {
            desc.append("External Calls: ");
            List<String> urls = externalCalls.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> {
                        String url = v.get("url").asString("");
                        String httpMethod = v.get("httpMethod").asString("");
                        return httpMethod + " " + url;
                    })
                    .limit(5)
                    .collect(Collectors.toList());
            desc.append(String.join(", ", urls)).append("\n");
        }

        return desc.toString();
    }

    /**
     * Load Service class nodes
     */
    private List<CodeChunk> loadServiceChunks(Session session, String projectId) {
        String cypher = """
            MATCH (s:Service {projectId: $projectId})
            OPTIONAL MATCH (s)-[:HAS_METHOD]->(m:Method)
            RETURN s, collect(m) AS methods
            """;

        Result result = session.run(cypher, Map.of("projectId", projectId));
        List<CodeChunk> chunks = new ArrayList<>();

        while (result.hasNext()) {
            Record record = result.next();
            try {
                CodeChunk chunk = buildServiceChunk(record, projectId);
                chunks.add(chunk);
            } catch (Exception e) {
                log.warn("[Graph RAG] Error building service chunk: {}", e.getMessage());
            }
        }

        log.debug("[Graph RAG] Loaded {} service chunks", chunks.size());
        return chunks;
    }

    /**
     * Build a CodeChunk from a Service node record
     */
    private CodeChunk buildServiceChunk(Record record, String projectId) {
        Value serviceNode = record.get("s");
        String nodeId = serviceNode.get("id").asString();
        String className = serviceNode.get("className").asString("");
        String packageName = serviceNode.get("packageName").asString("");
        String appKey = serviceNode.get("appKey").asString("");

        List<Value> methods = record.get("methods").asList(v -> v);

        StringBuilder desc = new StringBuilder();
        desc.append("Service: ").append(className).append("\n");
        desc.append("Package: ").append(packageName).append("\n");
        desc.append("Methods: ").append(methods.size()).append("\n");

        if (!methods.isEmpty()) {
            desc.append("Method names: ");
            List<String> methodNames = methods.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("methodName").asString(""))
                    .limit(10)
                    .collect(Collectors.toList());
            desc.append(String.join(", ", methodNames));
        }

        CodeChunkId chunkId = CodeChunkId.builder()
                .projectId(projectId)
                .nodeId(nodeId)
                .level("SERVICE")
                .build();

        return CodeChunk.builder()
                .id(chunkId)
                .text(desc.toString())
                .originalNodeId(nodeId)
                .nodeType("SERVICE")
                .projectId(projectId)
                .appKey(appKey)
                .className(className)
                .packageName(packageName)
                .build();
    }

    /**
     * Load Controller class nodes
     */
    private List<CodeChunk> loadControllerChunks(Session session, String projectId) {
        String cypher = """
            MATCH (c:Controller {projectId: $projectId})
            OPTIONAL MATCH (c)-[:HAS_ENDPOINT]->(e:Endpoint)
            RETURN c, collect(e) AS endpoints
            """;

        Result result = session.run(cypher, Map.of("projectId", projectId));
        List<CodeChunk> chunks = new ArrayList<>();

        while (result.hasNext()) {
            Record record = result.next();
            try {
                CodeChunk chunk = buildControllerChunk(record, projectId);
                chunks.add(chunk);
            } catch (Exception e) {
                log.warn("[Graph RAG] Error building controller chunk: {}", e.getMessage());
            }
        }

        log.debug("[Graph RAG] Loaded {} controller chunks", chunks.size());
        return chunks;
    }

    /**
     * Build a CodeChunk from a Controller node record
     */
    private CodeChunk buildControllerChunk(Record record, String projectId) {
        Value controllerNode = record.get("c");
        String nodeId = controllerNode.get("id").asString();
        String className = controllerNode.get("className").asString("");
        String packageName = controllerNode.get("packageName").asString("");
        String baseUrl = controllerNode.get("baseUrl").asString("");
        String appKey = controllerNode.get("appKey").asString("");

        List<Value> endpoints = record.get("endpoints").asList(v -> v);

        StringBuilder desc = new StringBuilder();
        desc.append("Controller: ").append(className).append("\n");
        desc.append("Package: ").append(packageName).append("\n");
        desc.append("Base URL: ").append(baseUrl).append("\n");
        desc.append("Endpoints: ").append(endpoints.size()).append("\n");

        if (!endpoints.isEmpty()) {
            desc.append("Endpoint paths: ");
            List<String> paths = endpoints.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> {
                        String method = v.get("httpMethod").asString("");
                        String path = v.get("path").asString("");
                        return method + " " + path;
                    })
                    .limit(10)
                    .collect(Collectors.toList());
            desc.append(String.join(", ", paths));
        }

        CodeChunkId chunkId = CodeChunkId.builder()
                .projectId(projectId)
                .nodeId(nodeId)
                .level("CONTROLLER")
                .build();

        return CodeChunk.builder()
                .id(chunkId)
                .text(desc.toString())
                .originalNodeId(nodeId)
                .nodeType("CONTROLLER")
                .projectId(projectId)
                .appKey(appKey)
                .className(className)
                .packageName(packageName)
                .build();
    }

    /**
     * Load Repository class nodes
     */
    private List<CodeChunk> loadRepositoryChunks(Session session, String projectId) {
        String cypher = """
            MATCH (r:RepositoryClass {projectId: $projectId})
            OPTIONAL MATCH (r)-[:HAS_METHOD]->(m:Method)
            OPTIONAL MATCH (r)-[:ACCESSES]->(t:DatabaseTable)
            RETURN r, collect(m) AS methods, collect(t) AS tables
            """;

        Result result = session.run(cypher, Map.of("projectId", projectId));
        List<CodeChunk> chunks = new ArrayList<>();

        while (result.hasNext()) {
            Record record = result.next();
            try {
                CodeChunk chunk = buildRepositoryChunk(record, projectId);
                chunks.add(chunk);
            } catch (Exception e) {
                log.warn("[Graph RAG] Error building repository chunk: {}", e.getMessage());
            }
        }

        log.debug("[Graph RAG] Loaded {} repository chunks", chunks.size());
        return chunks;
    }

    /**
     * Build a CodeChunk from a Repository node record
     */
    private CodeChunk buildRepositoryChunk(Record record, String projectId) {
        Value repoNode = record.get("r");
        String nodeId = repoNode.get("id").asString();
        String className = repoNode.get("className").asString("");
        String packageName = repoNode.get("packageName").asString("");
        String repositoryType = repoNode.get("repositoryType").asString("");
        String appKey = repoNode.get("appKey").asString("");

        List<Value> methods = record.get("methods").asList(v -> v);
        List<Value> tables = record.get("tables").asList(v -> v);

        StringBuilder desc = new StringBuilder();
        desc.append("Repository: ").append(className).append("\n");
        desc.append("Package: ").append(packageName).append("\n");
        desc.append("Type: ").append(repositoryType).append("\n");

        if (!tables.isEmpty()) {
            desc.append("Database Tables: ");
            List<String> tableNames = tables.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("tableName").asString(""))
                    .collect(Collectors.toList());
            desc.append(String.join(", ", tableNames)).append("\n");
        }

        desc.append("Methods: ").append(methods.size());

        if (!methods.isEmpty()) {
            desc.append("\nMethod names: ");
            List<String> methodNames = methods.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("methodName").asString(""))
                    .limit(10)
                    .collect(Collectors.toList());
            desc.append(String.join(", ", methodNames));
        }

        CodeChunkId chunkId = CodeChunkId.builder()
                .projectId(projectId)
                .nodeId(nodeId)
                .level("REPOSITORY")
                .build();

        return CodeChunk.builder()
                .id(chunkId)
                .text(desc.toString())
                .originalNodeId(nodeId)
                .nodeType("REPOSITORY")
                .projectId(projectId)
                .appKey(appKey)
                .className(className)
                .packageName(packageName)
                .build();
    }

    /**
     * Load Endpoint nodes
     */
    private List<CodeChunk> loadEndpointChunks(Session session, String projectId) {
        String cypher = """
            MATCH (e:Endpoint {projectId: $projectId})
            OPTIONAL MATCH (e)-[:CALLS]->(callee)
            OPTIONAL MATCH (e)-[:PUBLISHES_TO]->(k:KafkaTopic)
            OPTIONAL MATCH (e)-[:EXTERNAL_CALL]->(ext:ExternalCall)
            RETURN e,
                   collect(DISTINCT callee) AS callees,
                   collect(DISTINCT k) AS topics,
                   collect(DISTINCT ext) AS externalCalls
            """;

        Result result = session.run(cypher, Map.of("projectId", projectId));
        List<CodeChunk> chunks = new ArrayList<>();

        while (result.hasNext()) {
            Record record = result.next();
            try {
                CodeChunk chunk = buildEndpointChunk(record, projectId);
                chunks.add(chunk);
            } catch (Exception e) {
                log.warn("[Graph RAG] Error building endpoint chunk: {}", e.getMessage());
            }
        }

        log.debug("[Graph RAG] Loaded {} endpoint chunks", chunks.size());
        return chunks;
    }

    /**
     * Build a CodeChunk from an Endpoint node record
     */
    private CodeChunk buildEndpointChunk(Record record, String projectId) {
        Value endpointNode = record.get("e");
        String nodeId = endpointNode.get("id").asString();
        String httpMethod = endpointNode.get("httpMethod").asString("");
        String path = endpointNode.get("path").asString("");
        String controllerClass = endpointNode.get("controllerClass").asString("");
        String handlerMethod = endpointNode.get("handlerMethod").asString("");
        String signature = endpointNode.get("signature").asString("");
        String appKey = endpointNode.get("appKey").asString("");

        List<Value> callees = record.get("callees").asList(v -> v);
        List<Value> topics = record.get("topics").asList(v -> v);
        List<Value> externalCalls = record.get("externalCalls").asList(v -> v);

        StringBuilder desc = new StringBuilder();
        desc.append("Endpoint: ").append(httpMethod).append(" ").append(path).append("\n");
        desc.append("Controller: ").append(controllerClass).append("\n");
        desc.append("Handler: ").append(handlerMethod).append("\n");

        if (signature != null && !signature.isBlank()) {
            desc.append("Signature: ").append(signature).append("\n");
        }

        if (!callees.isEmpty()) {
            desc.append("Calls: ");
            List<String> calleeNames = callees.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> {
                        String calleeClass = v.get("className").asString("");
                        String calleeMethod = v.get("methodName").asString("");
                        return calleeClass + "." + calleeMethod;
                    })
                    .limit(10)
                    .collect(Collectors.toList());
            desc.append(String.join(", ", calleeNames)).append("\n");
        }

        if (!topics.isEmpty()) {
            desc.append("Kafka Topics: ");
            List<String> topicNames = topics.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("topicName").asString(""))
                    .collect(Collectors.toList());
            desc.append(String.join(", ", topicNames)).append("\n");
        }

        if (!externalCalls.isEmpty()) {
            desc.append("External Calls: ");
            List<String> urls = externalCalls.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> {
                        String url = v.get("url").asString("");
                        String method = v.get("httpMethod").asString("");
                        return method + " " + url;
                    })
                    .limit(5)
                    .collect(Collectors.toList());
            desc.append(String.join(", ", urls));
        }

        CodeChunkId chunkId = CodeChunkId.builder()
                .projectId(projectId)
                .nodeId(nodeId)
                .level("ENDPOINT")
                .build();

        return CodeChunk.builder()
                .id(chunkId)
                .text(desc.toString())
                .originalNodeId(nodeId)
                .nodeType("ENDPOINT")
                .projectId(projectId)
                .appKey(appKey)
                .className(controllerClass)
                .methodName(handlerMethod)
                .signature(signature)
                .build();
    }
}
