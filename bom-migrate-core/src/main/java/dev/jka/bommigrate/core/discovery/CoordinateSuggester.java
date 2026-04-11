package dev.jka.bommigrate.core.discovery;

import dev.jka.bommigrate.core.model.PomModelReader;
import org.apache.maven.model.Model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Suggests default BOM coordinates (groupId, artifactId, version) based on
 * the scanned service POMs. Used to pre-fill the web UI's BOM coordinates
 * form so the user doesn't have to type them from scratch.
 */
public final class CoordinateSuggester {

    /** The suggested coordinates for a BOM. */
    public record Suggestion(String groupId, String artifactId, String version) {}

    private static final Suggestion DEFAULT = new Suggestion("com.example", "my-bom", "1.0.0");

    /**
     * Suggests coordinates by scanning the given POMs for a common groupId prefix.
     * Falls back to {@code com.example / my-bom / 1.0.0} if nothing useful can be derived.
     */
    public Suggestion suggest(List<Path> scannedPoms) {
        if (scannedPoms == null || scannedPoms.isEmpty()) {
            return DEFAULT;
        }

        List<String> groupIds = new ArrayList<>();
        for (Path pom : scannedPoms) {
            try {
                Model model = PomModelReader.parseModel(pom);
                String gid = model.getGroupId();
                if (gid == null && model.getParent() != null) {
                    gid = model.getParent().getGroupId();
                }
                if (gid != null && !gid.isBlank()) {
                    groupIds.add(gid);
                }
            } catch (IOException e) {
                // Skip unparseable POMs — they won't contribute to the suggestion
            }
        }

        if (groupIds.isEmpty()) {
            return DEFAULT;
        }

        String commonPrefix = longestCommonPrefix(groupIds);
        String suggestedGroupId = commonPrefix != null && !commonPrefix.isBlank()
                ? commonPrefix
                : groupIds.get(0);

        return new Suggestion(suggestedGroupId, "my-bom", "1.0.0");
    }

    /**
     * Finds the longest common dot-delimited prefix across the given groupIds.
     * Example: {@code [com.mycompany.claims.service-a, com.mycompany.claims.service-b]}
     * → {@code com.mycompany.claims}.
     *
     * <p>Trims the trailing dot if any. Returns null if the list is empty or
     * the first element has no dot segments.
     */
    static String longestCommonPrefix(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String[][] parts = new String[values.size()][];
        int minLen = Integer.MAX_VALUE;
        for (int i = 0; i < values.size(); i++) {
            parts[i] = values.get(i).split("\\.");
            minLen = Math.min(minLen, parts[i].length);
        }

        StringBuilder prefix = new StringBuilder();
        for (int segIdx = 0; segIdx < minLen; segIdx++) {
            String seg = parts[0][segIdx];
            boolean allMatch = true;
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i][segIdx].equals(seg)) {
                    allMatch = false;
                    break;
                }
            }
            if (!allMatch) break;
            if (prefix.length() > 0) prefix.append('.');
            prefix.append(seg);
        }
        return prefix.length() == 0 ? null : prefix.toString();
    }
}
