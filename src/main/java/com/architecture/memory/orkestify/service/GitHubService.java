package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.dto.GitHubOrganization;
import com.architecture.memory.orkestify.dto.GitHubRepository;
import com.architecture.memory.orkestify.dto.GitHubUser;
import com.architecture.memory.orkestify.model.GitHubToken;
import com.architecture.memory.orkestify.repository.GitHubTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubService {

    private final GitHubTokenRepository tokenRepository;
    private final WebClient.Builder webClientBuilder;

    @Value("${github.api.base-url}")
    private String githubApiBaseUrl;

    public void saveToken(String userId, String accessToken, String tokenType, String scope) {
        log.info("Saving GitHub token for user: {}", userId);

        GitHubToken token = GitHubToken.builder()
                .userId(userId)
                .accessToken(accessToken)
                .tokenType(tokenType)
                .scope(scope)
                .createdAt(LocalDateTime.now())
                .build();

        tokenRepository.deleteByUserId(userId);
        tokenRepository.save(token);

        log.info("GitHub token saved successfully for user: {}", userId);
    }

    public GitHubUser getAuthenticatedUser(String userId) {
        log.info("Fetching authenticated GitHub user for userId: {}", userId);

        String accessToken = getAccessToken(userId);

        WebClient webClient = webClientBuilder
                .baseUrl(githubApiBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        return webClient.get()
                .uri("/user")
                .retrieve()
                .bodyToMono(GitHubUser.class)
                .block();
    }

    public List<GitHubOrganization> getUserOrganizations(String userId) {
        log.info("Fetching organizations for user: {}", userId);

        String accessToken = getAccessToken(userId);

        WebClient webClient = webClientBuilder
                .baseUrl(githubApiBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();

        List<GitHubOrganization> organizations = webClient.get()
                .uri("/user/orgs")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<GitHubOrganization>>() {})
                .block();

        log.info("Found {} organizations for user: {}", organizations != null ? organizations.size() : 0, userId);
        return organizations;
    }

    public List<GitHubRepository> getOrganizationRepositories(String userId, String orgName) {
        log.info("Fetching repositories for organization: {} (user: {})", orgName, userId);

        String accessToken = getAccessToken(userId);

        WebClient webClient = webClientBuilder
                .baseUrl(githubApiBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        List<GitHubRepository> repositories = webClient.get()
                .uri("/orgs/{org}/repos?per_page=100", orgName)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<GitHubRepository>>() {})
                .block();

        log.info("Found {} repositories for organization: {}",
                repositories != null ? repositories.size() : 0, orgName);
        return repositories;
    }

    public List<GitHubRepository> getUserRepositories(String userId) {
        log.info("Fetching user repositories for user: {}", userId);

        String accessToken = getAccessToken(userId);

        WebClient webClient = webClientBuilder
                .baseUrl(githubApiBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();

        List<GitHubRepository> repositories = webClient.get()
                .uri("/user/repos?per_page=100&affiliation=owner")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<GitHubRepository>>() {})
                .block();

        log.info("Found {} repositories for user: {}",
                repositories != null ? repositories.size() : 0, userId);
        return repositories;
    }

    public boolean isTokenAvailable(String userId) {
        return tokenRepository.findByUserId(userId).isPresent();
    }

    public void deleteToken(String userId) {
        log.info("Deleting GitHub token for user: {}", userId);
        tokenRepository.deleteByUserId(userId);
    }

    private String getAccessToken(String userId) {
        return tokenRepository.findByUserId(userId)
                .map(GitHubToken::getAccessToken)
                .orElseThrow(() -> new RuntimeException("GitHub token not found for user: " + userId));
    }
}
