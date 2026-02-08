package com.architecture.memory.orkestify.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unique identifier for a code chunk in the RAG system.
 * Combines project, node, and granularity level.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeChunkId {

    /**
     * Project identifier
     */
    private String projectId;

    /**
     * Neo4j node ID (UUID from graph)
     */
    private String nodeId;

    /**
     * Granularity level: METHOD, CLASS, SERVICE, CONTROLLER, REPOSITORY, etc.
     */
    private String level;

    @Override
    public String toString() {
        return String.format("%s::%s::%s", projectId, level, nodeId);
    }

    /**
     * Parse from string representation
     */
    public static CodeChunkId fromString(String str) {
        String[] parts = str.split("::");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid CodeChunkId format: " + str);
        }
        return new CodeChunkId(parts[0], parts[2], parts[1]);
    }
}
