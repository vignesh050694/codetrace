package com.architecture.memory.orkestify.service.graph.analyzer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a dependency injected into a Spring component via:
 * - Constructor injection (most common in @RequiredArgsConstructor + final fields)
 * - @Autowired field injection
 * - Setter injection
 *
 * Tracks the declared type (may be an interface) and the resolved concrete type.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InjectedDependency {
    private String fieldName;

    // The type declared in the field/constructor param (may be interface)
    private String declaredTypeSimple;
    private String declaredTypeQualified;

    // The resolved concrete implementation type (resolved after Pass 1)
    private String resolvedTypeSimple;
    private String resolvedTypeQualified;

    // How the injection happens
    private InjectionType injectionType;

    public enum InjectionType {
        CONSTRUCTOR,
        FIELD_AUTOWIRED,
        SETTER
    }
}
