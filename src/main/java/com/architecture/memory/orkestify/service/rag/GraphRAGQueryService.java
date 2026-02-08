package com.architecture.memory.orkestify.service.rag;

import com.architecture.memory.orkestify.dto.rag.GraphRagAnswer;
import com.architecture.memory.orkestify.service.rag.QdrantRestClient.SearchResult;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;


/**
 * Service for performing Graph RAG queries.
 * Combines semantic search over Qdrant embeddings with graph expansion in Neo4j
 * to answer natural language questions about code architecture.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GraphRAGQueryService {

    private final EmbeddingModel embeddingModel;
    private final ChatLanguageModel chatLanguageModel;
    private final QdrantRestClient qdrantRestClient;
    private final Driver neo4jDriver;

    private static final String SYSTEM_PROMPT = """
You are a code architecture assistant analyzing a Spring Boot application using a graph-based code model.

The application has already been statically analyzed and represented as an architectural graph
(controllers, endpoints, services, repositories, methods, Kafka topics, database tables, and their relationships).
You do NOT have access to raw source code and must NOT rely on general framework knowledge.

You must reason ONLY from the provided context blocks, which are derived directly from this graph.
The graph is the single source of truth.

YOUR PRIMARY TASK:
Answer questions about the system’s architecture by explaining how existing components,
relationships, and data flows are affected or involved — strictly based on the given context.

STRICT RULES (MANDATORY):
- Do NOT invent or assume the existence of any class, method, endpoint, framework, configuration, or dependency.
- Do NOT introduce common Spring concepts (e.g., Spring Security, JWT, filters, annotations) unless they are explicitly present in the context.
- Do NOT explain how something is typically implemented in Spring Boot.
- Do NOT give design advice, best practices, or implementation steps.
- If information is not present in the context, treat it as unknown.

IMPACT-FOCUSED QUESTIONS:
When a question asks about “impact”, “effect”, or “what would change”:

- Describe which existing graph components would be involved or become affected
  IF the requested capability were introduced.

- You MAY reason hypothetically about impact on existing components,
  as long as you:
  - Reference ONLY components present in the context
  - Do NOT invent new components, frameworks, or mechanisms
  - Do NOT describe implementation details

- Focus on impact surfaces:
  - Which controllers would require additional checks
  - Which services would become authorization-sensitive
  - Which repositories or data models are involved

- Clearly distinguish:
  - What is visible in the graph
  - What is currently missing from the graph

- Do NOT propose new architectural elements.
- Do NOT explain how the feature would be implemented.

HOW TO ANSWER:
- Always reference concrete elements from the context (class names, method names, endpoints, tables, topics).
- Describe relationships explicitly (e.g., “Endpoint X calls Service Y, which writes to Table Z”).
- Focus on architecture-level reasoning: boundaries, dependencies, cross-cutting concerns, and data flow.
- Use clear structure with bullet points or numbered lists.
- Prefer factual statements over speculative language.

PARTIAL OR INSUFFICIENT CONTEXT:
- If only part of a flow is visible, describe only what is observable.
- Explicitly state limits, for example:
  “The graph shows X calling Y, but no authorization or persistence logic beyond this point is visible.”
- If the question cannot be answered from the graph, respond exactly with:
  “I don’t see enough information in the provided graph context to answer this question.”

CONTEXT FORMAT:
- Each context block represents a single graph node (Controller, Endpoint, Service, Method, Repository, etc.).
- Blocks may include:
  - Outgoing calls
  - Database tables accessed
  - Kafka topics published or consumed
  - External API calls
  - Related nodes within a limited traversal depth
- Treat the context as authoritative for what it shows, and incomplete beyond that.
""";

    /**
     * Answer a natural language question about the codebase using Graph RAG.
     *
     * @param projectId Project identifier
     * @param question Natural language question
     * @param maxResults Maximum number of Qdrant results to retrieve
     * @param minScore Minimum similarity score threshold (0.0 to 1.0)
     * @return Answer with referenced nodes
     */
    public GraphRagAnswer answerQuestion(String projectId, String question, int maxResults, double minScore) {
        log.info("[Graph RAG] Processing question for project {}: {}", projectId, question);
        log.info("[Graph RAG] Parameters - maxResults: {}, minScore: {}", maxResults, minScore);
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: Embed the question
            Embedding questionEmbedding = embeddingModel.embed(question).content();

            // Convert embedding to List<Float> for REST API
            List<Float> queryVector = new ArrayList<>();
            for (float val : questionEmbedding.vector()) {
                queryVector.add(val);
            }

            // Step 2: Search Qdrant via REST API for relevant code chunks
            Map<String, Object> filter = Map.of(
                "must", List.of(
                    Map.of(
                        "key", "projectId",
                        "match", Map.of("value", projectId)
                    )
                )
            );

            List<SearchResult> searchResults = qdrantRestClient.search(queryVector, maxResults, filter);

            log.info("[Graph RAG] Found {} relevant code chunks from Qdrant", searchResults.size());

            // Log score distribution for debugging
            if (!searchResults.isEmpty()) {
                double maxScore = searchResults.stream().mapToDouble(SearchResult::getScore).max().orElse(0.0);
                double minScoreFound = searchResults.stream().mapToDouble(SearchResult::getScore).min().orElse(0.0);
                double avgScore = searchResults.stream().mapToDouble(SearchResult::getScore).average().orElse(0.0);
                log.info("[Graph RAG] Score distribution - Max: {}, Min: {}, Avg: {}",
                        maxScore, minScoreFound, avgScore);

                // Log all scores for complete visibility
                log.info("[Graph RAG] All result scores:");
                for (int i = 0; i < Math.min(searchResults.size(), 20); i++) {
                    SearchResult r = searchResults.get(i);
                    Map<String, Object> p = r.getPayload();
                    log.info("[Graph RAG]   [{}] score={}, type={}, class={}",
                            i, r.getScore(), p.get("nodeType"), p.get("className"));
                }
            }

            if (searchResults.isEmpty()) {
                return GraphRagAnswer.builder()
                        .answer("I couldn't find any relevant information in the codebase to answer your question. " +
                                "Please try rephrasing your question or ensure the project has been analyzed and embeddings generated.")
                        .nodeIds(List.of())
                        .matchedChunks(List.of())
                        .resultCount(0)
                        .processingTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // Step 3: Build matched chunks with graph context
            List<GraphRagAnswer.MatchedChunk> matchedChunks = new ArrayList<>();
            Set<String> nodeIds = new HashSet<>();
            StringBuilder contextBuilder = new StringBuilder();


            log.debug("[Graph RAG] Processing {} search results (minScore: {})", searchResults.size(), minScore);

            for (int i = 0; i < searchResults.size(); i++) {
                SearchResult result = searchResults.get(i);
                Map<String, Object> payload = result.getPayload();

                log.debug("[Graph RAG] Result {}: score={}, nodeType={}, className={}",
                         i, result.getScore(), payload.get("nodeType"), payload.get("className"));

                // Filter by minimum score
                if (result.getScore() < minScore) {
                    log.debug("[Graph RAG] Skipping result {} due to low score: {}", i, result.getScore());
                    continue;
                }

                String nodeId = (String) payload.get("nodeId");
                String nodeType = (String) payload.get("nodeType");
                String className = (String) payload.get("className");
                String methodName = (String) payload.get("methodName");
                String signature = (String) payload.get("signature");

                nodeIds.add(nodeId);

                // Get expanded graph context from Neo4j with fallback lookups
                String graphContext = expandGraphContext(nodeId, nodeType, projectId, className, methodName, signature);

                // Build matched chunk info
                GraphRagAnswer.MatchedChunk matchedChunk = GraphRagAnswer.MatchedChunk.builder()
                        .nodeId(nodeId)
                        .nodeType(nodeType)
                        .className(className)
                        .methodName(methodName)
                        .signature(signature)
                        .score(result.getScore())
                        .context(graphContext)
                        .build();
                matchedChunks.add(matchedChunk);

                // Append to overall context for LLM
                contextBuilder.append("\n\n--- Context Block ").append(i + 1).append(" ---\n");
                contextBuilder.append(graphContext);
            }

            // Check if we have any results after filtering
            if (matchedChunks.isEmpty()) {
                return GraphRagAnswer.builder()
                        .answer("I found some results but none were relevant enough (similarity score too low). " +
                                "Please try rephrasing your question.")
                        .nodeIds(List.of())
                        .matchedChunks(List.of())
                        .resultCount(0)
                        .processingTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // Step 4: Build prompt and query LLM
            String llmAnswer = queryLLM(question, contextBuilder.toString());

            long duration = System.currentTimeMillis() - startTime;

            log.info("[Graph RAG] Question answered in {}ms with {} context blocks", duration, matchedChunks.size());

            return GraphRagAnswer.builder()
                    .answer(llmAnswer)
                    .nodeIds(new ArrayList<>(nodeIds))
                    .matchedChunks(matchedChunks)
                    .resultCount(matchedChunks.size())
                    .processingTimeMs(duration)
                    .build();

        } catch (Exception e) {
            log.error("[Graph RAG] Error processing question for project {}: {}", projectId, question, e);
            throw new RuntimeException("Failed to process RAG query: " + e.getMessage(), e);
        }
    }

    /**
     * Expand graph context for a node by querying Neo4j for its neighborhood.
     * Falls back to class/method lookup when nodeId lookup fails (stale embeddings).
     */
    private String expandGraphContext(String nodeId, String nodeType, String projectId,
                                      String className, String methodName, String signature) {
        try (Session session = neo4jDriver.session()) {
            String cypher = buildContextQuery(nodeType);

            Result result = session.run(cypher, Map.of("nodeId", nodeId, "projectId", projectId));

            if (result.hasNext()) {
                Record record = result.next();
                return buildContextFromRecord(record, nodeType);
            }

            // Fallbacks for stale embeddings or missing nodeId
            String fallback = fallbackContextLookup(session, nodeType, projectId, className, methodName, signature);
            if (fallback != null) {
                return fallback;
            }

            return "Node not found in graph.";
        } catch (Exception e) {
            log.error("[Graph RAG] Error expanding graph context for node {}: {}", nodeId, e.getMessage());
            return "Error retrieving graph context.";
        }
    }

    private String fallbackContextLookup(Session session, String nodeType, String projectId,
                                         String className, String methodName, String signature) {
        if (className == null || className.isBlank()) {
            return null;
        }

        String cypher;
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);

        switch (nodeType) {
            case "SERVICE" -> {
                cypher = "MATCH (s:Service {projectId: $projectId, className: $className}) RETURN s LIMIT 1";
                params.put("className", className);
                Result result = session.run(cypher, params);
                if (result.hasNext()) {
                    return buildServiceContext(result.next());
                }
            }
            case "REPOSITORY" -> {
                cypher = "MATCH (r:RepositoryClass {projectId: $projectId, className: $className}) RETURN r LIMIT 1";
                params.put("className", className);
                Result result = session.run(cypher, params);
                if (result.hasNext()) {
                    return buildRepositoryContext(result.next());
                }
            }
            case "CONTROLLER" -> {
                cypher = "MATCH (c:Controller {projectId: $projectId, className: $className}) RETURN c LIMIT 1";
                params.put("className", className);
                Result result = session.run(cypher, params);
                if (result.hasNext()) {
                    return buildControllerContext(result.next());
                }
            }
            case "METHOD" -> {
                if (signature != null && !signature.isBlank()) {
                    cypher = "MATCH (m:Method {projectId: $projectId, signature: $signature}) RETURN m LIMIT 1";
                    params.put("signature", signature);
                    Result result = session.run(cypher, params);
                    if (result.hasNext()) {
                        return buildMethodContext(result.next());
                    }
                }
                if (methodName != null && !methodName.isBlank()) {
                    cypher = "MATCH (m:Method {projectId: $projectId, className: $className, methodName: $methodName}) RETURN m LIMIT 1";
                    params.put("className", className);
                    params.put("methodName", methodName);
                    Result result = session.run(cypher, params);
                    if (result.hasNext()) {
                        return buildMethodContext(result.next());
                    }
                }
            }
            default -> {
                // No fallback available
            }
        }

        return null;
    }

    /**
     * Build Cypher query based on node type
     */
    private String buildContextQuery(String nodeType) {
        return switch (nodeType) {
            case "METHOD" -> """
                MATCH (m:Method {id: $nodeId, projectId: $projectId})
                OPTIONAL MATCH (m)-[:CALLS]->(callee)
                OPTIONAL MATCH (m)-[:PUBLISHES_TO]->(k:KafkaTopic)
                OPTIONAL MATCH (m)-[:ACCESSES]->(d:DatabaseTable)
                OPTIONAL MATCH (m)-[:EXTERNAL_CALL]->(ext:ExternalCall)
                OPTIONAL MATCH (parent)-[:HAS_METHOD]->(m)
                RETURN m, parent,
                       collect(DISTINCT callee) AS callees,
                       collect(DISTINCT k) AS topics,
                       collect(DISTINCT d) AS tables,
                       collect(DISTINCT ext) AS externalCalls
                LIMIT 1
                """;
            case "ENDPOINT" -> """
                MATCH (e:Endpoint {id: $nodeId, projectId: $projectId})
                OPTIONAL MATCH (e)-[:CALLS]->(callee)
                OPTIONAL MATCH (e)-[:PUBLISHES_TO]->(k:KafkaTopic)
                OPTIONAL MATCH (e)-[:EXTERNAL_CALL]->(ext:ExternalCall)
                OPTIONAL MATCH (c:Controller)-[:HAS_ENDPOINT]->(e)
                RETURN e, c,
                       collect(DISTINCT callee) AS callees,
                       collect(DISTINCT k) AS topics,
                       collect(DISTINCT ext) AS externalCalls
                LIMIT 1
                """;
            case "SERVICE" -> """
                MATCH (s:Service {id: $nodeId, projectId: $projectId})
                OPTIONAL MATCH (s)-[:HAS_METHOD]->(m:Method)
                OPTIONAL MATCH (m)-[:CALLS]->(dep)
                OPTIONAL MATCH (caller)-[:CALLS]->(m)
                RETURN s,
                       collect(DISTINCT m) AS methods,
                       collect(DISTINCT dep) AS dependencies,
                       collect(DISTINCT caller) AS callers
                LIMIT 1
                """;
            case "CONTROLLER" -> """
                MATCH (c:Controller {id: $nodeId, projectId: $projectId})
                OPTIONAL MATCH (c)-[:HAS_ENDPOINT]->(e:Endpoint)
                OPTIONAL MATCH (e)-[:CALLS]->(s)
                RETURN c,
                       collect(DISTINCT e) AS endpoints,
                       collect(DISTINCT s) AS serviceCalls
                LIMIT 1
                """;
            case "REPOSITORY" -> """
                MATCH (r:RepositoryClass {id: $nodeId, projectId: $projectId})
                OPTIONAL MATCH (r)-[:HAS_METHOD]->(m:Method)
                OPTIONAL MATCH (r)-[:ACCESSES]->(t:DatabaseTable)
                OPTIONAL MATCH (caller)-[:CALLS]->(m)
                RETURN r,
                       collect(DISTINCT m) AS methods,
                       collect(DISTINCT t) AS tables,
                       collect(DISTINCT caller) AS callers
                LIMIT 1
                """;
            default -> """
                MATCH (n {id: $nodeId, projectId: $projectId})
                RETURN n
                LIMIT 1
                """;
        };
    }

    /**
     * Build human-readable context from Neo4j record
     */
    private String buildContextFromRecord(Record record, String nodeType) {

        return switch (nodeType) {
            case "METHOD" -> buildMethodContext(record);
            case "ENDPOINT" -> buildEndpointContext(record);
            case "SERVICE" -> buildServiceContext(record);
            case "CONTROLLER" -> buildControllerContext(record);
            case "REPOSITORY" -> buildRepositoryContext(record);
            default -> "Unknown node type: " + nodeType;
        };
    }

    private String buildMethodContext(Record record) {
        StringBuilder ctx = new StringBuilder();
        Value m = record.get("m");
        Value parent = record.get("parent");

        ctx.append("Method: ").append(m.get("className").asString("")).append(".")
           .append(m.get("methodName").asString("")).append("\n");
        ctx.append("Type: ").append(m.get("methodType").asString("")).append("\n");

        String sig = m.get("signature").asString("");
        if (!sig.isBlank()) {
            ctx.append("Signature: ").append(sig).append("\n");
        }

        if (!parent.isNull()) {
            String parentLabel = parent.asNode().labels().iterator().next();
            ctx.append("Belongs to: ").append(parentLabel).append(" - ")
               .append(parent.get("className").asString("")).append("\n");
        }

        appendCalls(ctx, record.get("callees"));
        appendTopics(ctx, record.get("topics"));
        appendTables(ctx, record.get("tables"));
        appendExternalCalls(ctx, record.get("externalCalls"));

        return ctx.toString();
    }

    private String buildEndpointContext(Record record) {
        StringBuilder ctx = new StringBuilder();
        Value e = record.get("e");
        Value c = record.get("c");

        ctx.append("Endpoint: ").append(e.get("httpMethod").asString("")).append(" ")
           .append(e.get("path").asString("")).append("\n");
        ctx.append("Handler: ").append(e.get("controllerClass").asString("")).append(".")
           .append(e.get("handlerMethod").asString("")).append("\n");

        if (!c.isNull()) {
            ctx.append("Controller: ").append(c.get("className").asString("")).append("\n");
            ctx.append("Base URL: ").append(c.get("baseUrl").asString("")).append("\n");
        }

        appendCalls(ctx, record.get("callees"));
        appendTopics(ctx, record.get("topics"));
        appendExternalCalls(ctx, record.get("externalCalls"));

        return ctx.toString();
    }

    private String buildServiceContext(Record record) {
        StringBuilder ctx = new StringBuilder();
        Value s = record.get("s");

        ctx.append("Service: ").append(s.get("className").asString("")).append("\n");
        ctx.append("Package: ").append(s.get("packageName").asString("")).append("\n");

        List<Value> methods = record.get("methods").asList(v -> v);
        ctx.append("Methods (").append(methods.size()).append("): ");
        ctx.append(methods.stream()
                .filter(v -> !v.isNull())
                .map(v -> v.get("methodName").asString(""))
                .limit(10)
                .collect(Collectors.joining(", "))).append("\n");

        List<Value> deps = record.get("dependencies").asList(v -> v);
        if (!deps.isEmpty()) {
            ctx.append("Dependencies: ");
            ctx.append(deps.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("className").asString("") + "." + v.get("methodName").asString(""))
                    .limit(10)
                    .collect(Collectors.joining(", "))).append("\n");
        }

        List<Value> callers = record.get("callers").asList(v -> v);
        if (!callers.isEmpty()) {
            ctx.append("Called by: ");
            ctx.append(callers.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("className").asString("") + "." + v.get("methodName").asString(""))
                    .limit(10)
                    .collect(Collectors.joining(", "))).append("\n");
        }

        return ctx.toString();
    }

    private String buildControllerContext(Record record) {
        StringBuilder ctx = new StringBuilder();
        Value c = record.get("c");

        ctx.append("Controller: ").append(c.get("className").asString("")).append("\n");
        ctx.append("Package: ").append(c.get("packageName").asString("")).append("\n");
        ctx.append("Base URL: ").append(c.get("baseUrl").asString("")).append("\n");

        List<Value> endpoints = record.get("endpoints").asList(v -> v);
        ctx.append("Endpoints (").append(endpoints.size()).append("):\n");
        endpoints.stream()
                .filter(v -> !v.isNull())
                .limit(15)
                .forEach(e -> {
                    String method = e.get("httpMethod").asString("");
                    String path = e.get("path").asString("");
                    ctx.append("  - ").append(method).append(" ").append(path).append("\n");
                });

        List<Value> serviceCalls = record.get("serviceCalls").asList(v -> v);
        if (!serviceCalls.isEmpty()) {
            ctx.append("Service calls: ");
            ctx.append(serviceCalls.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("className").asString(""))
                    .distinct()
                    .limit(10)
                    .collect(Collectors.joining(", "))).append("\n");
        }

        return ctx.toString();
    }

    private String buildRepositoryContext(Record record) {
        StringBuilder ctx = new StringBuilder();
        Value r = record.get("r");

        ctx.append("Repository: ").append(r.get("className").asString("")).append("\n");
        ctx.append("Package: ").append(r.get("packageName").asString("")).append("\n");
        ctx.append("Type: ").append(r.get("repositoryType").asString("")).append("\n");

        List<Value> tables = record.get("tables").asList(v -> v);
        if (!tables.isEmpty()) {
            ctx.append("Database Tables: ");
            ctx.append(tables.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("tableName").asString(""))
                    .collect(Collectors.joining(", "))).append("\n");
        }

        List<Value> methods = record.get("methods").asList(v -> v);
        ctx.append("Methods (").append(methods.size()).append("): ");
        ctx.append(methods.stream()
                .filter(v -> !v.isNull())
                .map(v -> v.get("methodName").asString(""))
                .limit(10)
                .collect(Collectors.joining(", "))).append("\n");

        List<Value> callers = record.get("callers").asList(v -> v);
        if (!callers.isEmpty()) {
            ctx.append("Called by: ");
            ctx.append(callers.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("className").asString("") + "." + v.get("methodName").asString(""))
                    .limit(10)
                    .collect(Collectors.joining(", "))).append("\n");
        }

        return ctx.toString();
    }

    private void appendCalls(StringBuilder ctx, Value callees) {
        List<Value> calls = callees.asList(v -> v);
        if (!calls.isEmpty()) {
            ctx.append("Calls: ");
            ctx.append(calls.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("className").asString("") + "." + v.get("methodName").asString(""))
                    .limit(10)
                    .collect(Collectors.joining(", "))).append("\n");
        }
    }

    private void appendTopics(StringBuilder ctx, Value topics) {
        List<Value> topicList = topics.asList(v -> v);
        if (!topicList.isEmpty()) {
            ctx.append("Kafka Topics: ");
            ctx.append(topicList.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("topicName").asString(""))
                    .collect(Collectors.joining(", "))).append("\n");
        }
    }

    private void appendTables(StringBuilder ctx, Value tables) {
        List<Value> tableList = tables.asList(v -> v);
        if (!tableList.isEmpty()) {
            ctx.append("Database Tables: ");
            ctx.append(tableList.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("tableName").asString(""))
                    .collect(Collectors.joining(", "))).append("\n");
        }
    }

    private void appendExternalCalls(StringBuilder ctx, Value externalCalls) {
        List<Value> extList = externalCalls.asList(v -> v);
        if (!extList.isEmpty()) {
            ctx.append("External API Calls: ");
            ctx.append(extList.stream()
                    .filter(v -> !v.isNull())
                    .map(v -> v.get("httpMethod").asString("") + " " + v.get("url").asString(""))
                    .limit(5)
                    .collect(Collectors.joining(", "))).append("\n");
        }
    }

    /**
     * Query the LLM with system prompt, user question, and retrieved context
     */
    private String queryLLM(String question, String context) {
        try {
            SystemMessage systemMessage = SystemMessage.from(SYSTEM_PROMPT);

            String userPrompt = String.format("""
                    Question: %s
                    
                    Retrieved Context:
                    %s
                    
                    Please answer the question based on the above context.
                    """, question, context);

            UserMessage userMessage = UserMessage.from(userPrompt);

            Response<AiMessage> response = chatLanguageModel.generate(systemMessage, userMessage);

            return response.content().text();

        } catch (Exception e) {
            log.error("[Graph RAG] Error querying LLM: {}", e.getMessage(), e);
            return "I encountered an error while generating the answer. Please try again.";
        }
    }
}
