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
public class ResponseTypeInfo {
    private String type;              // Fully qualified class name
    private String simpleTypeName;    // e.g., "SemesterMark"
    private List<String> fields;      // Field names
    private boolean isCollection;     // Is it a List/Collection?
    private int statusCode;           // HTTP status code (200, 201, etc.) - default 200
}
