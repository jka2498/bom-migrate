package dev.jka.bommigrate.core.migrator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans raw POM lines to locate {@code <version>...</version>} elements within
 * {@code <plugin>} blocks in the {@code <build><plugins>} section (not
 * {@code <pluginManagement>}).
 *
 * <p>Mirrors {@link VersionLineLocator} but for the plugin section of the POM.
 */
public final class PluginVersionLineLocator {

    private static final Pattern BUILD_START = Pattern.compile("<build\\s*>");
    private static final Pattern BUILD_END = Pattern.compile("</build\\s*>");
    private static final Pattern PLUGIN_MGMT_START = Pattern.compile("<pluginManagement\\s*>");
    private static final Pattern PLUGIN_MGMT_END = Pattern.compile("</pluginManagement\\s*>");
    private static final Pattern PLUGINS_START = Pattern.compile("<plugins\\s*>");
    private static final Pattern PLUGINS_END = Pattern.compile("</plugins\\s*>");
    private static final Pattern PLUGIN_START = Pattern.compile("<plugin\\s*>");
    private static final Pattern PLUGIN_END = Pattern.compile("</plugin\\s*>");
    private static final Pattern GROUP_ID = Pattern.compile("<groupId>\\s*(.+?)\\s*</groupId>");
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>\\s*(.+?)\\s*</artifactId>");
    private static final Pattern VERSION_FULL_LINE = Pattern.compile("^(\\s*)<version>.*?</version>\\s*$");

    /**
     * Locates the {@code <version>} elements for plugins in the
     * {@code <build><plugins>} section of the POM (skipping pluginManagement).
     *
     * @param pomLines the raw lines of the POM file
     * @return map from plugin key (groupId:artifactId) to version location
     */
    public Map<String, VersionLineLocator.VersionElementLocation> locatePluginVersionElements(List<String> pomLines) {
        Map<String, VersionLineLocator.VersionElementLocation> result = new LinkedHashMap<>();

        boolean inBuild = false;
        boolean inPluginMgmt = false;
        boolean inPlugins = false;
        boolean inPlugin = false;
        int pluginMgmtDepth = 0;

        String currentGroupId = null;
        String currentArtifactId = null;
        int versionStartLine = -1;
        int versionEndLine = -1;

        for (int i = 0; i < pomLines.size(); i++) {
            String line = pomLines.get(i);

            // Track <build> nesting
            if (!inBuild && BUILD_START.matcher(line).find()) {
                inBuild = true;
                continue;
            }
            if (inBuild && !inPluginMgmt && !inPlugins && BUILD_END.matcher(line).find()) {
                inBuild = false;
                continue;
            }

            if (!inBuild) {
                continue;
            }

            // Skip <pluginManagement> — we only want <build><plugins> (direct declarations)
            if (PLUGIN_MGMT_START.matcher(line).find()) {
                inPluginMgmt = true;
                pluginMgmtDepth++;
                continue;
            }
            if (inPluginMgmt && PLUGIN_MGMT_END.matcher(line).find()) {
                pluginMgmtDepth--;
                if (pluginMgmtDepth == 0) {
                    inPluginMgmt = false;
                }
                continue;
            }
            if (inPluginMgmt) {
                continue;
            }

            // Track <plugins> sections
            if (!inPlugins && PLUGINS_START.matcher(line).find()) {
                inPlugins = true;
                continue;
            }
            if (inPlugins && !inPlugin && PLUGINS_END.matcher(line).find()) {
                inPlugins = false;
                continue;
            }

            if (!inPlugins) {
                continue;
            }

            // Track individual <plugin> blocks
            if (!inPlugin && PLUGIN_START.matcher(line).find()) {
                inPlugin = true;
                currentGroupId = null;
                currentArtifactId = null;
                versionStartLine = -1;
                versionEndLine = -1;
                continue;
            }

            if (inPlugin && PLUGIN_END.matcher(line).find()) {
                if (currentArtifactId != null && versionStartLine >= 0) {
                    String groupId = currentGroupId != null ? currentGroupId : "org.apache.maven.plugins";
                    String key = groupId + ":" + currentArtifactId;

                    String lookupKey = key;
                    int duplicateCounter = 2;
                    while (result.containsKey(lookupKey)) {
                        lookupKey = key + "#" + duplicateCounter++;
                    }
                    result.put(lookupKey, new VersionLineLocator.VersionElementLocation(versionStartLine, versionEndLine));
                }
                inPlugin = false;
                continue;
            }

            if (inPlugin) {
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

                if (VERSION_FULL_LINE.matcher(line).matches()) {
                    versionStartLine = i;
                    versionEndLine = i;
                } else if (line.contains("<version>") && !line.contains("</version>")) {
                    versionStartLine = i;
                } else if (versionStartLine >= 0 && versionEndLine < 0 && line.contains("</version>")) {
                    versionEndLine = i;
                }
            }
        }

        return result;
    }
}
