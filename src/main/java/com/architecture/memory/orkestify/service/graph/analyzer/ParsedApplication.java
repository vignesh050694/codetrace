package com.architecture.memory.orkestify.service.graph.analyzer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level result of parsing a Spring Boot application.
 * Contains all parsed components and resolution indexes built during the two-pass analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedApplication {
    private String mainClassName;
    private String mainClassPackage;
    private boolean isSpringBoot;
    private String rootPath;
    private int lineStart;
    private int lineEnd;

    // All parsed components by type
    @Builder.Default
    private List<ParsedComponent> controllers = new ArrayList<>();
    @Builder.Default
    private List<ParsedComponent> services = new ArrayList<>();
    @Builder.Default
    private List<ParsedComponent> repositories = new ArrayList<>();
    @Builder.Default
    private List<ParsedComponent> configurations = new ArrayList<>();
    @Builder.Default
    private List<ParsedComponent> kafkaListeners = new ArrayList<>();

    // Resolution indexes (built during Pass 2)

    // Interface simple/qualified name -> list of implementing class qualified names
    @Builder.Default
    private Map<String, List<String>> interfaceToImplMap = new HashMap<>();

    // Qualified class name -> ParsedComponent
    @Builder.Default
    private Map<String, ParsedComponent> componentIndex = new HashMap<>();
}
