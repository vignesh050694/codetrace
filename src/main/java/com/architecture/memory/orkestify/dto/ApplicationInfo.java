package com.architecture.memory.orkestify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationInfo {
    private boolean isSpringBootApplication;
    private String mainClassName;
    private String mainClassPackage;
    private String rootPath;
    private LineRange line;
}
