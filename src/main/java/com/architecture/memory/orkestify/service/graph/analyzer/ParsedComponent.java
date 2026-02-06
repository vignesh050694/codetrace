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
 * Intermediate representation of a parsed Spring component.
 * This is the output of Pass 1 (collection) and input to Pass 2 (resolution).
 *
 * Unlike the old approach, this captures ALL information needed for graph building
 * including injected dependencies, interface relationships, and raw method invocations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedComponent {
    private String className;
    private String qualifiedName;
    private String packageName;
    private SpringComponentType componentType;
    private int lineStart;
    private int lineEnd;

    // For controllers: base URL from @RequestMapping
    private String baseUrl;

    // For repositories: extends info and entity mapping
    private String extendsClass;
    private String repositoryType;
    private String entityClassName;
    private String tableName;
    private String tableSource;
    private String databaseType;
    @Builder.Default
    private List<String> databaseOperations = new ArrayList<>();

    // Interface -> Implementation tracking
    @Builder.Default
    private List<String> implementedInterfaces = new ArrayList<>();

    // Injected fields: fieldName -> resolved concrete type qualified name
    // e.g., "paymentService" -> "com.example.PaymentServiceImpl"
    @Builder.Default
    private Map<String, InjectedDependency> injectedDependencies = new HashMap<>();

    // Parsed methods (endpoints for controllers, regular methods for services/repos)
    @Builder.Default
    private List<ParsedMethod> methods = new ArrayList<>();

    // For @Configuration: beans
    @Builder.Default
    private List<ParsedBean> beans = new ArrayList<>();

    // For Kafka listener classes: listener method details
    @Builder.Default
    private List<ParsedKafkaListenerMethod> kafkaListenerMethods = new ArrayList<>();
}
