package dev.jka.bommigrate.cli;

import dev.jka.bommigrate.core.discovery.BomCandidate;
import dev.jka.bommigrate.core.discovery.BomGenerationPlan;
import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.BomModuleAssignment;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
import dev.jka.bommigrate.core.discovery.VersionFormat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive wizard that walks the user through each BOM candidate,
 * asking whether to include it, which version to use, and (if multiple modules
 * are defined) which module to assign it to.
 */
public final class CandidateReviewWizard {

    private final BufferedReader input;
    private final PrintStream output;
    private final DiscoveryReportFormatter formatter;

    public CandidateReviewWizard() {
        this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
    }

    CandidateReviewWizard(BufferedReader input, PrintStream output) {
        this.input = input;
        this.output = output;
        this.formatter = new DiscoveryReportFormatter();
    }

    /**
     * Walks the user through each candidate and produces a BOM generation plan.
     *
     * @param report         the discovery report to review
     * @param modules        available BOM modules
     * @param parentGroupId  parent POM groupId
     * @param parentArtifactId parent POM artifactId
     * @param parentVersion  parent POM version
     * @return the confirmed generation plan
     */
    public BomGenerationPlan review(DiscoveryReport report,
                                    List<BomModule> modules,
                                    String parentGroupId,
                                    String parentArtifactId,
                                    String parentVersion) throws IOException {
        return review(report, modules, parentGroupId, parentArtifactId, parentVersion, VersionFormat.INLINE);
    }

    public BomGenerationPlan review(DiscoveryReport report,
                                    List<BomModule> modules,
                                    String parentGroupId,
                                    String parentArtifactId,
                                    String parentVersion,
                                    VersionFormat versionFormat) throws IOException {
        output.println();
        output.println("Found " + report.candidates().size() + " BOM candidates. Review each to include or exclude:");
        output.println();

        List<BomModuleAssignment> assignments = new ArrayList<>();
        int index = 1;
        int total = report.candidates().size();

        for (BomCandidate candidate : report.candidates()) {
            output.println(formatter.formatCandidate(candidate, index, total));

            if (!promptInclude()) {
                index++;
                continue;
            }

            String confirmedVersion = promptVersion(candidate.suggestedVersion());
            BomModule module = modules.size() == 1
                    ? modules.get(0)
                    : promptModule(modules);

            assignments.add(new BomModuleAssignment(candidate, module, confirmedVersion));
            index++;
        }

        return new BomGenerationPlan(
                parentGroupId, parentArtifactId, parentVersion,
                modules, assignments, versionFormat);
    }

    private boolean promptInclude() throws IOException {
        output.print("  Include in BOM? [Y/n/skip]: ");
        String line = input.readLine();
        if (line == null) return true;
        String answer = line.trim().toLowerCase();
        return answer.isEmpty() || answer.startsWith("y");
    }

    private String promptVersion(String suggested) throws IOException {
        output.print("  Version [" + suggested + "]: ");
        String line = input.readLine();
        if (line == null || line.isBlank()) return suggested;
        return line.trim();
    }

    private BomModule promptModule(List<BomModule> modules) throws IOException {
        StringBuilder options = new StringBuilder();
        for (int i = 0; i < modules.size(); i++) {
            if (i > 0) options.append("/");
            options.append(modules.get(i).name());
        }
        output.print("  Assign to module [" + options + "]: ");
        String line = input.readLine();
        if (line == null || line.isBlank()) {
            return modules.get(0); // default
        }
        String chosen = line.trim();
        return modules.stream()
                .filter(m -> m.name().equals(chosen))
                .findFirst()
                .orElse(modules.get(0));
    }
}
