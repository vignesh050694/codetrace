package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.dto.*;
import com.architecture.memory.orkestify.exception.ProjectNotFoundException;
import com.architecture.memory.orkestify.exception.UserNotFoundException;
import com.architecture.memory.orkestify.model.CodeAnalysisResult;
import com.architecture.memory.orkestify.model.Project;
import com.architecture.memory.orkestify.model.User;
import com.architecture.memory.orkestify.repository.CodeAnalysisResultRepository;
import com.architecture.memory.orkestify.repository.ProjectRepository;
import com.architecture.memory.orkestify.repository.UserRepository;
import com.architecture.memory.orkestify.service.graph.GraphPersistenceService;
import com.architecture.memory.orkestify.service.graph.GraphResolutionService;
import com.architecture.memory.orkestify.service.graph.analyzer.Neo4jCodeAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final RepositoryAnalysisService repositoryAnalysisService;
    private final CodeAnalysisService codeAnalysisService;
    private final CodeAnalysisResultRepository codeAnalysisResultRepository;
    private final AsyncExternalCallResolverService asyncExternalCallResolverService;
    private final KafkaProducerConsumerResolver kafkaProducerConsumerResolver;
    private final GraphPersistenceService graphPersistenceService;
    private final GraphResolutionService graphResolutionService;
    private final Neo4jCodeAnalysisService neo4jCodeAnalysisService;

    public ProjectResponse createProject(CreateProjectRequest request, String username) {
        log.info("Creating new project with name: {} for user: {}", request.getName(), username);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        Project project = Project.builder()
                .name(request.getName())
                .userId(user.getId())
                .archived(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Project savedProject = projectRepository.save(project);
        log.info("Project created successfully with id: {} for user: {}", savedProject.getId(), username);

        return mapToResponse(savedProject);
    }

    public ProjectResponse updateProject(String id, UpdateProjectRequest request, String username) {
        log.info("Updating project with id: {} for user: {}", id, username);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        Project project = projectRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ProjectNotFoundException("Project not found with id: " + id));

        project.setName(request.getName());
        project.setGithubUrls(request.getGithubUrls());
        project.setUpdatedAt(LocalDateTime.now());

        Project updatedProject = projectRepository.save(project);
        log.info("Project updated successfully with id: {}", updatedProject.getId());

        return mapToResponse(updatedProject);
    }

    public ProjectResponse getProjectById(String id, String username) {
        log.info("Fetching project with id: {} for user: {}", id, username);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        Project project = projectRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ProjectNotFoundException("Project not found with id: " + id));

        return mapToResponse(project);
    }

    public List<ProjectResponse> getAllProjects(String username) {
        log.info("Fetching all projects for user: {}", username);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        return projectRepository.findByUserId(user.getId()).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ProjectResponse> getActiveProjects(String username) {
        log.info("Fetching active projects for user: {}", username);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        return projectRepository.findByUserIdAndArchivedFalse(user.getId()).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ProjectResponse> getArchivedProjects(String username) {
        log.info("Fetching archived projects for user: {}", username);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        return projectRepository.findByUserIdAndArchivedTrue(user.getId()).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public ProjectResponse archiveProject(String id, String username) {
        log.info("Archiving project with id: {} for user: {}", id, username);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        Project project = projectRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ProjectNotFoundException("Project not found with id: " + id));

        project.setArchived(true);
        project.setArchivedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());

        Project archivedProject = projectRepository.save(project);
        log.info("Project archived successfully with id: {}", archivedProject.getId());

        return mapToResponse(archivedProject);
    }

    public ProjectResponse unarchiveProject(String id, String username) {
        log.info("Unarchiving project with id: {} for user: {}", id, username);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        Project project = projectRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ProjectNotFoundException("Project not found with id: " + id));

        project.setArchived(false);
        project.setArchivedAt(null);
        project.setUpdatedAt(LocalDateTime.now());

        Project unarchivedProject = projectRepository.save(project);
        log.info("Project unarchived successfully with id: {}", unarchivedProject.getId());

        return mapToResponse(unarchivedProject);
    }

    public void deleteProject(String id, String username) {
        log.info("Deleting project with id: {} for user: {}", id, username);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (!projectRepository.existsByIdAndUserId(id, user.getId())) {
            throw new ProjectNotFoundException("Project not found with id: " + id);
        }

        projectRepository.deleteById(id);
        log.info("Project deleted successfully with id: {}", id);
    }

    public List<ProjectResponse> searchProjects(String name, String username) {
        log.info("Searching projects with name containing: {} for user: {}", name, username);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        return projectRepository.findByUserIdAndNameContainingIgnoreCase(user.getId(), name).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private ProjectResponse mapToResponse(Project project) {
        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .githubUrls(project.getGithubUrls())
                .archived(project.isArchived())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .archivedAt(project.getArchivedAt())
                .build();
    }

    public AnalysisJobResponse analyzeProjectCode(String projectId, String username) {
        log.info("Starting deep code analysis for all repositories in project: {}", projectId);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        Project project = projectRepository.findByIdAndUserId(projectId, user.getId())
                .orElseThrow(() -> new ProjectNotFoundException("Project not found with id: " + projectId));

        if (project.getGithubUrls() == null || project.getGithubUrls().isEmpty()) {
            throw new RuntimeException("Project has no GitHub repositories configured");
        }

        int totalRepositories = project.getGithubUrls().size();
        int applicationsAnalyzed = 0;
        int successCount = 0;
        int failureCount = 0;

        // Analyze each repository in the project
        for (String repositoryUrl : project.getGithubUrls()) {
            try {
                log.info("Analyzing repository: {}", repositoryUrl);
                List<CodeAnalysisResponse> repoAnalyses = codeAnalysisService.analyzeRepository(projectId, repositoryUrl);

                // Handle monorepo - each Spring Boot app gets a separate entry
                for (CodeAnalysisResponse analysis : repoAnalyses) {
                    applicationsAnalyzed++;

                    // Save each application as separate MongoDB document
                    CodeAnalysisResult result = CodeAnalysisResult.builder()
                            .projectId(projectId)
                            .userId(user.getId())
                            .repoUrl(repositoryUrl)
                            .appKey(buildAppKey(repositoryUrl, analysis.getApplicationInfo()))
                            .analyzedAt(analysis.getAnalyzedAt())
                            .applicationInfo(analysis.getApplicationInfo())
                            .controllers(analysis.getControllers())
                            .kafkaListeners(analysis.getKafkaListeners())
                            .services(analysis.getServices())
                            .repositories(analysis.getRepositories())
                            .configurations(analysis.getConfigurations())
                            .totalClasses(analysis.getTotalClasses())
                            .totalMethods(analysis.getTotalMethods())
                            .status(analysis.getStatus())
                            .createdAt(LocalDateTime.now())
                            .build();
                    upsertAnalysisResult(result);

                    // Neo4j graph persistence is now handled by the new pipeline below
                    // (after all MongoDB saves are complete for this repository)

                    successCount++;

                    if (analysis.getApplicationInfo().isSpringBootApplication()) {
                        log.info("Saved analysis result for Spring Boot app: {} from repository: {}",
                                analysis.getApplicationInfo().getMainClassName(), repositoryUrl);
                    } else {
                        log.info("Saved analysis result for non-Spring Boot project from repository: {}", repositoryUrl);
                    }
                }

            } catch (Exception e) {
                log.error("Failed to analyze repository: {}", repositoryUrl, e);
                failureCount++;

                ApplicationInfo emptyAppInfo = ApplicationInfo.builder()
                        .isSpringBootApplication(false)
                        .build();

                // Save error result to MongoDB
                CodeAnalysisResult errorResult = CodeAnalysisResult.builder()
                        .projectId(projectId)
                        .userId(user.getId())
                        .repoUrl(repositoryUrl)
                        .appKey(buildAppKey(repositoryUrl, emptyAppInfo))
                        .analyzedAt(LocalDateTime.now())
                        .status("FAILED: " + e.getMessage())
                        .applicationInfo(emptyAppInfo)
                        .controllers(new ArrayList<>())
                        .kafkaListeners(new ArrayList<>())
                        .services(new ArrayList<>())
                        .repositories(new ArrayList<>())
                        .configurations(new ArrayList<>())
                        .totalClasses(0)
                        .totalMethods(0)
                        .createdAt(LocalDateTime.now())
                        .build();
                upsertAnalysisResult(errorResult);
            }
        }

        String status = failureCount == 0 ? "SUCCESS" :
                       successCount == 0 ? "FAILED" : "PARTIAL_SUCCESS";

        String message = String.format("Analysis completed. %d application(s) analyzed successfully from %d repository(ies).",
                                      successCount, totalRepositories);

        log.info("Deep code analysis completed for project: {}. Total repos: {}, Apps analyzed: {}, Success: {}, Failed: {}",
                projectId, totalRepositories, applicationsAnalyzed, successCount, failureCount);

        // Persist to Neo4j graph using the new 2-pass analyzer pipeline
        // This replaces the old graphPersistenceService.persistAnalysis() call
        for (String repositoryUrl : project.getGithubUrls()) {
            try {
                log.info("Persisting Neo4j graph for repository: {} (new pipeline)", repositoryUrl);
                neo4jCodeAnalysisService.analyzeAndPersistGraph(projectId, user.getId(), repositoryUrl);
                log.info("Neo4j graph persisted successfully for: {}", repositoryUrl);
            } catch (Exception graphEx) {
                log.error("Failed to persist Neo4j graph (new pipeline) for {}: {}", repositoryUrl, graphEx.getMessage(), graphEx);
                // Non-fatal: MongoDB data is already saved
            }
        }

        // Trigger async resolution of external calls
        log.info("Triggering async external call resolution for project: {}", projectId);
        asyncExternalCallResolverService.resolveProjectExternalCalls(projectId, user.getId());

        // Trigger Kafka producer-consumer resolution
        log.info("Triggering Kafka producer-consumer resolution for user: {}", user.getId());
        kafkaProducerConsumerResolver.resolveKafkaProducers(user.getId());

        // Resolve cross-microservice external calls in Neo4j graph
        try {
            graphResolutionService.resolveExternalCalls(projectId);
            graphResolutionService.resolveKafkaConnections(projectId);
            log.info("Completed graph resolution for project: {}", projectId);
        } catch (Exception resolveEx) {
            log.error("Failed to resolve graph relationships: {}", resolveEx.getMessage(), resolveEx);
        }

        return AnalysisJobResponse.builder()
                .message(message)
                .totalRepositories(totalRepositories)
                .applicationsAnalyzed(applicationsAnalyzed)
                .successCount(successCount)
                .failureCount(failureCount)
                .analyzedAt(LocalDateTime.now())
                .status(status)
                .build();
    }

    public List<CodeAnalysisResult> getAnalysisResults(String projectId, String username) {
        log.info("Fetching analysis results for project: {}", projectId);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        Project project = projectRepository.findByIdAndUserId(projectId, user.getId())
                .orElseThrow(() -> new ProjectNotFoundException("Project not found with id: " + projectId));

        return codeAnalysisResultRepository.findByProjectIdAndUserId(projectId, user.getId());
    }

    public CodeAnalysisResult getAnalysisResultById(String resultId, String username) {
        log.info("Fetching analysis result by id: {}", resultId);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        return codeAnalysisResultRepository.findByIdAndUserId(resultId, user.getId())
                .orElseThrow(() -> new RuntimeException("Analysis result not found"));
    }

    private void upsertAnalysisResult(CodeAnalysisResult result) {
        String appKey = result.getAppKey();
        codeAnalysisResultRepository
                .findByProjectIdAndUserIdAndRepoUrlAndAppKey(
                        result.getProjectId(), result.getUserId(), result.getRepoUrl(), appKey)
                .ifPresent(existing -> {
                    result.setId(existing.getId());
                    result.setCreatedAt(existing.getCreatedAt());
                });
        codeAnalysisResultRepository.save(result);
    }

    private String buildAppKey(String repoUrl, ApplicationInfo appInfo) {
        if (appInfo != null && appInfo.isSpringBootApplication()
                && appInfo.getMainClassName() != null && appInfo.getMainClassPackage() != null) {
            return appInfo.getMainClassPackage() + "." + appInfo.getMainClassName();
        }
        return repoUrl + "::NON_SPRING";
    }
}
