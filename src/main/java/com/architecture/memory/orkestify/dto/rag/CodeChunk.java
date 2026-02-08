package com.architecture.memory.orkestify.dto.rag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeChunk {
    private CodeChunkId id;
    private String text;
    private String originalNodeId;
    private String nodeType;
    private String projectId;
    private String appKey;
    private String className;
    private String methodName;
    private String packageName;
    private String signature;
}
