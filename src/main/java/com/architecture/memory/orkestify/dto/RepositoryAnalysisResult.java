package com.architecture.memory.orkestify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepositoryAnalysisResult {

    private String repositoryUrl;
    private boolean cloned;
    private boolean isJavaRepository;
    private String errorMessage;
    private String localPath;
    private JavaProjectInfo javaProjectInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JavaProjectInfo {
        private boolean hasPomXml;
        private boolean hasGradleBuild;
        private boolean hasJavaFiles;
        private int javaFileCount;
        private String buildTool;
    }
}
