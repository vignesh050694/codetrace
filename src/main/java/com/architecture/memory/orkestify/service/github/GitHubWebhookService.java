package com.architecture.memory.orkestify.service.github;

import com.architecture.memory.orkestify.dto.github.PullRequestFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Handles GitHub webhook signature validation and GitHub API calls
 * for webhook-triggered operations (fetching PR files, posting comments).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubWebhookService {

    private final WebClient.Builder webClientBuilder;

    @Value("${github.api.base-url}")
    private String githubApiBaseUrl;

    @Value("${github.webhook.secret:}")
    private String webhookSecret;

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String COMMENT_MARKER = "<!-- orkestify-impact-report -->";

    // ========================= SIGNATURE VALIDATION =========================

    /**
     * Validate the webhook signature using HMAC-SHA256.
     *
     * @param payload   Raw request body bytes
     * @param signature The X-Hub-Signature-256 header value
     * @return true if the signature is valid
     */
    public boolean validateSignature(byte[] payload, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Webhook secret not configured, skipping signature validation");
            return true;
        }

        if (signature == null || !signature.startsWith(SIGNATURE_PREFIX)) {
            log.error("Invalid or missing webhook signature header");
            return false;
        }

        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKey = new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKey);

            byte[] computedHash = mac.doFinal(payload);
            String computedSignature = SIGNATURE_PREFIX + HexFormat.of().formatHex(computedHash);

            boolean valid = constantTimeEquals(computedSignature, signature);
            if (!valid) {
                log.error("Webhook signature validation failed");
            }
            return valid;

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error validating webhook signature: {}", e.getMessage());
            return false;
        }
    }

    // ========================= PR FILES API =========================

    /**
     * Fetch the list of files changed in a pull request.
     * GET /repos/{owner}/{repo}/pulls/{pull_number}/files
     */
    public List<PullRequestFile> fetchPullRequestFiles(String owner, String repo, int prNumber, String accessToken) {
        log.info("Fetching PR files: {}/{} #{}", owner, repo, prNumber);

        WebClient webClient = buildClient(accessToken);

        List<PullRequestFile> files = webClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}/files?per_page=100", owner, repo, prNumber)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<PullRequestFile>>() {})
                .block();

        log.info("Fetched {} files for PR #{}", files != null ? files.size() : 0, prNumber);
        return files != null ? files : List.of();
    }

    // ========================= COMMENT API =========================

    /**
     * Post a new comment on a PR (via the Issues API).
     * POST /repos/{owner}/{repo}/issues/{issue_number}/comments
     *
     * @return The comment ID for future updates
     */
    public Long postComment(String owner, String repo, int issueNumber, String body, String accessToken) {
        log.info("Posting comment on {}/{} #{}", owner, repo, issueNumber);

        WebClient webClient = buildClient(accessToken);

        Map<String, Object> response = webClient.post()
                .uri("/repos/{owner}/{repo}/issues/{number}/comments", owner, repo, issueNumber)
                .bodyValue(Map.of("body", COMMENT_MARKER + "\n" + body))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();

        if (response != null && response.containsKey("id")) {
            Long commentId = ((Number) response.get("id")).longValue();
            log.info("Posted comment {} on {}/{} #{}", commentId, owner, repo, issueNumber);
            return commentId;
        }

        log.warn("Failed to get comment ID from response");
        return null;
    }

    /**
     * Update an existing comment.
     * PATCH /repos/{owner}/{repo}/issues/comments/{comment_id}
     */
    public void updateComment(String owner, String repo, long commentId, String body, String accessToken) {
        log.info("Updating comment {} on {}/{}", commentId, owner, repo);

        WebClient webClient = buildClient(accessToken);

        webClient.patch()
                .uri("/repos/{owner}/{repo}/issues/comments/{commentId}", owner, repo, commentId)
                .bodyValue(Map.of("body", COMMENT_MARKER + "\n" + body))
                .retrieve()
                .bodyToMono(Void.class)
                .block();

        log.info("Updated comment {} on {}/{}", commentId, owner, repo);
    }

    /**
     * Find an existing Orkestify comment on a PR by searching for our marker.
     * GET /repos/{owner}/{repo}/issues/{issue_number}/comments
     *
     * @return The comment ID if found, null otherwise
     */
    public Long findExistingComment(String owner, String repo, int issueNumber, String accessToken) {
        WebClient webClient = buildClient(accessToken);

        List<Map<String, Object>> comments = webClient.get()
                .uri("/repos/{owner}/{repo}/issues/{number}/comments?per_page=100", owner, repo, issueNumber)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block();

        if (comments != null) {
            for (Map<String, Object> comment : comments) {
                String commentBody = (String) comment.get("body");
                if (commentBody != null && commentBody.contains(COMMENT_MARKER)) {
                    Long commentId = ((Number) comment.get("id")).longValue();
                    log.info("Found existing Orkestify comment {} on {}/{} #{}", commentId, owner, repo, issueNumber);
                    return commentId;
                }
            }
        }

        return null;
    }

    /**
     * Post or update the Orkestify impact report comment on a PR.
     * If an existing comment is found, update it. Otherwise, create a new one.
     *
     * @return The comment ID
     */
    public Long postOrUpdateComment(String owner, String repo, int prNumber, String body, String accessToken) {
        Long existingCommentId = findExistingComment(owner, repo, prNumber, accessToken);

        if (existingCommentId != null) {
            updateComment(owner, repo, existingCommentId, body, accessToken);
            return existingCommentId;
        } else {
            return postComment(owner, repo, prNumber, body, accessToken);
        }
    }

    // ========================= HELPERS =========================

    private WebClient buildClient(String accessToken) {
        return webClientBuilder
                .baseUrl(githubApiBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * Constant-time string comparison to prevent timing attacks on signature validation.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;

        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
