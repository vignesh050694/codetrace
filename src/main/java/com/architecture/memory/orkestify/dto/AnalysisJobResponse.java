package com.architecture.memory.orkestify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisJobResponse {
    private String message;
    private int totalRepositories;
    private int applicationsAnalyzed;
    private int successCount;
    private int failureCount;
    private LocalDateTime analyzedAt;
    private String status;
}
