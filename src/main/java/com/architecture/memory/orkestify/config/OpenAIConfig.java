package com.architecture.memory.orkestify.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration for OpenAI models (embeddings and chat).
 * Reads API key and model names from application.yml properties.
 */
@Configuration
@Slf4j
public class OpenAIConfig {

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.model.embedding:text-embedding-3-small}")
    private String embeddingModel;

    @Value("${openai.model.chat:gpt-4-turbo}")
    private String chatModel;

    @Value("${openai.timeout:60}")
    private int timeoutSeconds;

    @Value("${openai.max-retries:3}")
    private int maxRetries;

    /**
     * OpenAI embedding model for generating vector embeddings from text.
     * Used to embed code descriptions for semantic search.
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("[OpenAI Config] Initializing EmbeddingModel with model: {}", embeddingModel);

        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(embeddingModel)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(maxRetries)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * OpenAI chat model for generating natural language responses.
     * Used to answer architecture questions based on retrieved context.
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        log.info("[OpenAI Config] Initializing ChatLanguageModel with model: {}", chatModel);

        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(chatModel)
                .temperature(0.2) // Lower temperature for more deterministic answers
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(maxRetries)
                .maxTokens(2000)
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
