package com.architecture.memory.orkestify.controller;

import com.architecture.memory.orkestify.dto.GitHubOrganization;
import com.architecture.memory.orkestify.dto.GitHubRepository;
import com.architecture.memory.orkestify.dto.GitHubUser;
import com.architecture.memory.orkestify.service.GitHubService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/github")
@RequiredArgsConstructor
@Slf4j
public class GitHubController {

    private final GitHubService gitHubService;

    @GetMapping("/login")
    public ResponseEntity<Map<String, String>> loginInfo() {
        return ResponseEntity.ok(Map.of(
            "message", "Redirect to /oauth2/authorization/github to login with GitHub",
            "loginUrl", "/oauth2/authorization/github"
        ));
    }

    @GetMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(
            @RegisteredOAuth2AuthorizedClient("github") OAuth2AuthorizedClient authorizedClient,
            @AuthenticationPrincipal OAuth2User oauth2User) {

        String userId = oauth2User.getAttribute("login");
        String accessToken = authorizedClient.getAccessToken().getTokenValue();
        String tokenType = authorizedClient.getAccessToken().getTokenType().getValue();

        // Get scopes if available
        String scope = authorizedClient.getAccessToken().getScopes() != null
            ? String.join(",", authorizedClient.getAccessToken().getScopes().stream()
                .map(Object::toString).toArray(String[]::new))
            : "";

        gitHubService.saveToken(userId, accessToken, tokenType, scope);

        log.info("GitHub OAuth callback successful for user: {}", userId);

        return ResponseEntity.ok(Map.of(
            "message", "Successfully authenticated with GitHub",
            "userId", userId,
            "user", oauth2User.getAttributes()
        ));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<GitHubUser> getUser(@PathVariable String userId) {
        GitHubUser user = gitHubService.getAuthenticatedUser(userId);
        return ResponseEntity.ok(user);
    }

    @GetMapping("/user/{userId}/organizations")
    public ResponseEntity<List<GitHubOrganization>> getUserOrganizations(@PathVariable String userId) {
        List<GitHubOrganization> organizations = gitHubService.getUserOrganizations(userId);
        return ResponseEntity.ok(organizations);
    }

    @GetMapping("/user/{userId}/organization/{orgName}/repositories")
    public ResponseEntity<List<GitHubRepository>> getOrganizationRepositories(
            @PathVariable String userId,
            @PathVariable String orgName) {
        List<GitHubRepository> repositories = gitHubService.getOrganizationRepositories(userId, orgName);
        return ResponseEntity.ok(repositories);
    }

    @GetMapping("/user/{userId}/repositories")
    public ResponseEntity<List<GitHubRepository>> getUserRepositories(@PathVariable String userId) {
        List<GitHubRepository> repositories = gitHubService.getUserRepositories(userId);
        return ResponseEntity.ok(repositories);
    }

    @GetMapping("/user/{userId}/token/status")
    public ResponseEntity<Map<String, Boolean>> checkTokenStatus(@PathVariable String userId) {
        boolean hasToken = gitHubService.isTokenAvailable(userId);
        return ResponseEntity.ok(Map.of("hasToken", hasToken));
    }

    @DeleteMapping("/user/{userId}/token")
    public ResponseEntity<Map<String, String>> deleteToken(@PathVariable String userId) {
        gitHubService.deleteToken(userId);
        return ResponseEntity.ok(Map.of("message", "Token deleted successfully"));
    }
}
