package dev.jka.bommigrate.cli;

import dev.jka.bommigrate.core.discovery.BomCandidate;
import dev.jka.bommigrate.core.discovery.ConflictSeverity;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;

import java.util.Map;

/**
 * Formats a {@link DiscoveryReport} for console output with ANSI colouring.
 */
public final class DiscoveryReportFormatter {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String DIM = "\u001B[2m";

    public String formatSummary(DiscoveryReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append(BOLD).append("=== Discovery Report ===").append(RESET).append("\n");
        sb.append("Services scanned: ").append(report.totalServicesScanned()).append("\n");
        sb.append("Unique dependencies: ").append(report.uniqueDependencyCount()).append("\n");
        sb.append("Candidates with version conflicts: ").append(report.conflicting().size()).append("\n");
        return sb.toString();
    }

    public String formatCandidate(BomCandidate candidate, int index, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append(BOLD).append("[").append(index).append("/").append(total).append("] ")
                .append(candidate.groupId()).append(":").append(candidate.artifactId())
                .append(RESET).append("\n");

        sb.append("  Used by: ").append(candidate.serviceCount()).append("/")
                .append(candidate.totalServicesScanned())
                .append(" services (")
                .append(String.format("%.0f%%", candidate.frequencyRatio() * 100))
                .append(")\n");

        sb.append("  Versions: ").append(formatVersions(candidate.versions()));
        sb.append(" ").append(conflictIndicator(candidate.conflictSeverity())).append("\n");

        sb.append("  Suggested version: ").append(GREEN).append(candidate.suggestedVersion()).append(RESET).append("\n");
        sb.append("  Score: ").append(String.format("%.2f", candidate.score())).append("\n");
        return sb.toString();
    }

    private String formatVersions(Map<String, Integer> versions) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> entry : versions.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append(" (").append(entry.getValue()).append(")");
            first = false;
        }
        return sb.toString();
    }

    private String conflictIndicator(ConflictSeverity severity) {
        return switch (severity) {
            case NONE -> GREEN + "consistent" + RESET;
            case MINOR -> YELLOW + "minor conflict" + RESET;
            case MAJOR -> RED + "MAJOR CONFLICT" + RESET;
        };
    }
}
