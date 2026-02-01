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
public class GitHubOrganization {

    private Long id;

    private String login;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    private String description;

    private String url;

    @JsonProperty("repos_url")
    private String reposUrl;
}
