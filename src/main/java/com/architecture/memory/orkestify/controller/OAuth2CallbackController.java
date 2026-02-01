package com.architecture.memory.orkestify.controller;

import com.architecture.memory.orkestify.service.GitHubService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

@Controller
@RequiredArgsConstructor
@Slf4j
public class OAuth2CallbackController {

    private final GitHubService gitHubService;

    @GetMapping("/oauth2/callback")
    public RedirectView handleCallback(
            @RegisteredOAuth2AuthorizedClient("github") OAuth2AuthorizedClient authorizedClient,
            @AuthenticationPrincipal OAuth2User oauth2User) {

        String userId = oauth2User.getAttribute("login");
        String accessToken = authorizedClient.getAccessToken().getTokenValue();
        String tokenType = authorizedClient.getAccessToken().getTokenType().getValue();

        String scope = authorizedClient.getAccessToken().getScopes() != null
            ? String.join(",", authorizedClient.getAccessToken().getScopes().stream()
                .map(Object::toString).toArray(String[]::new))
            : "";

        gitHubService.saveToken(userId, accessToken, tokenType, scope);

        log.info("GitHub OAuth callback successful for user: {}", userId);

        // Redirect to a success page or frontend URL
        return new RedirectView("/api/github/callback?userId=" + userId);
    }
}
