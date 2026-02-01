package com.architecture.memory.orkestify.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "projects")
public class Project {

    @Id
    private String id;

    private String name;

    @Indexed
    private String userId;

    @Builder.Default
    private List<String> githubUrls = new ArrayList<>();

    @Builder.Default
    private boolean archived = false;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime archivedAt;
}
