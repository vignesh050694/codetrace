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
public class ProjectAnalysisResponse {

    private String projectId;
    private String projectName;
    private int totalRepositories;
    private int successfullyCloned;
    private int javaRepositories;
    private int nonJavaRepositories;
    private List<RepositoryAnalysisResult> repositories;
    private LocalDateTime analyzedAt;
    private String status;
}
