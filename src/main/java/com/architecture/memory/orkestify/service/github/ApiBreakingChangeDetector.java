package com.architecture.memory.orkestify.service.github;

import com.architecture.memory.orkestify.dto.github.PullRequestFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects breaking API changes in code diffs, particularly:
 * - Changes to REST client endpoint URLs (RestTemplate, WebClient, Feign)
 * - Modifications to API path constants
 * - Changes to service-to-service communication endpoints
 */
@Service
@Slf4j
public class ApiBreakingChangeDetector {

    // Pattern to match API endpoint URLs in strings
    // Matches patterns like: "/api/users/", "/v1/orders/{id}", "api/products/search"
    private static final Pattern API_PATH_PATTERN = Pattern.compile(
            "\"([/]?api[/][^\"]*?)\"",
            Pattern.CASE_INSENSITIVE
    );

    // Alternative patterns for path segments
    private static final Pattern PATH_SEGMENT_PATTERN = Pattern.compile(
            "\"(/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_/{}]*?)\"",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern to detect HTTP method calls with URLs
    // Matches: restTemplate.getForObject("url", ...), webClient.get().uri("url", ...), etc.
    private static final Pattern HTTP_CLIENT_PATTERN = Pattern.compile(
            "(restTemplate|webClient|httpClient|feignClient|restClient)" +
            "\\.(get|post|put|delete|patch|exchange|retrieve)" +
            ".*?[\"']([/][^\"']*?)[\"']",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern for Feign client method annotations
    private static final Pattern FEIGN_ANNOTATION_PATTERN = Pattern.compile(
            "@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)" +
            "\\([\"']([/][^\"']*?)[\"']",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Analyze changed files to detect breaking API URL changes
     */
    public List<ApiUrlChange> detectApiUrlChanges(List<PullRequestFile> changedFiles) {
        List<ApiUrlChange> changes = new ArrayList<>();

        for (PullRequestFile file : changedFiles) {
            if (file.getPatch() == null || file.getPatch().isEmpty()) {
                continue;
            }

            // Skip non-Java files
            if (!file.isJavaFile()) {
                continue;
            }

            List<ApiUrlChange> fileChanges = analyzeFileDiff(file);
            changes.addAll(fileChanges);
        }

        log.info("Detected {} API URL changes", changes.size());
        return changes;
    }

    /**
     * Analyze a single file's diff for API URL changes
     */
    private List<ApiUrlChange> analyzeFileDiff(PullRequestFile file) {
        List<ApiUrlChange> changes = new ArrayList<>();
        String patch = file.getPatch();
        String className = file.extractSimpleClassName();

        // Parse diff line by line
        String[] lines = patch.split("\n");
        String removedUrl = null;
        String addedUrl = null;
        int lineNumber = 0;

        for (String line : lines) {
            lineNumber++;

            // Check for removed lines (starting with -)
            if (line.startsWith("-") && !line.startsWith("---")) {
                List<String> urls = extractUrlsFromLine(line.substring(1));
                if (!urls.isEmpty()) {
                    removedUrl = urls.get(0);
                }
            }

            // Check for added lines (starting with +)
            if (line.startsWith("+") && !line.startsWith("+++")) {
                List<String> urls = extractUrlsFromLine(line.substring(1));
                if (!urls.isEmpty()) {
                    addedUrl = urls.get(0);
                }
            }

            // If we found both a removed and added URL on consecutive lines, it's a change
            if (removedUrl != null && addedUrl != null && !removedUrl.equals(addedUrl)) {
                changes.add(ApiUrlChange.builder()
                        .fileName(file.getFilename())
                        .className(className)
                        .oldUrl(removedUrl)
                        .newUrl(addedUrl)
                        .lineNumber(lineNumber)
                        .changeType(determineChangeType(removedUrl, addedUrl))
                        .build());

                log.info("Detected API URL change in {}: '{}' -> '{}'",
                        className, removedUrl, addedUrl);

                removedUrl = null;
                addedUrl = null;
            }
        }

        return changes;
    }

    /**
     * Extract API URLs from a line of code
     */
    private List<String> extractUrlsFromLine(String line) {
        List<String> urls = new ArrayList<>();

        // Try API path pattern
        Matcher apiMatcher = API_PATH_PATTERN.matcher(line);
        while (apiMatcher.find()) {
            urls.add(apiMatcher.group(1));
        }

        // Try path segment pattern (catches /users/roll/ style paths)
        if (urls.isEmpty()) {
            Matcher pathMatcher = PATH_SEGMENT_PATTERN.matcher(line);
            while (pathMatcher.find()) {
                String path = pathMatcher.group(1);
                // Filter to only paths that look like API endpoints
                if (path.split("/").length >= 2) {
                    urls.add(path);
                }
            }
        }

        // Try HTTP client pattern
        if (urls.isEmpty()) {
            Matcher httpMatcher = HTTP_CLIENT_PATTERN.matcher(line);
            while (httpMatcher.find()) {
                urls.add(httpMatcher.group(3));
            }
        }

        // Try Feign annotation pattern
        if (urls.isEmpty()) {
            Matcher feignMatcher = FEIGN_ANNOTATION_PATTERN.matcher(line);
            while (feignMatcher.find()) {
                urls.add(feignMatcher.group(2));
            }
        }

        return urls;
    }

    /**
     * Determine the type of change based on old and new URLs
     */
    private ChangeType determineChangeType(String oldUrl, String newUrl) {
        // Complete path change
        if (!oldUrl.contains("/") || !newUrl.contains("/")) {
            return ChangeType.COMPLETE_REWRITE;
        }

        // Path structure change (different segments)
        String[] oldSegments = oldUrl.split("/");
        String[] newSegments = newUrl.split("/");

        if (oldSegments.length != newSegments.length) {
            return ChangeType.PATH_STRUCTURE_CHANGE;
        }

        // Check how many segments differ
        int differingSegments = 0;
        for (int i = 0; i < oldSegments.length; i++) {
            if (!oldSegments[i].equals(newSegments[i])) {
                differingSegments++;
            }
        }

        if (differingSegments == 0) {
            return ChangeType.MINOR_CHANGE;
        } else if (differingSegments == 1 && differingSegments < oldSegments.length - 1) {
            return ChangeType.PATH_SEGMENT_CHANGE;
        } else {
            return ChangeType.PATH_STRUCTURE_CHANGE;
        }
    }

    /**
     * Data class representing an API URL change
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ApiUrlChange {
        private String fileName;
        private String className;
        private String oldUrl;
        private String newUrl;
        private int lineNumber;
        private ChangeType changeType;

        /**
         * Get a human-readable description of this change
         */
        public String getDescription() {
            return String.format("API endpoint changed in %s: '%s' → '%s'",
                    className, oldUrl, newUrl);
        }

        /**
         * Determine severity based on change type
         */
        public Severity getSeverity() {
            return switch (changeType) {
                case COMPLETE_REWRITE, PATH_STRUCTURE_CHANGE -> Severity.HIGH;
                case PATH_SEGMENT_CHANGE -> Severity.HIGH;
                case MINOR_CHANGE -> Severity.MEDIUM;
            };
        }

        /**
         * Get the breaking changes points for this URL change
         * Used for risk scoring
         */
        public int getBreakingChangesPoints() {
            return switch (changeType) {
                case COMPLETE_REWRITE -> 35;        // Complete URL rewrite
                case PATH_STRUCTURE_CHANGE -> 30;   // Structure changed (segments added/removed)
                case PATH_SEGMENT_CHANGE -> 25;     // Individual segment changed
                case MINOR_CHANGE -> 15;            // Minor modification
            };
        }
    }

    /**
     * Type of URL change
     */
    public enum ChangeType {
        COMPLETE_REWRITE,           // Entire URL changed
        PATH_STRUCTURE_CHANGE,      // Number of path segments changed
        PATH_SEGMENT_CHANGE,        // Individual segment changed
        MINOR_CHANGE                // Minor modification
    }

    /**
     * Severity of the change
     */
    public enum Severity {
        HIGH,
        MEDIUM,
        LOW
    }
}
