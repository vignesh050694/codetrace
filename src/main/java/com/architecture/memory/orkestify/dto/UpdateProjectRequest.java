package com.architecture.memory.orkestify.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProjectRequest {

    @NotBlank(message = "Project name is required")
    private String name;

    private List<String> githubUrls;
}
