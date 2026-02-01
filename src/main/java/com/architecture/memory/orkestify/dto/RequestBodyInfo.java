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
public class RequestBodyInfo {
    private String type;              // Fully qualified class name
    private String simpleTypeName;    // e.g., "SemesterMark"
    private List<String> fields;      // Field names
    private boolean isCollection;     // Is it a List/Collection?
    private boolean isWrapper;        // Is it wrapped in RequestBody annotation?
}
