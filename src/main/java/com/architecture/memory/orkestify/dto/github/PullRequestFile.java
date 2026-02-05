package com.architecture.memory.orkestify.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A file changed in a pull request, from GitHub's List pull request files API.
 * GET /repos/{owner}/{repo}/pulls/{pull_number}/files
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PullRequestFile {

    private String sha;
    private String filename;    // e.g., "src/main/java/com/example/MyService.java"
    private String status;      // added, removed, modified, renamed, copied, changed
    private int additions;
    private int deletions;
    private int changes;
    private String patch;       // Unified diff patch (may be absent for binary files)

    @JsonProperty("previous_filename")
    private String previousFilename;  // Set when status is "renamed"

    /**
     * Check if this is a Java source file.
     */
    public boolean isJavaFile() {
        return filename != null && filename.endsWith(".java");
    }

    /**
     * Check if this is a main source file (not test).
     */
    public boolean isMainSource() {
        return filename != null
                && filename.contains("src/main/java/")
                && !filename.contains("src/test/");
    }

    /**
     * Extract the fully qualified class name from the file path.
     * e.g., "src/main/java/com/example/service/MyService.java" -> "com.example.service.MyService"
     */
    public String extractClassName() {
        if (filename == null) return null;

        String path = filename;
        int srcIdx = path.indexOf("src/main/java/");
        if (srcIdx >= 0) {
            path = path.substring(srcIdx + "src/main/java/".length());
        }
        if (path.endsWith(".java")) {
            path = path.substring(0, path.length() - 5);
        }
        return path.replace('/', '.');
    }

    /**
     * Extract just the simple class name.
     * e.g., "src/main/java/com/example/service/MyService.java" -> "MyService"
     */
    public String extractSimpleClassName() {
        String fqn = extractClassName();
        if (fqn == null) return null;
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
    }

    /**
     * Extract the package name.
     * e.g., "src/main/java/com/example/service/MyService.java" -> "com.example.service"
     */
    public String extractPackageName() {
        String fqn = extractClassName();
        if (fqn == null) return null;
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(0, lastDot) : "";
    }
}
