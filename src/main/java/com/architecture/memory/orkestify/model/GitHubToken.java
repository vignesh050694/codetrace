package com.architecture.memory.orkestify.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "github_tokens")
public class GitHubToken {

    @Id
    private String id;

    private String userId;

    private String accessToken;

    private String tokenType;

    private String scope;

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;
}
