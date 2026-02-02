package com.architecture.memory.orkestify.model;

import com.architecture.memory.orkestify.dto.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "code_analysis_results")
public class CodeAnalysisResult {

    @Id
    private String id;

    @Indexed
    private String projectId;

    @Indexed
    private String userId;

    @Indexed
    private String appKey; // repoUrl + app main class (or NON_SPRING)

    private String repoUrl;

    private LocalDateTime analyzedAt;

    private ApplicationInfo applicationInfo;

    private List<ControllerInfo> controllers;

    private List<KafkaListenerInfo> kafkaListeners; // Kafka entry points

    private List<ServiceInfo> services;

    private List<RepositoryInfo> repositories;

    private List<ConfigurationInfo> configurations;

    private int totalClasses;

    private int totalMethods;

    private String status;

    private LocalDateTime createdAt;
}
