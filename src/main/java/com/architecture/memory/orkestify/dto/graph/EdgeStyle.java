package com.architecture.memory.orkestify.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Visual styling hints for graph edges.
 * Used by visualization libraries for rendering.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EdgeStyle {

    private String color;       // Hex color or named color
    private Integer width;      // Line width in pixels
    private String lineStyle;   // solid, dashed, dotted
    private Boolean animated;   // Whether to animate the edge (for data flow visualization)
    private String arrowShape;  // triangle, circle, none
}
