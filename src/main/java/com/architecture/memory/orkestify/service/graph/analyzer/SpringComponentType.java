package com.architecture.memory.orkestify.service.graph.analyzer;

/**
 * Enumeration of Spring component types detected during code analysis.
 */
public enum SpringComponentType {
    CONTROLLER,
    REST_CONTROLLER,
    SERVICE,
    REPOSITORY,
    COMPONENT,
    CONFIGURATION,
    KAFKA_LISTENER,
    FEIGN_CLIENT,
    UNKNOWN
}
