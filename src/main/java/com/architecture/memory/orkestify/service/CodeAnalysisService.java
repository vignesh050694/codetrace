package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.dto.CodeAnalysisResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class CodeAnalysisService {

    private final SpoonCodeAnalyzer spoonCodeAnalyzer;
    private static final String TEMP_CLONE_DIR = System.getProperty("java.io.tmpdir") + "/orkestify-analysis/";

    public List<CodeAnalysisResponse> analyzeRepository(String projectId, String repositoryUrl) {
        log.info("Starting code analysis for repository: {}", repositoryUrl);

        String repoName = extractRepoName(repositoryUrl);
        Path clonePath = Paths.get(TEMP_CLONE_DIR, repoName);

        try {
            // Clean up if directory already exists
            if (Files.exists(clonePath)) {
                log.info("Cleaning up existing directory: {}", clonePath);
                cleanupDirectory(clonePath);
            }

            // Clone the repository
            log.info("Cloning repository to: {}", clonePath);
            boolean cloneSuccess = cloneRepository(repositoryUrl, clonePath);

            if (!cloneSuccess) {
                throw new RuntimeException("Failed to clone repository: " + repositoryUrl);
            }

            // Analyze the code using Spoon (returns list for monorepo support)
            log.info("Analyzing code structure with Spoon...");
            List<CodeAnalysisResponse> analyses = spoonCodeAnalyzer.analyzeCode(clonePath, projectId, repositoryUrl);

            log.info("Code analysis completed successfully for: {}. Found {} application(s)",
                    repositoryUrl, analyses.size());
            return analyses;

        } catch (Exception e) {
            log.error("Error during code analysis: {}", repositoryUrl, e);
            throw new RuntimeException("Code analysis failed: " + e.getMessage(), e);
        } finally {
            // Clean up cloned directory
            try {
                if (Files.exists(clonePath)) {
                    cleanupDirectory(clonePath);
                    log.info("Cleaned up cloned directory: {}", clonePath);
                }
            } catch (Exception e) {
                log.warn("Failed to cleanup directory: {}", clonePath, e);
            }
        }
    }

    private boolean cloneRepository(String repositoryUrl, Path targetPath) {
        try {
            // Create parent directory
            Files.createDirectories(targetPath.getParent());

            // Build git clone command
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "git", "clone", "--depth", "1", repositoryUrl, targetPath.toString()
            );
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("Successfully cloned repository: {}", repositoryUrl);
                return true;
            } else {
                log.error("Git clone failed with exit code: {}", exitCode);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            log.error("Error cloning repository: {}", repositoryUrl, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private String extractRepoName(String repositoryUrl) {
        // Extract repository name from URL
        // Example: https://github.com/user/repo.git -> repo
        String[] parts = repositoryUrl.split("/");
        String repoName = parts[parts.length - 1];
        if (repoName.endsWith(".git")) {
            repoName = repoName.substring(0, repoName.length() - 4);
        }
        return repoName + "_" + System.currentTimeMillis();
    }

    private void cleanupDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(file -> {
                            if (!file.delete()) {
                                log.warn("Failed to delete file: {}", file.getAbsolutePath());
                            }
                        });
            }
        }
    }
}
