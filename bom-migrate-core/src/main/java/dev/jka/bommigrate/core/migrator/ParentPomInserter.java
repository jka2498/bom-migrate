package dev.jka.bommigrate.core.migrator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Replaces or inserts a {@code <parent>} block in a service POM, for the
 * "use BOM as parent" migration strategy. Unlike the import approach, this
 * gives the service both {@code <dependencyManagement>} and
 * {@code <pluginManagement>} via inheritance.
 */
public final class ParentPomInserter {

    private static final Pattern PARENT_OPEN = Pattern.compile("^(\\s*)<parent\\s*>");
    private static final Pattern PARENT_CLOSE = Pattern.compile("^\\s*</parent\\s*>");
    private static final Pattern MODEL_VERSION = Pattern.compile("^\\s*<modelVersion\\s*>");
    private static final Pattern PROJECT_OPEN = Pattern.compile("^(\\s*)<project[\\s>]");

    public String insertParent(String pomContent, String groupId, String artifactId, String version) {
        String lineEnding = pomContent.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(pomContent.lines().toList());

        int parentStart = -1;
        int parentEnd = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (PARENT_OPEN.matcher(lines.get(i)).find() && parentStart < 0) {
                parentStart = i;
            }
            if (PARENT_CLOSE.matcher(lines.get(i)).find() && parentStart >= 0) {
                parentEnd = i;
                break;
            }
        }

        String indent = "    ";
        List<String> parentBlock = List.of(
                indent + "<parent>",
                indent + "    <groupId>" + groupId + "</groupId>",
                indent + "    <artifactId>" + artifactId + "</artifactId>",
                indent + "    <version>" + version + "</version>",
                indent + "    <relativePath/>",
                indent + "</parent>"
        );

        if (parentStart >= 0 && parentEnd >= 0) {
            for (int i = parentEnd; i >= parentStart; i--) {
                lines.remove(i);
            }
            lines.addAll(parentStart, parentBlock);
        } else {
            int insertAt = findInsertionPoint(lines);
            lines.addAll(insertAt, parentBlock);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1) {
                sb.append(lineEnding);
            }
        }
        if (pomContent.endsWith("\n") || pomContent.endsWith("\r\n")) {
            sb.append(lineEnding);
        }
        return sb.toString();
    }

    private int findInsertionPoint(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (MODEL_VERSION.matcher(lines.get(i)).find()) {
                return i + 1;
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            if (PROJECT_OPEN.matcher(lines.get(i)).find()) {
                return i + 1;
            }
        }
        return 1;
    }
}
