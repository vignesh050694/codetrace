package com.architecture.memory.orkestify.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;
import java.util.Set;

/**
 * Configuration document for storing analyzer mappings and settings in MongoDB.
 * This allows dynamic configuration changes without code modifications.
 */
@Document(collection = "analyzer_configurations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzerConfiguration {

    @Id
    private String id;

    private String configType; // e.g., "MAPPING_ANNOTATIONS", "HTTP_METHODS", etc.

    private Set<String> stringSet; // For sets like MAPPING_ANNOTATIONS, REST_TEMPLATE_METHODS

    private Map<String, String> stringMap; // For maps like ANNOTATION_TO_HTTP_METHOD

    private String description;

    private boolean active = true;

    private long version = 1L;
}
