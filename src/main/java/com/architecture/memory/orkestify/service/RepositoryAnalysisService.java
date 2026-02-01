package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.dto.RepositoryAnalysisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

@Service
@Slf4j
public class RepositoryAnalysisService {

    private static final String TEMP_CLONE_DIR = System.getProperty("java.io.tmpdir") + "/orkestify-analysis/";

    public RepositoryAnalysisResult analyzeRepository(String repositoryUrl) {
        log.info("Starting analysis for repository: {}", repositoryUrl);

        RepositoryAnalysisResult result = RepositoryAnalysisResult.builder()
                .repositoryUrl(repositoryUrl)
                .cloned(false)
                .isJavaRepository(false)
                .build();

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
                result.setErrorMessage("Failed to clone repository");
                return result;
            }

            result.setCloned(true);
            result.setLocalPath(clonePath.toString());

            // Analyze if it's a Java repository
            RepositoryAnalysisResult.JavaProjectInfo javaInfo = analyzeJavaProject(clonePath);
            result.setJavaProjectInfo(javaInfo);
            result.setJavaRepository(javaInfo.isHasJavaFiles() || javaInfo.isHasPomXml() || javaInfo.isHasGradleBuild());

            log.info("Repository analysis completed. Is Java: {}", result.isJavaRepository());

        } catch (Exception e) {
            log.error("Error analyzing repository: {}", repositoryUrl, e);
            result.setErrorMessage(e.getMessage());
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

        return result;
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

    private RepositoryAnalysisResult.JavaProjectInfo analyzeJavaProject(Path repoPath) throws IOException {
        log.info("Analyzing Java project at: {}", repoPath);

        boolean hasPomXml = Files.exists(repoPath.resolve("pom.xml"));
        boolean hasGradleBuild = Files.exists(repoPath.resolve("build.gradle")) ||
                Files.exists(repoPath.resolve("build.gradle.kts"));

        // Count Java files
        int javaFileCount = countJavaFiles(repoPath);
        boolean hasJavaFiles = javaFileCount > 0;

        String buildTool = null;
        if (hasPomXml) {
            buildTool = "Maven";
        } else if (hasGradleBuild) {
            buildTool = "Gradle";
        }

        return RepositoryAnalysisResult.JavaProjectInfo.builder()
                .hasPomXml(hasPomXml)
                .hasGradleBuild(hasGradleBuild)
                .hasJavaFiles(hasJavaFiles)
                .javaFileCount(javaFileCount)
                .buildTool(buildTool)
                .build();
    }

    private int countJavaFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return (int) paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("/.git/"))
                    .count();
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
        return repoName;
    }

    private void cleanupDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    public void cleanupTempDirectory() {
        try {
            Path tempDir = Paths.get(TEMP_CLONE_DIR);
            if (Files.exists(tempDir)) {
                cleanupDirectory(tempDir);
                log.info("Cleaned up temporary directory: {}", TEMP_CLONE_DIR);
            }
        } catch (IOException e) {
            log.error("Failed to cleanup temporary directory: {}", TEMP_CLONE_DIR, e);
        }
    }
}
