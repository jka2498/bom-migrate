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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies STRIP changes from a migration report to the POM file by removing
 * {@code <version>} lines while preserving all other formatting.
 */
public final class PomWriter {

    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{(.+?)}");
    private static final Pattern PROPERTY_LINE = Pattern.compile("^\\s*<(.+?)>.*?</\\1>\\s*$");

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

        // Collect property names used in the version lines being removed
        Set<String> candidateProperties = new HashSet<>();
        for (int lineIndex : linesToRemove) {
            if (lineIndex >= 0 && lineIndex < lines.size()) {
                Matcher propMatcher = PROPERTY_REF.matcher(lines.get(lineIndex));
                while (propMatcher.find()) {
                    candidateProperties.add(propMatcher.group(1));
                }
            }
        }

        // Remove version lines in reverse order to preserve indices
        for (int lineIndex : linesToRemove) {
            if (lineIndex >= 0 && lineIndex < lines.size()) {
                lines.remove(lineIndex);
            }
        }

        // Remove orphaned properties — only if not referenced anywhere else in the POM
        if (!candidateProperties.isEmpty()) {
            removeOrphanedProperties(lines, candidateProperties);
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
     * Removes property definitions from the {@code <properties>} block if
     * they are no longer referenced anywhere in the POM content.
     */
    private void removeOrphanedProperties(List<String> lines, Set<String> candidateProperties) {
        // Find the <properties> section boundaries
        int propsStart = -1;
        int propsEnd = -1;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.equals("<properties>") || trimmed.startsWith("<properties ")) {
                propsStart = i;
            } else if (trimmed.equals("</properties>") && propsStart >= 0) {
                propsEnd = i;
                break;
            }
        }

        if (propsStart < 0 || propsEnd < 0) {
            return;
        }

        // Build the full POM text WITHOUT the properties section, to check references
        StringBuilder pomWithoutProps = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i < propsStart || i > propsEnd) {
                pomWithoutProps.append(lines.get(i)).append("\n");
            }
        }
        String restOfPom = pomWithoutProps.toString();

        // Find which candidate properties are truly orphaned
        TreeSet<Integer> propertyLinesToRemove = new TreeSet<>(Comparator.reverseOrder());
        for (String propName : candidateProperties) {
            // Check if ${propName} appears anywhere outside the <properties> section
            if (restOfPom.contains("${" + propName + "}")) {
                continue; // Still referenced — keep it
            }

            // Find and mark the property line for removal
            for (int i = propsStart + 1; i < propsEnd; i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("<" + propName + ">") && trimmed.endsWith("</" + propName + ">")) {
                    propertyLinesToRemove.add(i);
                    break;
                }
            }
        }

        // Remove in reverse order
        for (int lineIndex : propertyLinesToRemove) {
            lines.remove(lineIndex);
        }
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
