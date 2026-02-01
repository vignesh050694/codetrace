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
public class RepositoryInfo {
    private String className;
    private String packageName;
    private String repositoryType;
    private String extendsClass;
    private LineRange line;
    private List<MethodInfo> methods;
    private DatabaseOperationInfo databaseOperations;
}


