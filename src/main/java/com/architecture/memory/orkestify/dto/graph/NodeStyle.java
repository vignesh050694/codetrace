package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Visual styling hints for graph nodes.
 * Used by visualization libraries for rendering.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeStyle {

    private String color;       // Hex color (e.g., "#4CAF50") or named color
    private Integer size;       // Relative size for the node
    private String shape;       // circle, rectangle, diamond, hexagon
    private String icon;        // Icon name for UI (e.g., "controller", "service", "kafka")
    private String borderColor; // Border color for the node
    private Integer borderWidth; // Border width in pixels
}
