package com.architecture.memory.orkestify.dto.graph;

/**
 * Enum representing the different types of nodes in the code architecture graph.
 */
public enum NodeType {
    APPLICATION("Application"),
    CONTROLLER("Controller"),
    ENDPOINT("Endpoint"),
    SERVICE("Service"),
    METHOD("Method"),
    REPOSITORY("RepositoryClass"),
    DATABASE_TABLE("DatabaseTable"),
    KAFKA_TOPIC(""),
    KAFKA_LISTENER("");

    private final String value;
    NodeType(String value) {
        this.value = value;
    }

    /**
     * Get the enum value from a string, case-insensitive.
     */
    public static NodeType fromString(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.toUpperCase().replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
