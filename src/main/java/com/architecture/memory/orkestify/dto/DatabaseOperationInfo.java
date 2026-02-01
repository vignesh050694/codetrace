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
public class DatabaseOperationInfo {
    private String className;           // Repository class name
    private String packageName;         // Repository package
    private String entityClass;         // Entity class (full name)
    private String entitySimpleName;    // Entity simple name
    private String tableName;           // Table/Collection name
    private String tableSource;         // Where table name came from (@Table, @Document, or class name)
    private String databaseType;        // JPA, MongoDB, etc.
    private List<String> operations;    // READ, WRITE, DELETE, UPDATE
    private LineRange line;
}
