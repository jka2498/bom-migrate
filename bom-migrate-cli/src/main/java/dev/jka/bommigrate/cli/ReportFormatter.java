package dev.jka.bommigrate.cli;

import dev.jka.bommigrate.core.model.MigrationAction;
import dev.jka.bommigrate.core.model.MigrationCandidate;
import dev.jka.bommigrate.core.model.MigrationReport;

import java.util.List;

/**
 * Formats {@link MigrationReport} instances for console output.
 */
public final class ReportFormatter {

    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_DIM = "\u001B[2m";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";

    /**
     * Formats a single POM's migration report.
     */
    public String format(MigrationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append(ANSI_BOLD).append("=== ").append(report.pomPath()).append(" ===").append(ANSI_RESET).append("\n");

        List<MigrationCandidate> strips = report.byAction(MigrationAction.STRIP);
        if (!strips.isEmpty()) {
            sb.append("\n  ").append(ANSI_GREEN).append("STRIP").append(ANSI_RESET)
                    .append(" (version matches BOM — will be removed):\n");
            for (MigrationCandidate c : strips) {
                sb.append("    ").append(ANSI_GREEN).append("- ").append(ANSI_RESET)
                        .append(formatDependency(c))
                        .append("\n");
            }
        }

        List<MigrationCandidate> flags = report.byAction(MigrationAction.FLAG);
        if (!flags.isEmpty()) {
            sb.append("\n  ").append(ANSI_YELLOW).append("FLAG").append(ANSI_RESET)
                    .append(" (needs human review):\n");
            for (MigrationCandidate c : flags) {
                sb.append("    ").append(ANSI_YELLOW).append("! ").append(ANSI_RESET)
                        .append(formatDependency(c))
                        .append(" — ").append(c.reason())
                        .append("\n");
            }
        }

        List<MigrationCandidate> skips = report.byAction(MigrationAction.SKIP);
        if (!skips.isEmpty()) {
            sb.append("\n  ").append(ANSI_DIM).append("SKIP").append(ANSI_RESET)
                    .append(" (not managed or no action needed):\n");
            for (MigrationCandidate c : skips) {
                sb.append("    ").append(ANSI_DIM).append("  ").append(formatDependency(c))
                        .append(" — ").append(c.reason()).append(ANSI_RESET)
                        .append("\n");
            }
        }

        sb.append("\n  Summary: ")
                .append(ANSI_GREEN).append(report.stripCount()).append(" strip").append(ANSI_RESET).append(", ")
                .append(ANSI_YELLOW).append(report.flagCount()).append(" flag").append(ANSI_RESET).append(", ")
                .append(ANSI_DIM).append(report.skipCount()).append(" skip").append(ANSI_RESET);

        return sb.toString();
    }

    /**
     * Formats a summary across all reports.
     */
    public String formatSummary(List<MigrationReport> reports) {
        int totalStrip = 0;
        int totalFlag = 0;
        int totalSkip = 0;
        int filesChanged = 0;

        for (MigrationReport report : reports) {
            totalStrip += report.stripCount();
            totalFlag += report.flagCount();
            totalSkip += report.skipCount();
            if (report.hasChanges()) {
                filesChanged++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(ANSI_BOLD).append("=== Overall Summary ===").append(ANSI_RESET).append("\n");
        sb.append("  POMs analyzed: ").append(reports.size()).append("\n");
        sb.append("  POMs with changes: ").append(filesChanged).append("\n");
        sb.append("  Total: ")
                .append(ANSI_GREEN).append(totalStrip).append(" strip").append(ANSI_RESET).append(", ")
                .append(ANSI_YELLOW).append(totalFlag).append(" flag").append(ANSI_RESET).append(", ")
                .append(ANSI_DIM).append(totalSkip).append(" skip").append(ANSI_RESET)
                .append("\n");

        return sb.toString();
    }

    private String formatDependency(MigrationCandidate candidate) {
        var dep = candidate.dependency();
        StringBuilder sb = new StringBuilder();
        sb.append(dep.groupId()).append(":").append(dep.artifactId());
        if (dep.version() != null) {
            sb.append(":").append(dep.version());
        }
        if (!"jar".equals(dep.type())) {
            sb.append(" [type=").append(dep.type()).append("]");
        }
        if (!dep.classifier().isEmpty()) {
            sb.append(" [classifier=").append(dep.classifier()).append("]");
        }
        return sb.toString();
    }
}
