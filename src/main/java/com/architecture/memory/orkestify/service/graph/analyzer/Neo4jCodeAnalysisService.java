package com.architecture.memory.orkestify.service.graph.analyzer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Orchestrates the new Neo4j-native code analysis pipeline:
 *   1. Clone repository
 *   2. Parse with Neo4jSpoonAnalyzer (2-pass)
 *   3. Build graph with Neo4jGraphBuilder
 *
 * This replaces the old flow of SpoonCodeAnalyzer -> GraphPersistenceService
 * while keeping the old classes intact for backward compatibility.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Neo4jCodeAnalysisService {

    private final Neo4jSpoonAnalyzer neo4jSpoonAnalyzer;
    private final Neo4jGraphBuilder neo4jGraphBuilder;

    private static final String TEMP_CLONE_DIR = System.getProperty("java.io.tmpdir") + "/orkestify-analysis/";

    /**
     * Analyze a repository and persist the graph directly to Neo4j.
     * This is the new pipeline that replaces CodeAnalysisService + GraphPersistenceService.
     *
     * @return list of ParsedApplication objects (one per Spring Boot app found)
     */
    public List<ParsedApplication> analyzeAndPersistGraph(String projectId, String userId,
                                                           String repositoryUrl) {
        log.info("[neo4j-pipeline] Starting analysis for repository: {}", repositoryUrl);

        String repoName = extractRepoName(repositoryUrl);
        Path clonePath = Paths.get(TEMP_CLONE_DIR, repoName);

        try {
            if (Files.exists(clonePath)) {
                cleanupDirectory(clonePath);
            }

            boolean cloneSuccess = cloneRepository(repositoryUrl, clonePath);
            if (!cloneSuccess) {
                throw new RuntimeException("Failed to clone repository: " + repositoryUrl);
            }

            // Parse with new 2-pass analyzer
            List<ParsedApplication> parsedApps = neo4jSpoonAnalyzer.analyze(clonePath);

            // Build and persist Neo4j graph for each application
            for (ParsedApplication parsedApp : parsedApps) {
                neo4jGraphBuilder.buildAndPersist(projectId, userId, repositoryUrl, parsedApp);
            }

            log.info("[neo4j-pipeline] Analysis complete for: {}. Found {} application(s)",
                    repositoryUrl, parsedApps.size());
            return parsedApps;

        } catch (Exception e) {
            log.error("[neo4j-pipeline] Error during analysis: {}", repositoryUrl, e);
            throw new RuntimeException("Neo4j code analysis failed: " + e.getMessage(), e);
        } finally {
            try {
                if (Files.exists(clonePath)) {
                    cleanupDirectory(clonePath);
                }
            } catch (Exception e) {
                log.warn("[neo4j-pipeline] Failed to cleanup: {}", clonePath, e);
            }
        }
    }

    /**
     * Analyze a specific branch (for PR/shadow graph analysis).
     */
    public List<ParsedApplication> analyzeAndPersistGraphBranch(String projectId, String userId,
                                                                  String repositoryUrl, String branchName) {
        log.info("[neo4j-pipeline] Starting analysis for repository: {} branch: {}", repositoryUrl, branchName);

        String repoName = extractRepoName(repositoryUrl);
        Path clonePath = Paths.get(TEMP_CLONE_DIR, repoName);

        try {
            if (Files.exists(clonePath)) {
                cleanupDirectory(clonePath);
            }

            boolean cloneSuccess = cloneRepositoryBranch(repositoryUrl, branchName, clonePath);
            if (!cloneSuccess) {
                throw new RuntimeException("Failed to clone repository branch: " + repositoryUrl + " @ " + branchName);
            }

            List<ParsedApplication> parsedApps = neo4jSpoonAnalyzer.analyze(clonePath);

            for (ParsedApplication parsedApp : parsedApps) {
                neo4jGraphBuilder.buildAndPersist(projectId, userId, repositoryUrl, parsedApp);
            }

            return parsedApps;

        } catch (Exception e) {
            log.error("[neo4j-pipeline] Error during branch analysis: {} @ {}", repositoryUrl, branchName, e);
            throw new RuntimeException("Neo4j code analysis failed: " + e.getMessage(), e);
        } finally {
            try {
                if (Files.exists(clonePath)) {
                    cleanupDirectory(clonePath);
                }
            } catch (Exception e) {
                log.warn("[neo4j-pipeline] Failed to cleanup: {}", clonePath, e);
            }
        }
    }

    /**
     * Delete graph data for a project.
     */
    public void deleteProjectGraph(String projectId) {
        neo4jGraphBuilder.deleteProjectGraph(projectId);
    }

    // ========================= Git Operations =========================

    private boolean cloneRepository(String repositoryUrl, Path targetPath) {
        return executeGitClone("git", "clone", "--depth", "1", repositoryUrl, targetPath.toString());
    }

    private boolean cloneRepositoryBranch(String repositoryUrl, String branchName, Path targetPath) {
        return executeGitClone("git", "clone", "--depth", "1", "--branch", branchName,
                repositoryUrl, targetPath.toString());
    }

    private boolean executeGitClone(String... command) {
        try {
            Path targetPath = Paths.get(command[command.length - 1]);
            Files.createDirectories(targetPath.getParent());

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("[neo4j-pipeline] Clone successful");
                return true;
            } else {
                log.error("[neo4j-pipeline] Git clone failed with exit code: {}", exitCode);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            log.error("[neo4j-pipeline] Error cloning repository", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private String extractRepoName(String repositoryUrl) {
        String[] parts = repositoryUrl.split("/");
        String repoName = parts[parts.length - 1];
        if (repoName.endsWith(".git")) {
            repoName = repoName.substring(0, repoName.length() - 4);
        }
        return repoName + "_neo4j_" + System.currentTimeMillis();
    }

    private void cleanupDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(file -> {
                            if (!file.delete()) {
                                log.warn("[neo4j-pipeline] Failed to delete: {}", file.getAbsolutePath());
                            }
                        });
            }
        }
    }
}
