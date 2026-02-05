package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a relationship change between production and shadow graphs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationshipChange {

    public enum ChangeType {
        ADDED,
        REMOVED
    }

    private ChangeType changeType;
    private String relationshipType; // CALLS, MAKES_EXTERNAL_CALL, PRODUCES_TO, etc.
    private String sourceNodeKey;
    private String sourceNodeType;
    private String targetNodeKey;
    private String targetNodeType;
    private String displayDescription; // e.g., "UserService.createUser() -> UserRepository.save()"
}
