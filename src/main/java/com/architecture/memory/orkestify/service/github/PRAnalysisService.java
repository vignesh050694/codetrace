package com.architecture.memory.orkestify.service.github;

import com.architecture.memory.orkestify.dto.github.ImpactReport;
import com.architecture.memory.orkestify.dto.github.PullRequestFile;
import com.architecture.memory.orkestify.dto.github.RiskAssessment;
import com.architecture.memory.orkestify.dto.github.WebhookPayload;
import com.architecture.memory.orkestify.dto.graph.CreateShadowGraphRequest;
import com.architecture.memory.orkestify.dto.graph.ShadowGraphResponse;
import com.architecture.memory.orkestify.model.GitHubToken;
import com.architecture.memory.orkestify.model.Project;
import com.architecture.memory.orkestify.model.ShadowGraph;
import com.architecture.memory.orkestify.repository.GitHubTokenRepository;
import com.architecture.memory.orkestify.repository.ProjectRepository;
import com.architecture.memory.orkestify.repository.ShadowGraphRepository;
import com.architecture.memory.orkestify.service.graph.ShadowGraphService;
import com.architecture.memory.orkestify.service.llm.LlmReportGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the full PR analysis pipeline:
 * 1. Receive webhook event
 * 2. Find project by repository URL
 * 3. Post "analyzing..." comment
 * 4. Trigger shadow graph creation
 * 5. Poll for completion
 * 6. Run impact analysis
 * 7. Perform risk assessment
 * 8. Format and post/update the impact report comment with risk assessment
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PRAnalysisService {

    private final ProjectRepository projectRepository;
    private final GitHubTokenRepository gitHubTokenRepository;
    private final ShadowGraphRepository shadowGraphRepository;
    private final ShadowGraphService shadowGraphService;
    private final GitHubWebhookService gitHubWebhookService;
    private final ImpactAnalysisService impactAnalysisService;
    private final RiskAnalysisService riskAnalysisService;
    private final ImpactReportFormatter reportFormatter;
    private final LlmReportGenerator llmReportGenerator;

    @Value("${orkestify.report.use-llm:false}")
    private boolean useLlmReports;

    private static final int MAX_POLL_ATTEMPTS = 120;   // 120 * 5s = 10 minutes max
    private static final int POLL_INTERVAL_MS = 5000;

    /**
     * Process a pull_request webhook event asynchronously.
     * This is the main entry point called by the webhook controller.
     */
    @Async
    public void processPullRequestEvent(WebhookPayload payload) {
        String owner = payload.getOwner();
        String repo = payload.getRepoName();
        int prNumber = payload.getNumber();
        String branchName = payload.getPullRequest().getHead().getRef();
        String baseBranch = payload.getPullRequest().getBase().getRef();
        String cloneUrl = payload.getRepository().getCloneUrl();

        log.info("Processing PR event: {}/{} #{} branch={} action={}",
                owner, repo, prNumber, branchName, payload.getAction());

        try {
            // Step 1: Find the project by repository URL
            Project project = findProjectByRepoUrl(cloneUrl, payload.getRepository().getHtmlUrl());
            if (project == null) {
                log.warn("No project found for repository: {}/{}", owner, repo);
                return;
            }

            // Step 2: Get the project owner's GitHub access token
            String accessToken = getAccessToken(project.getUserId());
            if (accessToken == null) {
                log.warn("No GitHub token found for project owner: {}", project.getUserId());
                return;
            }

            // Step 3: Post initial "analyzing" comment
            String processingComment = reportFormatter.formatProcessing(branchName, prNumber);
            Long commentId = gitHubWebhookService.postOrUpdateComment(
                    owner, repo, prNumber, processingComment, accessToken);

            // Step 4: Create shadow graph
            CreateShadowGraphRequest request = CreateShadowGraphRequest.builder()
                    .repoUrl(cloneUrl)
                    .branchName(branchName)
                    .baseBranch(baseBranch)
                    .prNumber(String.valueOf(prNumber))
                    .build();

            ShadowGraphResponse shadowResponse = shadowGraphService.createShadowGraph(
                    project.getId(), request);
            String shadowId = shadowResponse.getShadowId();

            // Store comment ID and GitHub info on the shadow graph
            Optional<ShadowGraph> sgOpt = shadowGraphRepository
                    .findByProjectIdAndShadowId(project.getId(), shadowId);
            if (sgOpt.isPresent()) {
                ShadowGraph sg = sgOpt.get();
                sg.setGithubCommentId(commentId);
                sg.setGithubOwner(owner);
                sg.setGithubRepo(repo);
                shadowGraphRepository.save(sg);
            }

            // Step 5: Poll for shadow graph completion
            log.info("Waiting for shadow graph {} to complete...", shadowId);
            ShadowGraphResponse completedShadow = pollForCompletion(project.getId(), shadowId);

            if (completedShadow == null || ShadowGraph.Status.FAILED.equals(completedShadow.getStatus())) {
                String error = completedShadow != null ? completedShadow.getErrorMessage() : "Analysis timed out";
                log.error("Shadow graph analysis failed for PR #{}: {}", prNumber, error);
                String errorReport = reportFormatter.formatError(branchName, prNumber, error);
                gitHubWebhookService.postOrUpdateComment(owner, repo, prNumber, errorReport, accessToken);
                return;
            }

            // Step 6: Fetch PR files and run impact analysis
            List<PullRequestFile> prFiles = gitHubWebhookService.fetchPullRequestFiles(
                    owner, repo, prNumber, accessToken);

            // Get the full diff from the shadow graph
            ShadowGraphResponse fullDiff = shadowGraphService.getShadowGraph(
                    project.getId(), shadowId, true);

            ImpactReport report = impactAnalysisService.analyzeImpact(
                    project.getId(),
                    prFiles,
                    fullDiff.getDiff(),
                    fullDiff.getDiff() != null ? fullDiff.getDiff().getCircularDependencies() : null);

            report.setProjectId(project.getId());
            report.setBranchName(branchName);
            report.setPrNumber(prNumber);
            report.setPrUrl(payload.getPullRequest().getHtmlUrl());

            // Step 7: Generate the report (LLM or static)
            String reportComment;
            if (useLlmReports) {
                log.info("Generating LLM-based report for PR #{}", prNumber);
                reportComment = llmReportGenerator.generatePrAnalysisReport(report);
            } else {
                log.info("Generating static report for PR #{}", prNumber);
                RiskAssessment riskAssessment = riskAnalysisService.assessRisk(report);
                log.info("Risk assessment for PR #{}: {} (score: {})",
                        prNumber, riskAssessment.getOverallRiskLevel(), riskAssessment.getOverallScore());
                reportComment = reportFormatter.format(report, riskAssessment);
            }

            // Step 8: Post the report
            gitHubWebhookService.postOrUpdateComment(owner, repo, prNumber, reportComment, accessToken);

            log.info("Successfully posted impact report for {}/{} #{}", owner, repo, prNumber);

        } catch (Exception e) {
            log.error("Error processing PR event for {}/{} #{}: {}",
                    owner, repo, prNumber, e.getMessage(), e);

            // Try to post an error comment
            tryPostErrorComment(owner, repo, prNumber, branchName, e.getMessage(),
                    payload.getRepository().getCloneUrl(), payload.getRepository().getHtmlUrl());
        }
    }

    /**
     * Handle a PR close event — clean up the shadow graph.
     */
    @Async
    public void handlePullRequestClosed(WebhookPayload payload) {
        String owner = payload.getOwner();
        String repo = payload.getRepoName();
        int prNumber = payload.getNumber();

        log.info("PR closed: {}/{} #{}", owner, repo, prNumber);

        String cloneUrl = payload.getRepository().getCloneUrl();
        Project project = findProjectByRepoUrl(cloneUrl, payload.getRepository().getHtmlUrl());
        if (project == null) return;

        // Find and delete the shadow graph for this PR
        Optional<ShadowGraph> sgOpt = shadowGraphRepository
                .findTopByProjectIdAndPrNumberOrderByCreatedAtDesc(
                        project.getId(), String.valueOf(prNumber));

        if (sgOpt.isPresent()) {
            ShadowGraph sg = sgOpt.get();
            try {
                shadowGraphService.deleteShadowGraph(project.getId(), sg.getShadowId());
                log.info("Cleaned up shadow graph for closed PR #{}", prNumber);
            } catch (Exception e) {
                log.warn("Failed to cleanup shadow graph for PR #{}: {}", prNumber, e.getMessage());
            }
        }
    }

    // ========================= HELPERS =========================

    /**
     * Find a project by matching the repository URL against stored GitHub URLs.
     * Tries both clone URL and HTML URL formats.
     */
    private Project findProjectByRepoUrl(String cloneUrl, String htmlUrl) {
        // Try clone URL first
        List<Project> projects = projectRepository.findByGithubUrlsContaining(cloneUrl);
        if (!projects.isEmpty()) return projects.get(0);

        // Try HTML URL
        if (htmlUrl != null) {
            projects = projectRepository.findByGithubUrlsContaining(htmlUrl);
            if (!projects.isEmpty()) return projects.get(0);
        }

        // Try without .git suffix
        if (cloneUrl != null && cloneUrl.endsWith(".git")) {
            String withoutGit = cloneUrl.substring(0, cloneUrl.length() - 4);
            projects = projectRepository.findByGithubUrlsContaining(withoutGit);
            if (!projects.isEmpty()) return projects.get(0);
        }

        return null;
    }

    private String getAccessToken(String userId) {
        return gitHubTokenRepository.findByUserId(userId)
                .map(GitHubToken::getAccessToken)
                .orElse(null);
    }

    /**
     * Poll the shadow graph status until it completes or fails.
     */
    private ShadowGraphResponse pollForCompletion(String projectId, String shadowId) {
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

            try {
                ShadowGraphResponse response = shadowGraphService.getShadowGraph(
                        projectId, shadowId, false);

                String status = response.getStatus();
                if (ShadowGraph.Status.COMPLETED.equals(status) || ShadowGraph.Status.FAILED.equals(status)) {
                    log.info("Shadow graph {} reached status: {} after {} polls",
                            shadowId, status, attempt + 1);
                    return response;
                }
            } catch (Exception e) {
                log.warn("Error polling shadow graph status: {}", e.getMessage());
            }
        }

        log.error("Shadow graph {} timed out after {} attempts", shadowId, MAX_POLL_ATTEMPTS);
        return null;
    }

    /**
     * Best-effort attempt to post an error comment when analysis fails.
     */
    private void tryPostErrorComment(String owner, String repo, int prNumber,
                                      String branchName, String error,
                                      String cloneUrl, String htmlUrl) {
        try {
            Project project = findProjectByRepoUrl(cloneUrl, htmlUrl);
            if (project == null) return;

            String accessToken = getAccessToken(project.getUserId());
            if (accessToken == null) return;

            String errorReport = reportFormatter.formatError(branchName, prNumber, error);
            gitHubWebhookService.postOrUpdateComment(owner, repo, prNumber, errorReport, accessToken);
        } catch (Exception ex) {
            log.error("Failed to post error comment: {}", ex.getMessage());
        }
    }
}
