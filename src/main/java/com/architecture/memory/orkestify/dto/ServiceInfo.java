package com.architecture.memory.orkestify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceInfo {
    private String className;
    private String packageName;
    private LineRange line;
    private List<MethodInfo> methods;
    /**
     * List of interface names that this service class implements.
     * Used to resolve method calls through interfaces to their implementations.
     */
    private List<String> implementedInterfaces;
}
