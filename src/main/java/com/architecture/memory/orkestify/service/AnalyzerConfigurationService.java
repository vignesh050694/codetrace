package com.architecture.memory.orkestify.service;

import com.architecture.memory.orkestify.model.AnalyzerConfiguration;
import com.architecture.memory.orkestify.repository.AnalyzerConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for loading analyzer configurations from MongoDB.
 * Configurations are cached to improve performance.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnalyzerConfigurationService {

    private final AnalyzerConfigurationRepository configurationRepository;

    /**
     * Get mapping annotations (e.g., GetMapping, PostMapping, etc.)
     */
    @Cacheable("mappingAnnotations")
    public Set<String> getMappingAnnotations() {
        return getStringSet("MAPPING_ANNOTATIONS");
    }

    /**
     * Get annotation to HTTP method mapping
     */
    @Cacheable("annotationToHttpMethod")
    public Map<String, String> getAnnotationToHttpMethod() {
        return getStringMap("ANNOTATION_TO_HTTP_METHOD");
    }

    /**
     * Get REST template methods
     */
    @Cacheable("restTemplateMethods")
    public Set<String> getRestTemplateMethods() {
        return getStringSet("REST_TEMPLATE_METHODS");
    }

    /**
     * Get WebClient HTTP methods
     */
    @Cacheable("webClientHttpMethods")
    public Set<String> getWebClientHttpMethods() {
        return getStringSet("WEBCLIENT_HTTP_METHODS");
    }

    /**
     * Get Kafka producer methods
     */
    @Cacheable("kafkaProducerMethods")
    public Set<String> getKafkaProducerMethods() {
        return getStringSet("KAFKA_PRODUCER_METHODS");
    }

    /**
     * Get Kafka producer types
     */
    @Cacheable("kafkaProducerTypes")
    public Set<String> getKafkaProducerTypes() {
        return getStringSet("KAFKA_PRODUCER_TYPES");
    }

    /**
     * Get HTTP URL connection methods
     */
    @Cacheable("httpUrlConnectionMethods")
    public Set<String> getHttpUrlConnectionMethods() {
        return getStringSet("HTTP_URL_CONNECTION_METHODS");
    }

    /**
     * Get repository write methods
     */
    @Cacheable("repositoryWriteMethods")
    public Set<String> getRepositoryWriteMethods() {
        return getStringSet("REPOSITORY_WRITE_METHODS");
    }

    /**
     * Get repository read methods
     */
    @Cacheable("repositoryReadMethods")
    public Set<String> getRepositoryReadMethods() {
        return getStringSet("REPOSITORY_READ_METHODS");
    }

    /**
     * Get allowed analysis packages (packages that should be analyzed even if they would normally be filtered as standard types)
     */
    @Cacheable("allowedAnalysisPackages")
    public Set<String> getAllowedAnalysisPackages() {
        return getStringSet("ALLOWED_ANALYSIS_PACKAGES");
    }

    /**
     * Get all active configurations
     */
    public List<AnalyzerConfiguration> getAllActiveConfigurations() {
        return configurationRepository.findByActive(true);
    }

    /**
     * Get configuration by type
     */
    public Optional<AnalyzerConfiguration> getConfigurationByType(String configType) {
        return configurationRepository.findByConfigTypeAndActive(configType, true);
    }

    /**
     * Update an existing configuration
     */
    public AnalyzerConfiguration updateConfiguration(String configType, AnalyzerConfiguration updates) {
        Optional<AnalyzerConfiguration> existing = configurationRepository.findByConfigTypeAndActive(configType, true);
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("Configuration not found: " + configType);
        }

        AnalyzerConfiguration config = existing.get();
        if (updates.getStringSet() != null) {
            config.setStringSet(updates.getStringSet());
        }
        if (updates.getStringMap() != null) {
            config.setStringMap(updates.getStringMap());
        }
        if (updates.getDescription() != null) {
            config.setDescription(updates.getDescription());
        }

        config.setVersion(config.getVersion() + 1);
        return configurationRepository.save(config);
    }

    /**
     * Deactivate a configuration (soft delete)
     */
    public void deactivateConfiguration(String configType) {
        Optional<AnalyzerConfiguration> existing = configurationRepository.findByConfigTypeAndActive(configType, true);
        if (existing.isPresent()) {
            AnalyzerConfiguration config = existing.get();
            config.setActive(false);
            config.setVersion(config.getVersion() + 1);
            configurationRepository.save(config);
            log.info("Deactivated configuration: {}", configType);
        }
    }

    /**
     * Save or update a configuration
     */
    public AnalyzerConfiguration saveConfiguration(AnalyzerConfiguration config) {
        // Increment version for optimistic locking
        Optional<AnalyzerConfiguration> existing = configurationRepository.findByConfigTypeAndActive(config.getConfigType(), true);
        if (existing.isPresent()) {
            config.setVersion(existing.get().getVersion() + 1);
        }

        AnalyzerConfiguration saved = configurationRepository.save(config);
        log.info("Saved configuration: {} (version: {})", config.getConfigType(), saved.getVersion());
        return saved;
    }

    /**
     * Initialize default configurations if they don't exist
     */
    public void initializeDefaultConfigurations() {
        initializeMappingAnnotations();
        initializeAnnotationToHttpMethod();
        initializeRestTemplateMethods();
        initializeWebClientHttpMethods();
        initializeKafkaConfigurations();
        initializeHttpUrlConnectionMethods();
        initializeRepositoryMethods();
        initializeAllowedAnalysisPackages();
    }

    private void initializeMappingAnnotations() {
        if (configurationRepository.findByConfigTypeAndActive("MAPPING_ANNOTATIONS", true).isEmpty()) {
            AnalyzerConfiguration config = AnalyzerConfiguration.builder()
                    .configType("MAPPING_ANNOTATIONS")
                    .stringSet(Set.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping"))
                    .description("Spring MVC mapping annotations")
                    .active(true)
                    .version(1L)
                    .build();
            configurationRepository.save(config);
            log.info("Initialized default MAPPING_ANNOTATIONS configuration");
        }
    }

    private void initializeAnnotationToHttpMethod() {
        if (configurationRepository.findByConfigTypeAndActive("ANNOTATION_TO_HTTP_METHOD", true).isEmpty()) {
            Map<String, String> mapping = Map.of(
                    "GetMapping", "GET",
                    "PostMapping", "POST",
                    "PutMapping", "PUT",
                    "DeleteMapping", "DELETE",
                    "PatchMapping", "PATCH",
                    "RequestMapping", "REQUEST"
            );
            AnalyzerConfiguration config = AnalyzerConfiguration.builder()
                    .configType("ANNOTATION_TO_HTTP_METHOD")
                    .stringMap(mapping)
                    .description("Mapping from Spring annotations to HTTP methods")
                    .active(true)
                    .version(1L)
                    .build();
            configurationRepository.save(config);
            log.info("Initialized default ANNOTATION_TO_HTTP_METHOD configuration");
        }
    }

    private void initializeRestTemplateMethods() {
        if (configurationRepository.findByConfigTypeAndActive("REST_TEMPLATE_METHODS", true).isEmpty()) {
            AnalyzerConfiguration config = AnalyzerConfiguration.builder()
                    .configType("REST_TEMPLATE_METHODS")
                    .stringSet(Set.of("getForObject", "getForEntity", "postForObject", "postForEntity", "put", "delete", "exchange"))
                    .description("RestTemplate HTTP methods")
                    .active(true)
                    .version(1L)
                    .build();
            configurationRepository.save(config);
            log.info("Initialized default REST_TEMPLATE_METHODS configuration");
        }
    }

    private void initializeWebClientHttpMethods() {
        if (configurationRepository.findByConfigTypeAndActive("WEBCLIENT_HTTP_METHODS", true).isEmpty()) {
            AnalyzerConfiguration config = AnalyzerConfiguration.builder()
                    .configType("WEBCLIENT_HTTP_METHODS")
                    .stringSet(Set.of("get", "post", "put", "delete", "patch", "method"))
                    .description("WebClient HTTP methods")
                    .active(true)
                    .version(1L)
                    .build();
            configurationRepository.save(config);
            log.info("Initialized default WEBCLIENT_HTTP_METHODS configuration");
        }
    }

    private void initializeKafkaConfigurations() {
        if (configurationRepository.findByConfigTypeAndActive("KAFKA_PRODUCER_METHODS", true).isEmpty()) {
            AnalyzerConfiguration config = AnalyzerConfiguration.builder()
                    .configType("KAFKA_PRODUCER_METHODS")
                    .stringSet(Set.of("send", "sendDefault"))
                    .description("Kafka producer methods")
                    .active(true)
                    .version(1L)
                    .build();
            configurationRepository.save(config);
            log.info("Initialized default KAFKA_PRODUCER_METHODS configuration");
        }

        if (configurationRepository.findByConfigTypeAndActive("KAFKA_PRODUCER_TYPES", true).isEmpty()) {
            AnalyzerConfiguration config = AnalyzerConfiguration.builder()
                    .configType("KAFKA_PRODUCER_TYPES")
                    .stringSet(Set.of("KafkaTemplate", "ReactiveKafkaProducerTemplate"))
                    .description("Kafka producer types")
                    .active(true)
                    .version(1L)
                    .build();
            configurationRepository.save(config);
            log.info("Initialized default KAFKA_PRODUCER_TYPES configuration");
        }
    }

    private void initializeHttpUrlConnectionMethods() {
        if (configurationRepository.findByConfigTypeAndActive("HTTP_URL_CONNECTION_METHODS", true).isEmpty()) {
            AnalyzerConfiguration config = AnalyzerConfiguration.builder()
                    .configType("HTTP_URL_CONNECTION_METHODS")
                    .stringSet(Set.of("openConnection", "setRequestMethod", "getInputStream", "getOutputStream", "connect"))
                    .description("HttpURLConnection methods that indicate HTTP calls")
                    .active(true)
                    .version(1L)
                    .build();
            configurationRepository.save(config);
            log.info("Initialized default HTTP_URL_CONNECTION_METHODS configuration");
        }
    }

    private void initializeRepositoryMethods() {
        if (configurationRepository.findByConfigTypeAndActive("REPOSITORY_WRITE_METHODS", true).isEmpty()) {
            AnalyzerConfiguration config = AnalyzerConfiguration.builder()
                    .configType("REPOSITORY_WRITE_METHODS")
                    .stringSet(Set.of("save", "saveAll", "saveAndFlush", "saveAllAndFlush",
                            "delete", "deleteAll", "deleteById", "deleteAllById", "deleteInBatch", "deleteAllInBatch",
                            "insert", "update", "upsert"))
                    .description("Repository methods that perform write operations")
                    .active(true)
                    .version(1L)
                    .build();
            configurationRepository.save(config);
            log.info("Initialized default REPOSITORY_WRITE_METHODS configuration");
        }

        if (configurationRepository.findByConfigTypeAndActive("REPOSITORY_READ_METHODS", true).isEmpty()) {
            AnalyzerConfiguration config = AnalyzerConfiguration.builder()
                    .configType("REPOSITORY_READ_METHODS")
                    .stringSet(Set.of("findById", "findAll", "findAllById", "existsById", "count",
                            "getById", "getReferenceById", "getOne"))
                    .description("Repository methods that perform read operations")
                    .active(true)
                    .version(1L)
                    .build();
            configurationRepository.save(config);
            log.info("Initialized default REPOSITORY_READ_METHODS configuration");
        }
    }

    private void initializeAllowedAnalysisPackages() {
        if (configurationRepository.findByConfigTypeAndActive("ALLOWED_ANALYSIS_PACKAGES", true).isEmpty()) {
            AnalyzerConfiguration config = AnalyzerConfiguration.builder()
                    .configType("ALLOWED_ANALYSIS_PACKAGES")
                    .stringSet(Set.of("org.springframework.web.client", "com.architecture.memory.orkestify"))
                    .description("Packages that are allowed for analysis even if they would normally be filtered as standard types")
                    .active(true)
                    .version(1L)
                    .build();
            configurationRepository.save(config);
            log.info("Initialized default ALLOWED_ANALYSIS_PACKAGES configuration");
        }
    }

    /**
     * Get string set configuration by type
     */
    private Set<String> getStringSet(String configType) {
        Optional<AnalyzerConfiguration> config = configurationRepository.findByConfigTypeAndActive(configType, true);
        if (config.isPresent() && config.get().getStringSet() != null) {
            return config.get().getStringSet();
        }

        log.warn("Configuration {} not found or inactive, using empty set", configType);
        return Collections.emptySet();
    }

    /**
     * Get string map configuration by type
     */
    private Map<String, String> getStringMap(String configType) {
        Optional<AnalyzerConfiguration> config = configurationRepository.findByConfigTypeAndActive(configType, true);
        if (config.isPresent() && config.get().getStringMap() != null) {
            return config.get().getStringMap();
        }

        log.warn("Configuration {} not found or inactive, using empty map", configType);
        return Collections.emptyMap();
    }
}
