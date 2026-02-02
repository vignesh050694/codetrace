package com.architecture.memory.orkestify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeAnalysisResponse {
    private String projectId;
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
}
