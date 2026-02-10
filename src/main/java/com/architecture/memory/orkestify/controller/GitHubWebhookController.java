package com.architecture.memory.orkestify.controller;

import com.architecture.memory.orkestify.dto.github.WebhookPayload;
import com.architecture.memory.orkestify.service.github.GitHubWebhookService;
import com.architecture.memory.orkestify.service.github.PRAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Webhook endpoint for GitHub events.
 * Receives push and pull_request events, validates signatures,
 * and dispatches to the PR analysis pipeline.
 */
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class GitHubWebhookController {

    private final GitHubWebhookService gitHubWebhookService;
    private final PRAnalysisService prAnalysisService;

    private static final Set<String> PROCESSABLE_ACTIONS = Set.of(
            "opened", "synchronize", "reopened");

    /**
     * Receive GitHub webhook events.
     *
     * Headers:
     *   X-GitHub-Event: pull_request | push | ping
     *   X-Hub-Signature-256: sha256=...
     *   X-GitHub-Delivery: unique delivery GUID
     *
     * @param event     The X-GitHub-Event header
     * @param signature The X-Hub-Signature-256 header
     * @param rawBody   The raw request body bytes (for signature validation)
     * @param payload   The parsed JSON body (for pull_request events)
     */
    @PostMapping("/github")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestBody byte[] rawBody) {

        log.info("Received GitHub webhook: event={}, delivery={}", event, deliveryId);

        // Validate webhook signature
        if (!gitHubWebhookService.validateSignature(rawBody, signature)) {
            log.error("Invalid webhook signature for delivery: {}", deliveryId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid signature"));
        }

        // Handle ping events (sent when webhook is first configured)
        if ("ping".equals(event)) {
            log.info("Received ping event");
            return ResponseEntity.ok(Map.of("message", "pong"));
        }

        // Handle pull_request events
        if ("pull_request".equals(event)) {
            return handlePullRequestEvent(rawBody);
        }

        log.debug("Ignoring unhandled event type: {}", event);
        return ResponseEntity.ok(Map.of("message", "Event ignored: " + event));
    }

    /**
     * Handle pull_request events by dispatching to the analysis pipeline.
     */
    private ResponseEntity<Map<String, String>> handlePullRequestEvent(byte[] rawBody) {
        WebhookPayload payload;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();

            // GitHub can send application/json or application/x-www-form-urlencoded (payload=...)
            String bodyText = new String(rawBody, StandardCharsets.UTF_8).trim();
            if (bodyText.startsWith("payload=")) {
                String json = URLDecoder.decode(bodyText.substring("payload=".length()), StandardCharsets.UTF_8);
                payload = mapper.readValue(json, WebhookPayload.class);
            } else {
                payload = mapper.readValue(rawBody, WebhookPayload.class);
            }
        } catch (Exception e) {
            log.error("Failed to parse webhook payload: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid payload"));
        }

        String action = payload.getAction();
        String fullName = payload.getRepository() != null ? payload.getRepository().getFullName() : "unknown";

        log.info("Pull request event: action={}, repo={}, PR #{}",
                action, fullName, payload.getNumber());

        if ("closed".equals(action)) {
            prAnalysisService.handlePullRequestClosed(payload);
            return ResponseEntity.ok(Map.of("message", "PR close handled"));
        }

        if (PROCESSABLE_ACTIONS.contains(action)) {
            prAnalysisService.processPullRequestEvent(payload);
            return ResponseEntity.accepted()
                    .body(Map.of("message", "Analysis triggered for PR #" + payload.getNumber()));
        }

        log.debug("Ignoring pull_request action: {}", action);
        return ResponseEntity.ok(Map.of("message", "Action ignored: " + action));
    }
}
