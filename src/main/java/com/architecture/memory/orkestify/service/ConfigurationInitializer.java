package com.architecture.memory.orkestify.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Initializes default analyzer configurations in MongoDB on application startup.
 * This ensures that the analyzer has the necessary configuration data to function properly.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ConfigurationInitializer implements CommandLineRunner {

    private final AnalyzerConfigurationService configService;

    @Override
    public void run(String... args) throws Exception {
        log.info("Initializing analyzer configurations...");
        configService.initializeDefaultConfigurations();
        log.info("Analyzer configurations initialized successfully");
    }
}
