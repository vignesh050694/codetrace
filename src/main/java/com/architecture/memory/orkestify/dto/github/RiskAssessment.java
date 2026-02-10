package com.architecture.memory.orkestify.dto.github;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Risk assessment for a pull request based on architectural impact analysis.
 * Provides a comprehensive risk score and breakdown to help with merge decisions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessment {

    /**
     * Overall risk level: LOW, MEDIUM, HIGH, CRITICAL
     */
    private RiskLevel overallRiskLevel;

    /**
     * Overall risk score (0-100)
     */
    private int overallScore;

    /**
     * Score breakdown by category
     */
    private ScoreBreakdown scoreBreakdown;

    /**
     * Risk factors categorized by severity
     */
    @Builder.Default
    private List<RiskFactor> riskFactors = new ArrayList<>();

    /**
     * Recommended actions before merging
     */
    @Builder.Default
    private List<RecommendedAction> recommendedActions = new ArrayList<>();

    /**
     * Deployment strategy recommendation
     */
    private DeploymentStrategy deploymentStrategy;

    /**
     * Merge status based on risk assessment
     */
    private String mergeStatus;

    /**
     * Risk level enumeration
     */
    public enum RiskLevel {
        LOW("🟢", "LOW"),
        MEDIUM("🟡", "MEDIUM"),
        HIGH("🟠", "HIGH"),
        CRITICAL("🔴", "CRITICAL");

        private final String emoji;
        private final String label;

        RiskLevel(String emoji, String label) {
            this.emoji = emoji;
            this.label = label;
        }

        public String getEmoji() {
            return emoji;
        }

        public String getLabel() {
            return label;
        }
    }

    /**
     * Score breakdown by category
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoreBreakdown {
        private int breakingChanges;        // Max 40 points
        private int downstreamImpact;       // Max 30 points
        private int serviceCriticality;     // Max 20 points
        private int changeComplexity;       // Max 10 points
    }

    /**
     * Individual risk factor with severity and description
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskFactor {
        private Severity severity;
        private String description;

        public enum Severity {
            HIGH("🔴", "HIGH SEVERITY"),
            MEDIUM("🟡", "MEDIUM SEVERITY"),
            LOW("🟢", "LOW SEVERITY");

            private final String emoji;
            private final String label;

            Severity(String emoji, String label) {
                this.emoji = emoji;
                this.label = label;
            }

            public String getEmoji() {
                return emoji;
            }

            public String getLabel() {
                return label;
            }
        }
    }

    /**
     * Recommended action to take before merging
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecommendedAction {
        private String description;
        private Category category;

        public enum Category {
            BEFORE_MERGING,
            DEPLOYMENT_STRATEGY
        }
    }

    /**
     * Deployment strategy recommendation
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeploymentStrategy {
        private String recommendation;
        private boolean featureFlagRequired;
        private String reasoning;
        @Builder.Default
        private List<String> notes = new ArrayList<>();
    }
}
