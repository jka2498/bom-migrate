package dev.jka.bommigrate.core.migrator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans raw POM lines to locate {@code <version>...</version>} elements within
 * {@code <dependency>} blocks in the {@code <dependencies>} section (not
 * {@code <dependencyManagement>}).
 *
 * <p>Uses a lightweight state machine and regex patterns to find exact line
 * positions, enabling format-preserving removal of version elements.
 */
public final class VersionLineLocator {

    private static final Pattern DEPENDENCY_MGMT_START = Pattern.compile("<dependencyManagement\\s*>");
    private static final Pattern DEPENDENCY_MGMT_END = Pattern.compile("</dependencyManagement\\s*>");
    private static final Pattern DEPENDENCIES_START = Pattern.compile("<dependencies\\s*>");
    private static final Pattern DEPENDENCIES_END = Pattern.compile("</dependencies\\s*>");
    private static final Pattern DEPENDENCY_START = Pattern.compile("<dependency\\s*>");
    private static final Pattern DEPENDENCY_END = Pattern.compile("</dependency\\s*>");
    private static final Pattern GROUP_ID = Pattern.compile("<groupId>\\s*(.+?)\\s*</groupId>");
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>\\s*(.+?)\\s*</artifactId>");
    private static final Pattern TYPE_PATTERN = Pattern.compile("<type>\\s*(.+?)\\s*</type>");
    private static final Pattern CLASSIFIER_PATTERN = Pattern.compile("<classifier>\\s*(.+?)\\s*</classifier>");
    private static final Pattern VERSION_FULL_LINE = Pattern.compile("^(\\s*)<version>.*?</version>\\s*$");

    /**
     * Represents the position of a {@code <version>} element in the POM file.
     *
     * @param startLine 0-based line index where the version element starts
     * @param endLine   0-based line index where the version element ends (inclusive)
     */
    public record VersionElementLocation(int startLine, int endLine) {
    }

    /**
     * Locates the {@code <version>} elements for dependencies in the
     * {@code <dependencies>} section of the POM.
     *
     * @param pomLines the raw lines of the POM file
     * @return map from dependency key (groupId:artifactId:type:classifier) to version location
     */
    public Map<String, VersionElementLocation> locateVersionElements(List<String> pomLines) {
        Map<String, VersionElementLocation> result = new LinkedHashMap<>();

        boolean inDepMgmt = false;
        boolean inDependencies = false;
        boolean inDependency = false;
        int depMgmtDepth = 0;

        String currentGroupId = null;
        String currentArtifactId = null;
        String currentType = null;
        String currentClassifier = null;
        int versionStartLine = -1;
        int versionEndLine = -1;

        for (int i = 0; i < pomLines.size(); i++) {
            String line = pomLines.get(i);

            // Track dependencyManagement nesting
            if (DEPENDENCY_MGMT_START.matcher(line).find()) {
                inDepMgmt = true;
                depMgmtDepth++;
                continue;
            }
            if (inDepMgmt && DEPENDENCY_MGMT_END.matcher(line).find()) {
                depMgmtDepth--;
                if (depMgmtDepth == 0) {
                    inDepMgmt = false;
                }
                continue;
            }

            // Skip everything inside dependencyManagement
            if (inDepMgmt) {
                continue;
            }

            // Track <dependencies> sections outside of dependencyManagement
            if (!inDependencies && DEPENDENCIES_START.matcher(line).find()) {
                inDependencies = true;
                continue;
            }
            if (inDependencies && !inDependency && DEPENDENCIES_END.matcher(line).find()) {
                inDependencies = false;
                continue;
            }

            if (!inDependencies) {
                continue;
            }

            // Track individual <dependency> blocks
            if (!inDependency && DEPENDENCY_START.matcher(line).find()) {
                inDependency = true;
                currentGroupId = null;
                currentArtifactId = null;
                currentType = null;
                currentClassifier = null;
                versionStartLine = -1;
                versionEndLine = -1;
                continue;
            }

            if (inDependency && DEPENDENCY_END.matcher(line).find()) {
                // Emit this dependency's version location if found
                if (currentGroupId != null && currentArtifactId != null && versionStartLine >= 0) {
                    String type = (currentType == null || currentType.isBlank()) ? "jar" : currentType;
                    String classifier = (currentClassifier == null) ? "" : currentClassifier;
                    String key = currentGroupId + ":" + currentArtifactId + ":" + type + ":" + classifier;

                    // If duplicate key, append index to make it unique in the map
                    String lookupKey = key;
                    int duplicateCounter = 2;
                    while (result.containsKey(lookupKey)) {
                        lookupKey = key + "#" + duplicateCounter++;
                    }
                    result.put(lookupKey, new VersionElementLocation(versionStartLine, versionEndLine));
                }
                inDependency = false;
                continue;
            }

            if (inDependency) {
                Matcher groupMatcher = GROUP_ID.matcher(line);
                if (groupMatcher.find()) {
                    currentGroupId = groupMatcher.group(1);
                    continue;
                }

                Matcher artifactMatcher = ARTIFACT_ID.matcher(line);
                if (artifactMatcher.find()) {
                    currentArtifactId = artifactMatcher.group(1);
                    continue;
                }

                Matcher typeMatcher = TYPE_PATTERN.matcher(line);
                if (typeMatcher.find()) {
                    currentType = typeMatcher.group(1);
                    continue;
                }

                Matcher classifierMatcher = CLASSIFIER_PATTERN.matcher(line);
                if (classifierMatcher.find()) {
                    currentClassifier = classifierMatcher.group(1);
                    continue;
                }

                // Check for <version> element — could be single-line or multi-line
                if (VERSION_FULL_LINE.matcher(line).matches()) {
                    versionStartLine = i;
                    versionEndLine = i;
                } else if (line.contains("<version>") && !line.contains("</version>")) {
                    // Multi-line version (rare)
                    versionStartLine = i;
                } else if (versionStartLine >= 0 && versionEndLine < 0 && line.contains("</version>")) {
                    versionEndLine = i;
                }
            }
        }

        return result;
    }
}
