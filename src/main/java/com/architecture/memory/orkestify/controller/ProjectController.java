package com.architecture.memory.orkestify.controller;

import com.architecture.memory.orkestify.dto.*;
import com.architecture.memory.orkestify.model.CodeAnalysisResult;
import com.architecture.memory.orkestify.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody CreateProjectRequest request) {
        String username = getAuthenticatedUsername();
        ProjectResponse response = projectService.createProject(request, username);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> updateProject(
            @PathVariable String id,
            @Valid @RequestBody UpdateProjectRequest request) {
        String username = getAuthenticatedUsername();
        ProjectResponse response = projectService.updateProject(id, request, username);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> getProjectById(@PathVariable String id) {
        String username = getAuthenticatedUsername();
        ProjectResponse response = projectService.getProjectById(id, username);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> getAllProjects(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {

        String username = getAuthenticatedUsername();

        if (search != null && !search.isBlank()) {
            List<ProjectResponse> response = projectService.searchProjects(search, username);
            return ResponseEntity.ok(response);
        }

        if ("active".equalsIgnoreCase(status)) {
            List<ProjectResponse> response = projectService.getActiveProjects(username);
            return ResponseEntity.ok(response);
        } else if ("archived".equalsIgnoreCase(status)) {
            List<ProjectResponse> response = projectService.getArchivedProjects(username);
            return ResponseEntity.ok(response);
        }

        List<ProjectResponse> response = projectService.getAllProjects(username);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/archive")
    public ResponseEntity<ProjectResponse> archiveProject(@PathVariable String id) {
        String username = getAuthenticatedUsername();
        ProjectResponse response = projectService.archiveProject(id, username);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/unarchive")
    public ResponseEntity<ProjectResponse> unarchiveProject(@PathVariable String id) {
        String username = getAuthenticatedUsername();
        ProjectResponse response = projectService.unarchiveProject(id, username);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable String id) {
        String username = getAuthenticatedUsername();
        projectService.deleteProject(id, username);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/analyze-code")
    public ResponseEntity<AnalysisJobResponse> analyzeProjectCode(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "false") boolean generateEmbeddings) {
        String username = getAuthenticatedUsername();
        AnalysisJobResponse response = projectService.analyzeProjectCode(id, username, generateEmbeddings);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/analysis-results")
    public ResponseEntity<List<CodeAnalysisResult>> getAnalysisResults(@PathVariable String id) {
        String username = getAuthenticatedUsername();
        List<CodeAnalysisResult> response = projectService.getAnalysisResults(id, username);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/analysis-results/{resultId}")
    public ResponseEntity<CodeAnalysisResult> getAnalysisResultById(@PathVariable String resultId) {
        String username = getAuthenticatedUsername();
        CodeAnalysisResult response = projectService.getAnalysisResultById(resultId, username);
        return ResponseEntity.ok(response);
    }

    private String getAuthenticatedUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication.getName();
    }
}
