package com.architecture.memory.orkestify.controller;

import com.architecture.memory.orkestify.model.AnalyzerConfiguration;
import com.architecture.memory.orkestify.service.AnalyzerConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for managing analyzer configurations.
 * Allows dynamic configuration changes without code modifications.
 */
@RestController
@RequestMapping("/api/configurations")
@Slf4j
@RequiredArgsConstructor
public class ConfigurationController {

    private final AnalyzerConfigurationService configService;

    /**
     * Get all active configurations
     */
    @GetMapping
    public ResponseEntity<List<AnalyzerConfiguration>> getAllConfigurations() {
        List<AnalyzerConfiguration> configs = configService.getAllActiveConfigurations();
        return ResponseEntity.ok(configs);
    }

    /**
     * Get configuration by type
     */
    @GetMapping("/{configType}")
    public ResponseEntity<AnalyzerConfiguration> getConfiguration(@PathVariable String configType) {
        return configService.getConfigurationByType(configType)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create or update a configuration
     */
    @PostMapping
    public ResponseEntity<AnalyzerConfiguration> saveConfiguration(@RequestBody AnalyzerConfiguration config) {
        try {
            AnalyzerConfiguration saved = configService.saveConfiguration(config);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("Failed to save configuration: {}", config.getConfigType(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Update specific configuration values
     */
    @PatchMapping("/{configType}")
    public ResponseEntity<AnalyzerConfiguration> updateConfiguration(
            @PathVariable String configType,
            @RequestBody AnalyzerConfiguration updates) {

        try {
            AnalyzerConfiguration updated = configService.updateConfiguration(configType, updates);
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            log.error("Failed to update configuration: {}", configType, e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete a configuration (soft delete by setting active=false)
     */
    @DeleteMapping("/{configType}")
    public ResponseEntity<Void> deleteConfiguration(@PathVariable String configType) {
        try {
            configService.deactivateConfiguration(configType);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Failed to delete configuration: {}", configType, e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Reinitialize default configurations
     */
    @PostMapping("/initialize")
    public ResponseEntity<Void> initializeDefaults() {
        try {
            configService.initializeDefaultConfigurations();
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to initialize default configurations", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
