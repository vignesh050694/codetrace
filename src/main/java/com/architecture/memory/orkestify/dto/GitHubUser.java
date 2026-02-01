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
public class GitHubUser {

    private Long id;

    private String login;

    private String name;

    private String email;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    private String bio;

    private String company;

    private String location;

    @JsonProperty("html_url")
    private String htmlUrl;
}
