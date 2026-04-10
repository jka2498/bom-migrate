package dev.jka.bommigrate.core.migrator;

import dev.jka.bommigrate.core.model.MigrationAction;
import dev.jka.bommigrate.core.model.MigrationCandidate;
import dev.jka.bommigrate.core.model.MigrationReport;
import dev.jka.bommigrate.core.model.ResolvedDependency;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Applies STRIP changes from a migration report to the POM file by removing
 * {@code <version>} lines while preserving all other formatting.
 */
public final class PomWriter {

    private final VersionLineLocator locator = new VersionLineLocator();

    /**
     * Generates the modified POM content with version tags removed for STRIP candidates.
     *
     * @param pomPath path to the original POM file
     * @param report  migration report containing classified candidates
     * @return the modified POM content as a string
     * @throws IOException on file read errors
     */
    public String applyStrips(Path pomPath, MigrationReport report) throws IOException {
        String content = Files.readString(pomPath, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(content.lines().toList());

        Map<String, VersionLineLocator.VersionElementLocation> locations = locator.locateVersionElements(lines);

        // Collect all line ranges to remove (from STRIP candidates)
        TreeSet<Integer> linesToRemove = new TreeSet<>(Comparator.reverseOrder());
        for (MigrationCandidate candidate : report.byAction(MigrationAction.STRIP)) {
            ResolvedDependency dep = candidate.dependency();
            String key = dep.key();

            // Find matching location — check exact key and duplicate-suffixed keys
            VersionLineLocator.VersionElementLocation location = locations.get(key);
            if (location == null) {
                // Try duplicate-suffixed keys
                for (Map.Entry<String, VersionLineLocator.VersionElementLocation> entry : locations.entrySet()) {
                    if (entry.getKey().startsWith(key)) {
                        location = entry.getValue();
                        break;
                    }
                }
            }

            if (location != null) {
                for (int line = location.startLine(); line <= location.endLine(); line++) {
                    linesToRemove.add(line);
                }
            }
        }

        // Remove lines in reverse order to preserve indices
        for (int lineIndex : linesToRemove) {
            if (lineIndex >= 0 && lineIndex < lines.size()) {
                lines.remove(lineIndex);
            }
        }

        // Reconstruct with original line ending style
        String lineEnding = content.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1) {
                sb.append(lineEnding);
            }
        }
        // Preserve trailing newline if original had one
        if (content.endsWith("\n") || content.endsWith("\r\n")) {
            sb.append(lineEnding);
        }

        return sb.toString();
    }

    /**
     * Writes the modified content to the specified file.
     */
    public void writeTo(Path pomPath, String modifiedContent) throws IOException {
        Files.writeString(pomPath, modifiedContent, StandardCharsets.UTF_8);
    }

    /**
     * Generates a unified diff showing the changes that would be made.
     *
     * @param pomPath path to the POM file
     * @param report  migration report
     * @return a human-readable diff string
     * @throws IOException on file read errors
     */
    public String generateDiff(Path pomPath, MigrationReport report) throws IOException {
        String original = Files.readString(pomPath, StandardCharsets.UTF_8);
        String modified = applyStrips(pomPath, report);

        if (original.equals(modified)) {
            return "No changes.";
        }

        String[] originalLines = original.split("\\R", -1);
        String[] modifiedLines = modified.split("\\R", -1);

        StringBuilder diff = new StringBuilder();
        diff.append("--- ").append(pomPath).append("\n");
        diff.append("+++ ").append(pomPath).append(" (modified)\n");

        int origIdx = 0;
        int modIdx = 0;

        while (origIdx < originalLines.length || modIdx < modifiedLines.length) {
            if (origIdx < originalLines.length && modIdx < modifiedLines.length
                    && originalLines[origIdx].equals(modifiedLines[modIdx])) {
                origIdx++;
                modIdx++;
            } else if (origIdx < originalLines.length) {
                // Line exists in original but not in modified — it was removed
                diff.append("- ").append(originalLines[origIdx]).append("\n");
                origIdx++;
            } else {
                modIdx++;
            }
        }

        return diff.toString();
    }
}
