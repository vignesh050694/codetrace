package com.architecture.memory.orkestify.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Configuration for LLM providers (OpenAI, Anthropic, OpenRouter, etc.)
 * Allows different models for different features (PR analysis, code review, etc.)
 */
@Entity
@Table(name = "llm_configuration")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /**
     * Feature this LLM is used for (e.g., "PR_ANALYSIS", "CODE_REVIEW")
     */
    @Column(nullable = false, unique = true)
    private String feature;

    /**
     * LLM provider (e.g., "OPENAI", "ANTHROPIC", "OPENROUTER")
     */
    @Column(nullable = false)
    private String provider;

    /**
     * Base URL for the API (e.g., "https://api.openai.com/v1", "https://api.anthropic.com/v1")
     */
    @Column(nullable = false, length = 500)
    private String baseUrl;

    /**
     * API key for authentication
     */
    @Column(nullable = false, length = 500)
    private String apiKey;

    /**
     * Model to use (e.g., "gpt-4", "claude-3-5-sonnet-20241022", "gpt-4-turbo")
     */
    @Column(nullable = false)
    private String model;

    /**
     * Maximum tokens for the response
     */
    @Column
    private Integer maxTokens;

    /**
     * Temperature for generation (0.0 - 1.0)
     */
    @Column
    private Double temperature;

    /**
     * Whether this configuration is active
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
