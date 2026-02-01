package com.architecture.memory.orkestify.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfig {
    // Async configuration is enabled
    // Methods annotated with @Async will be executed in a thread pool
}
