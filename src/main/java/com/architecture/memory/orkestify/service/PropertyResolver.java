package com.architecture.memory.orkestify.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class PropertyResolver {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final Pattern DEFAULT_VALUE_PATTERN = Pattern.compile("\\$\\{([^:}]+):([^}]+)\\}");

    /**
     * Load properties from application.yaml and application.properties in repository
     */
    public Map<String, String> loadProperties(Path repositoryPath) {
        Map<String, String> properties = new HashMap<>();

        // Search for application.yaml, application.yml, and application.properties
        try {
            Files.walk(repositoryPath)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.equals("application.yaml") ||
                               fileName.equals("application.yml") ||
                               fileName.equals("application.properties");
                    })
                    .forEach(configFile -> {
                        log.info("Found configuration file: {}", configFile);
                        if (configFile.toString().endsWith(".properties")) {
                            properties.putAll(loadPropertiesFile(configFile));
                        } else {
                            properties.putAll(loadYamlFile(configFile));
                        }
                    });
        } catch (IOException e) {
            log.warn("Error scanning for configuration files: {}", e.getMessage());
        }

        log.info("Loaded {} properties from configuration files", properties.size());
        return properties;
    }

    /**
     * Load properties from .properties file
     */
    private Map<String, String> loadPropertiesFile(Path propertiesFile) {
        Map<String, String> props = new HashMap<>();
        Properties properties = new Properties();

        try (FileReader reader = new FileReader(propertiesFile.toFile())) {
            properties.load(reader);
            for (String key : properties.stringPropertyNames()) {
                props.put(key, properties.getProperty(key));
            }
            log.debug("Loaded {} properties from {}", props.size(), propertiesFile.getFileName());
        } catch (IOException e) {
            log.warn("Failed to load properties file {}: {}", propertiesFile, e.getMessage());
        }

        return props;
    }

    /**
     * Load properties from YAML file
     */
    private Map<String, String> loadYamlFile(Path yamlFile) {
        Map<String, String> flatProps = new HashMap<>();

        try (FileInputStream fis = new FileInputStream(yamlFile.toFile())) {
            Yaml yaml = new Yaml();
            Map<String, Object> yamlMap = yaml.load(fis);

            if (yamlMap != null) {
                flattenYaml("", yamlMap, flatProps);
            }

            log.debug("Loaded {} properties from {}", flatProps.size(), yamlFile.getFileName());
        } catch (IOException e) {
            log.warn("Failed to load YAML file {}: {}", yamlFile, e.getMessage());
        }

        return flatProps;
    }

    /**
     * Flatten nested YAML structure to dot-notation properties
     * Example: {kafka: {topic: {marks: "marks-topic"}}} -> kafka.topic.marks=marks-topic
     */
    private void flattenYaml(String prefix, Map<String, Object> map, Map<String, String> result) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                flattenYaml(key, nestedMap, result);
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> nestedMap = (Map<String, Object>) item;
                        flattenYaml(key + "[" + i + "]", nestedMap, result);
                    } else {
                        result.put(key + "[" + i + "]", String.valueOf(item));
                    }
                }
            } else if (value != null) {
                result.put(key, String.valueOf(value));
            }
        }
    }

    /**
     * Resolve a property placeholder like ${kafka.topic.marks}
     * Supports default values: ${kafka.topic.marks:default-topic}
     */
    public String resolveProperty(String placeholder, Map<String, String> properties) {
        if (placeholder == null || placeholder.isEmpty()) {
            return placeholder;
        }

        // Remove ${ and } if present
        String cleaned = placeholder.trim();
        if (cleaned.startsWith("${") && cleaned.endsWith("}")) {
            cleaned = cleaned.substring(2, cleaned.length() - 1);
        }

        // Check for default value pattern: key:defaultValue
        Matcher defaultMatcher = Pattern.compile("([^:]+):(.+)").matcher(cleaned);
        if (defaultMatcher.matches()) {
            String key = defaultMatcher.group(1).trim();
            String defaultValue = defaultMatcher.group(2).trim();

            String resolved = properties.get(key);
            if (resolved != null && !resolved.isEmpty()) {
                return resolved;
            }
            return defaultValue;
        }

        // Simple key lookup
        String resolved = properties.get(cleaned);
        if (resolved != null && !resolved.isEmpty()) {
            return resolved;
        }

        // Return original placeholder if not found
        return placeholder;
    }

    /**
     * Resolve all placeholders in a string
     * Example: "${user.service.url}/api/users" -> "http://localhost:8081/api/users"
     */
    public String resolveAllPlaceholders(String text, Map<String, String> properties) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String placeholder = matcher.group(0); // Full ${...}
            String resolved = resolveProperty(placeholder, properties);

            // Escape special regex characters in replacement
            String replacement = Matcher.quoteReplacement(resolved);
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Extract property key from @Value annotation value
     * Example: @Value("${kafka.topic.marks}") -> "kafka.topic.marks"
     */
    public String extractPropertyKey(String valueAnnotationValue) {
        if (valueAnnotationValue == null || valueAnnotationValue.isEmpty()) {
            return null;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(valueAnnotationValue);
        if (matcher.find()) {
            String content = matcher.group(1); // Content inside ${}

            // Handle default value syntax: key:defaultValue
            if (content.contains(":")) {
                return content.split(":")[0].trim();
            }

            return content.trim();
        }

        return null;
    }

    /**
     * Check if a string contains property placeholders
     */
    public boolean hasPlaceholders(String text) {
        return text != null && PLACEHOLDER_PATTERN.matcher(text).find();
    }

    /**
     * Get all property keys referenced in a string
     */
    public Set<String> extractAllPropertyKeys(String text) {
        Set<String> keys = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return keys;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            String key = extractPropertyKey(matcher.group(0));
            if (key != null) {
                keys.add(key);
            }
        }

        return keys;
    }
}
