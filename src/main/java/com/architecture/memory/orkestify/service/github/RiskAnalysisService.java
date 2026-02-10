package com.architecture.memory.orkestify.service.github;

import com.architecture.memory.orkestify.dto.github.ImpactReport;
import com.architecture.memory.orkestify.dto.github.RiskAssessment;
import com.architecture.memory.orkestify.dto.github.RiskAssessment.*;
import com.architecture.memory.orkestify.dto.graph.CircularDependency;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service to analyze architectural impact and generate risk assessments for pull requests.
 * Calculates risk scores based on breaking changes, downstream impact, criticality, and complexity.
 */
@Service
@Slf4j
public class RiskAnalysisService {

    /**
     * Analyze the impact report and generate a comprehensive risk assessment
     */
    public RiskAssessment assessRisk(ImpactReport report) {
        log.info("Assessing risk for PR #{}", report.getPrNumber());

        ScoreBreakdown breakdown = calculateScoreBreakdown(report);
        int totalScore = calculateTotalScore(breakdown);
        RiskLevel riskLevel = determineRiskLevel(totalScore);

        List<RiskFactor> riskFactors = identifyRiskFactors(report, breakdown);
        List<RecommendedAction> actions = generateRecommendedActions(report, riskLevel);
        DeploymentStrategy deploymentStrategy = determineDeploymentStrategy(report, riskLevel);
        String mergeStatus = determineMergeStatus(report, riskLevel);

        return RiskAssessment.builder()
                .overallRiskLevel(riskLevel)
                .overallScore(totalScore)
                .scoreBreakdown(breakdown)
                .riskFactors(riskFactors)
                .recommendedActions(actions)
                .deploymentStrategy(deploymentStrategy)
                .mergeStatus(mergeStatus)
                .build();
    }

    /**
     * Calculate score breakdown by category
     */
    private ScoreBreakdown calculateScoreBreakdown(ImpactReport report) {
        int breakingChanges = calculateBreakingChangesScore(report);
        int downstreamImpact = calculateDownstreamImpactScore(report);
        int serviceCriticality = calculateServiceCriticalityScore(report);
        int changeComplexity = calculateChangeComplexityScore(report);

        return ScoreBreakdown.builder()
                .breakingChanges(breakingChanges)
                .downstreamImpact(downstreamImpact)
                .serviceCriticality(serviceCriticality)
                .changeComplexity(changeComplexity)
                .build();
    }

    /**
     * Calculate breaking changes score (0-40 points)
     * Higher score = more breaking changes
     */
    private int calculateBreakingChangesScore(ImpactReport report) {
        int score = 0;

        // CRITICAL: API URL changes in service clients (service-to-service communication)
        // This is the MOST CRITICAL type of breaking change
        if (!report.getApiUrlChanges().isEmpty()) {
            // Each API URL change is worth 15 points (can max out the 40 with just 3 changes)
            score += Math.min(report.getApiUrlChanges().size() * 15, 35); // Up to 35 points
        }

        // Controllers modified/removed (likely API changes)
        long controllerChanges = report.getChangedComponents().stream()
                .filter(c -> "Controller".equals(c.getType()))
                .filter(c -> "modified".equals(c.getFileStatus()) || "removed".equals(c.getFileStatus()))
                .count();
        score += Math.min(controllerChanges * 10, 25); // Up to 25 points

        // New circular dependencies (architectural issues)
        if (!report.getNewCircularDependencies().isEmpty()) {
            long errorDeps = report.getNewCircularDependencies().stream()
                    .filter(d -> d.getSeverity() == CircularDependency.Severity.ERROR)
                    .count();
            score += Math.min(errorDeps * 10, 15); // Up to 15 points
        }

        return Math.min(score, 40);
    }

    /**
     * Calculate downstream impact score (0-30 points)
     * Higher score = more services/components affected
     */
    private int calculateDownstreamImpactScore(ImpactReport report) {
        int score = 0;

        // Affected endpoints
        int endpointCount = report.getAffectedEndpoints().size();
        score += Math.min(endpointCount * 3, 15); // Up to 15 points

        // Affected flows
        int flowCount = report.getAffectedFlows().size();
        score += Math.min(flowCount * 2, 10); // Up to 10 points

        // Components with many upstream callers (high fan-in)
        long highImpactComponents = report.getChangedComponents().stream()
                .filter(c -> c.getUpstreamCallers().size() >= 3)
                .count();
        score += Math.min(highImpactComponents * 3, 5); // Up to 5 points

        return Math.min(score, 30);
    }

    /**
     * Calculate service criticality score (0-20 points)
     * Based on component types and architectural significance
     */
    private int calculateServiceCriticalityScore(ImpactReport report) {
        int score = 0;

        // Core services changed (Service and Repository layers)
        long serviceChanges = report.getChangedComponents().stream()
                .filter(c -> "Service".equals(c.getType()) || "Repository".equals(c.getType()))
                .count();
        score += Math.min(serviceChanges * 4, 12); // Up to 12 points

        // Kafka listeners (event-driven architecture)
        long kafkaChanges = report.getChangedComponents().stream()
                .filter(c -> "KafkaListener".equals(c.getType()))
                .count();
        score += Math.min(kafkaChanges * 8, 8); // Up to 8 points (higher weight)

        return Math.min(score, 20);
    }

    /**
     * Calculate change complexity score (0-10 points)
     * Based on number of files and lines changed
     */
    private int calculateChangeComplexityScore(ImpactReport report) {
        int score = 0;

        // Number of architecture components changed
        long componentCount = report.getChangedComponents().stream()
                .filter(c -> !"Other".equals(c.getType()))
                .count();
        score += Math.min(componentCount / 2, 5); // Up to 5 points

        // Total lines changed
        int totalLinesChanged = report.getChangedComponents().stream()
                .mapToInt(c -> c.getLinesAdded() + c.getLinesRemoved())
                .sum();
        if (totalLinesChanged > 500) {
            score += 5;
        } else if (totalLinesChanged > 250) {
            score += 3;
        } else if (totalLinesChanged > 100) {
            score += 2;
        }

        return Math.min(score, 10);
    }

    /**
     * Calculate total risk score
     */
    private int calculateTotalScore(ScoreBreakdown breakdown) {
        return breakdown.getBreakingChanges() +
                breakdown.getDownstreamImpact() +
                breakdown.getServiceCriticality() +
                breakdown.getChangeComplexity();
    }

    /**
     * Determine overall risk level based on score
     */
    private RiskLevel determineRiskLevel(int score) {
        if (score >= 70) return RiskLevel.CRITICAL;
        if (score >= 50) return RiskLevel.HIGH;
        if (score >= 25) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    /**
     * Identify and categorize risk factors
     */
    private List<RiskFactor> identifyRiskFactors(ImpactReport report, ScoreBreakdown breakdown) {
        List<RiskFactor> factors = new ArrayList<>();

        // HIGH SEVERITY factors

        // CRITICAL: API URL changes
        if (!report.getApiUrlChanges().isEmpty()) {
            factors.add(RiskFactor.builder()
                    .severity(RiskFactor.Severity.HIGH)
                    .description("Service-to-service API endpoint URLs changed (" +
                            report.getApiUrlChanges().size() + " change(s)) - WILL break integration")
                    .build());
        }

        if (breakdown.getBreakingChanges() >= 20) {
            long controllerChanges = report.getChangedComponents().stream()
                    .filter(c -> "Controller".equals(c.getType()))
                    .filter(c -> "modified".equals(c.getFileStatus()))
                    .count();
            if (controllerChanges > 0) {
                factors.add(RiskFactor.builder()
                        .severity(RiskFactor.Severity.HIGH)
                        .description("API signature likely changed in " + controllerChanges + " controller(s)")
                        .build());
            }
        }

        if (!report.getNewCircularDependencies().isEmpty()) {
            long errorDeps = report.getNewCircularDependencies().stream()
                    .filter(d -> d.getSeverity() == CircularDependency.Severity.ERROR)
                    .count();
            if (errorDeps > 0) {
                factors.add(RiskFactor.builder()
                        .severity(RiskFactor.Severity.HIGH)
                        .description("Introduces " + errorDeps + " new circular dependency issues")
                        .build());
            }
        }

        // MEDIUM SEVERITY factors
        if (report.getAffectedEndpoints().size() >= 3) {
            factors.add(RiskFactor.builder()
                    .severity(RiskFactor.Severity.MEDIUM)
                    .description(report.getAffectedEndpoints().size() + " API endpoints affected")
                    .build());
        }

        long highImpactComponents = report.getChangedComponents().stream()
                .filter(c -> c.getUpstreamCallers().size() >= 3)
                .count();
        if (highImpactComponents > 0) {
            factors.add(RiskFactor.builder()
                    .severity(RiskFactor.Severity.MEDIUM)
                    .description(highImpactComponents + " component(s) with multiple upstream callers modified")
                    .build());
        }

        if (report.getAffectedFlows().size() >= 2) {
            factors.add(RiskFactor.builder()
                    .severity(RiskFactor.Severity.MEDIUM)
                    .description(report.getAffectedFlows().size() + " request flows require coordination")
                    .build());
        }

        // LOW SEVERITY factors
        if (report.getNewCircularDependencies().stream()
                .anyMatch(d -> d.getSeverity() == CircularDependency.Severity.WARNING)) {
            factors.add(RiskFactor.builder()
                    .severity(RiskFactor.Severity.LOW)
                    .description("Minor circular dependency warnings detected")
                    .build());
        }

        if (!report.getWarnings().isEmpty()) {
            factors.add(RiskFactor.builder()
                    .severity(RiskFactor.Severity.LOW)
                    .description(report.getWarnings().size() + " analysis warning(s)")
                    .build());
        }

        return factors;
    }

    /**
     * Generate recommended actions based on risk analysis
     */
    private List<RecommendedAction> generateRecommendedActions(ImpactReport report, RiskLevel riskLevel) {
        List<RecommendedAction> actions = new ArrayList<>();

        // Always recommend team review for affected components
        if (!report.getAffectedEndpoints().isEmpty()) {
            List<String> teams = determineAffectedTeams(report);
            if (!teams.isEmpty()) {
                actions.add(RecommendedAction.builder()
                        .category(RecommendedAction.Category.BEFORE_MERGING)
                        .description("Request review from " + String.join(", ", teams))
                        .build());
            }
        }

        // Integration tests for affected flows
        if (!report.getAffectedFlows().isEmpty()) {
            actions.add(RecommendedAction.builder()
                    .category(RecommendedAction.Category.BEFORE_MERGING)
                    .description("Run integration tests for affected request flows")
                    .build());
        }

        // Documentation updates for API changes
        long controllerChanges = report.getChangedComponents().stream()
                .filter(c -> "Controller".equals(c.getType()))
                .count();
        if (controllerChanges > 0) {
            actions.add(RecommendedAction.builder()
                    .category(RecommendedAction.Category.BEFORE_MERGING)
                    .description("Update API documentation for modified endpoints")
                    .build());
        }

        // Coordinate deployment for high/critical risk
        if (riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL) {
            actions.add(RecommendedAction.builder()
                    .category(RecommendedAction.Category.BEFORE_MERGING)
                    .description("Coordinate deployment timeline with affected teams")
                    .build());
        }

        // Handle circular dependencies
        if (!report.getNewCircularDependencies().isEmpty()) {
            actions.add(RecommendedAction.builder()
                    .category(RecommendedAction.Category.BEFORE_MERGING)
                    .description("Review and resolve circular dependencies")
                    .build());
        }

        return actions;
    }

    /**
     * Determine deployment strategy based on risk
     */
    private DeploymentStrategy determineDeploymentStrategy(ImpactReport report, RiskLevel riskLevel) {
        boolean requiresFeatureFlag = riskLevel == RiskLevel.CRITICAL ||
                (riskLevel == RiskLevel.HIGH && report.getAffectedEndpoints().size() >= 5);

        String recommendation;
        String reasoning;

        switch (riskLevel) {
            case CRITICAL:
                recommendation = "Phased rollout with canary deployment";
                reasoning = "High risk change requires gradual rollout";
                break;
            case HIGH:
                recommendation = requiresFeatureFlag ?
                        "Deploy behind feature flag with monitoring" :
                        "Standard deployment with enhanced monitoring";
                reasoning = "Significant architectural changes detected";
                break;
            case MEDIUM:
                recommendation = "Standard deployment with monitoring";
                reasoning = "Moderate impact on existing functionality";
                break;
            default:
                recommendation = "Standard deployment";
                reasoning = "Low risk change with minimal impact";
        }

        return DeploymentStrategy.builder()
                .recommendation(recommendation)
                .featureFlagRequired(requiresFeatureFlag)
                .reasoning(reasoning)
                .build();
    }

    /**
     * Determine merge status based on risk and issues
     */
    private String determineMergeStatus(ImpactReport report, RiskLevel riskLevel) {
        long errorDeps = report.getNewCircularDependencies().stream()
                .filter(d -> d.getSeverity() == CircularDependency.Severity.ERROR)
                .count();

        if (errorDeps > 0) {
            return "⛔ Blocked - Circular dependency errors must be resolved";
        }

        if (riskLevel == RiskLevel.CRITICAL) {
            return "⚠️ Requires architectural review and careful planning";
        }

        if (riskLevel == RiskLevel.HIGH) {
            return "⚠️ Awaiting reviews from affected teams";
        }

        if (riskLevel == RiskLevel.MEDIUM) {
            return "✅ Ready for review - moderate impact";
        }

        return "✅ Low risk - ready to merge after review";
    }

    /**
     * Determine which teams should review based on affected components
     */
    private List<String> determineAffectedTeams(ImpactReport report) {
        List<String> teams = new ArrayList<>();

        // Check for controller changes (API team)
        boolean hasControllerChanges = report.getChangedComponents().stream()
                .anyMatch(c -> "Controller".equals(c.getType()));
        if (hasControllerChanges) {
            teams.add("@api-team");
        }

        // Check for repository changes (data team)
        boolean hasRepositoryChanges = report.getChangedComponents().stream()
                .anyMatch(c -> "Repository".equals(c.getType()));
        if (hasRepositoryChanges) {
            teams.add("@data-team");
        }

        // Check for Kafka changes (integration team)
        boolean hasKafkaChanges = report.getChangedComponents().stream()
                .anyMatch(c -> "KafkaListener".equals(c.getType()));
        if (hasKafkaChanges) {
            teams.add("@integration-team");
        }

        // Check for service layer changes
        boolean hasServiceChanges = report.getChangedComponents().stream()
                .anyMatch(c -> "Service".equals(c.getType()));
        if (hasServiceChanges && teams.isEmpty()) {
            teams.add("@backend-team");
        }

        return teams;
    }
}
