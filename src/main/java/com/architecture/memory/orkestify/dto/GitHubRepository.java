package com.architecture.memory.orkestify.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitHubRepository {

    private Long id;

    private String name;

    @JsonProperty("full_name")
    private String fullName;

    private String description;

    @JsonProperty("html_url")
    private String htmlUrl;

    @JsonProperty("clone_url")
    private String cloneUrl;

    @JsonProperty("ssh_url")
    private String sshUrl;

    @JsonProperty("git_url")
    private String gitUrl;

    @JsonProperty("default_branch")
    private String defaultBranch;

    private String language;

    @JsonProperty("private")
    private Boolean isPrivate;

    private Boolean fork;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    @JsonProperty("pushed_at")
    private String pushedAt;

    private Integer size;

    @JsonProperty("stargazers_count")
    private Integer stargazersCount;

    @JsonProperty("watchers_count")
    private Integer watchersCount;

    @JsonProperty("forks_count")
    private Integer forksCount;

    @JsonProperty("open_issues_count")
    private Integer openIssuesCount;
}
