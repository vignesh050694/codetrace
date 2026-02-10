package com.architecture.memory.orkestify.service.llm;

import com.architecture.memory.orkestify.model.LlmConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Service to interact with different LLM providers (OpenAI, Anthropic, OpenRouter)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Generate text using the configured LLM
     */
    public String generate(LlmConfiguration config, String systemPrompt, String userPrompt) {
        log.info("Generating response using {} - model: {}", config.getProvider(), config.getModel());

        try {
            return switch (config.getProvider().toUpperCase()) {
                case "OPENAI", "OPENROUTER" -> generateOpenAI(config, systemPrompt, userPrompt);
                case "ANTHROPIC", "CLAUDE" -> generateAnthropic(config, systemPrompt, userPrompt);
                default -> throw new IllegalArgumentException("Unsupported provider: " + config.getProvider());
            };
        } catch (Exception e) {
            log.error("Error generating LLM response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate LLM response", e);
        }
    }

    /**
     * Generate using OpenAI-compatible API (OpenAI, OpenRouter)
     */
    private String generateOpenAI(LlmConfiguration config, String systemPrompt, String userPrompt) throws Exception {
        String endpoint = config.getBaseUrl() + "/chat/completions";

        // Build request body
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", config.getModel());

        if (config.getMaxTokens() != null) {
            requestBody.put("max_tokens", config.getMaxTokens());
        }

        if (config.getTemperature() != null) {
            requestBody.put("temperature", config.getTemperature());
        }

        // Messages array
        ArrayNode messages = requestBody.putArray("messages");

        // System message
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);

        // User message
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);

        // Headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());

        HttpEntity<String> request = new HttpEntity<>(
                objectMapper.writeValueAsString(requestBody),
                headers
        );

        // Make request
        ResponseEntity<String> response = restTemplate.exchange(
                endpoint,
                HttpMethod.POST,
                request,
                String.class
        );

        // Parse response
        JsonNode responseJson = objectMapper.readTree(response.getBody());
        return responseJson
                .path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText();
    }

    /**
     * Generate using Anthropic Claude API
     */
    private String generateAnthropic(LlmConfiguration config, String systemPrompt, String userPrompt) throws Exception {
        String endpoint = config.getBaseUrl() + "/messages";

        // Build request body
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", config.getModel());
        requestBody.put("system", systemPrompt);

        if (config.getMaxTokens() != null) {
            requestBody.put("max_tokens", config.getMaxTokens());
        } else {
            requestBody.put("max_tokens", 4096); // Default for Anthropic
        }

        if (config.getTemperature() != null) {
            requestBody.put("temperature", config.getTemperature());
        }

        // Messages array
        ArrayNode messages = requestBody.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);

        // Headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", config.getApiKey());
        headers.set("anthropic-version", "2023-06-01");

        HttpEntity<String> request = new HttpEntity<>(
                objectMapper.writeValueAsString(requestBody),
                headers
        );

        // Make request
        ResponseEntity<String> response = restTemplate.exchange(
                endpoint,
                HttpMethod.POST,
                request,
                String.class
        );

        // Parse response
        JsonNode responseJson = objectMapper.readTree(response.getBody());
        return responseJson
                .path("content")
                .path(0)
                .path("text")
                .asText();
    }
}
