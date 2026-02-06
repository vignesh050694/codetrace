package com.architecture.memory.orkestify.service.graph.analyzer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A @Bean method found in a @Configuration class.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedBean {
    private String beanName;
    private String methodName;
    private String returnType;
    private int lineStart;
    private int lineEnd;
}
