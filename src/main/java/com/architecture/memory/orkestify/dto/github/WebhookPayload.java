package com.architecture.memory.orkestify.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GitHub webhook payload for pull_request events.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookPayload {

    private String action;  // opened, synchronize, closed, reopened
    private int number;     // PR number

    @JsonProperty("pull_request")
    private PullRequest pullRequest;

    private Repository repository;
    private Sender sender;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PullRequest {
        private int number;
        private String title;
        private String state;

        @JsonProperty("html_url")
        private String htmlUrl;

        private Head head;
        private Base base;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Head {
        private String ref;  // Branch name
        private String sha;  // Head commit SHA

        @JsonProperty("repo")
        private HeadRepo repo;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HeadRepo {
        @JsonProperty("clone_url")
        private String cloneUrl;

        @JsonProperty("full_name")
        private String fullName;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Base {
        private String ref;  // Base branch name (e.g., "main")
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Repository {
        private long id;

        @JsonProperty("full_name")
        private String fullName;  // e.g., "owner/repo"

        private String name;

        @JsonProperty("clone_url")
        private String cloneUrl;

        @JsonProperty("html_url")
        private String htmlUrl;

        @JsonProperty("default_branch")
        private String defaultBranch;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sender {
        private String login;
    }

    /**
     * Extract owner from full_name (e.g., "owner/repo" -> "owner").
     */
    public String getOwner() {
        if (repository != null && repository.getFullName() != null) {
            return repository.getFullName().split("/")[0];
        }
        return null;
    }

    /**
     * Extract repo name from full_name (e.g., "owner/repo" -> "repo").
     */
    public String getRepoName() {
        if (repository != null && repository.getFullName() != null) {
            String[] parts = repository.getFullName().split("/");
            return parts.length > 1 ? parts[1] : parts[0];
        }
        return null;
    }
}
