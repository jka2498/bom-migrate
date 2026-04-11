package dev.jka.bommigrate.core.migrator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inserts a {@code <dependencyManagement>} BOM import block into a service POM,
 * preserving formatting as much as possible.
 *
 * <p>Two scenarios:
 * <ul>
 *   <li>POM already has a {@code <dependencyManagement>} section — new imports
 *       are appended as additional {@code <dependency>} entries just before
 *       the closing {@code </dependencies>} of the existing block.</li>
 *   <li>POM has no {@code <dependencyManagement>} — a new block is inserted
 *       immediately before the first {@code <dependencies>} tag, or before
 *       {@code </project>} if there are no dependencies.</li>
 * </ul>
 *
 * <p>Each imported BOM must be pre-built into a record via {@link #bomImport}.
 */
public final class BomImportInserter {

    private static final Pattern DEP_MGMT_OPEN = Pattern.compile("^(\\s*)<dependencyManagement\\s*>");
    private static final Pattern DEP_MGMT_CLOSE = Pattern.compile("^\\s*</dependencyManagement\\s*>");
    private static final Pattern DEPENDENCIES_OPEN = Pattern.compile("^(\\s*)<dependencies\\s*>");
    private static final Pattern DEPENDENCIES_CLOSE = Pattern.compile("^\\s*</dependencies\\s*>");
    private static final Pattern PROJECT_CLOSE = Pattern.compile("^\\s*</project\\s*>");

    /** A single BOM to import: groupId, artifactId, version. */
    public record BomImport(String groupId, String artifactId, String version) {}

    /**
     * Inserts BOM import entries into the given POM content.
     *
     * @param pomContent the full POM XML as a string (for example, output of
     *                   {@link PomWriter#applyStrips})
     * @param imports    the BOM imports to add
     * @return the modified POM content with imports inserted
     */
    public String insertImports(String pomContent, List<BomImport> imports) {
        if (imports == null || imports.isEmpty()) {
            return pomContent;
        }

        // Preserve original line ending style
        String lineEnding = pomContent.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(pomContent.lines().toList());

        DepMgmtLocation existing = findExistingDepMgmt(lines);
        if (existing != null) {
            return insertIntoExistingDepMgmt(lines, existing, imports, pomContent, lineEnding);
        }
        return insertNewDepMgmtBlock(lines, imports, pomContent, lineEnding);
    }

    // --- existing dep mgmt ---

    private String insertIntoExistingDepMgmt(List<String> lines, DepMgmtLocation loc,
                                             List<BomImport> imports, String original, String lineEnding) {
        // Find </dependencies> inside the depMgmt section
        int depsCloseLine = -1;
        for (int i = loc.openLine + 1; i <= loc.closeLine; i++) {
            if (DEPENDENCIES_CLOSE.matcher(lines.get(i)).find()) {
                depsCloseLine = i;
                break;
            }
        }

        String baseIndent = loc.indent;
        // dependency block is typically one level deeper than <dependencies>,
        // which is one level deeper than <dependencyManagement>. Use 4 spaces per level.
        String depIndent = baseIndent + "        ";

        List<String> importLines = renderImportDependencies(imports, depIndent);

        if (depsCloseLine >= 0) {
            // Insert before </dependencies>
            lines.addAll(depsCloseLine, importLines);
        } else {
            // Rare: depMgmt with no <dependencies> child. Insert before </dependencyManagement>
            // with a <dependencies> wrapper.
            List<String> wrapped = new ArrayList<>();
            wrapped.add(baseIndent + "    <dependencies>");
            wrapped.addAll(importLines);
            wrapped.add(baseIndent + "    </dependencies>");
            lines.addAll(loc.closeLine, wrapped);
        }
        return joinLines(lines, original, lineEnding);
    }

    // --- new dep mgmt block ---

    private String insertNewDepMgmtBlock(List<String> lines, List<BomImport> imports,
                                         String original, String lineEnding) {
        // Find insertion point: first top-level <dependencies> OR just before </project>
        int insertLine = -1;
        String indent = "    ";

        for (int i = 0; i < lines.size(); i++) {
            Matcher depsOpen = DEPENDENCIES_OPEN.matcher(lines.get(i));
            if (depsOpen.find()) {
                // Make sure this isn't inside a depMgmt we missed (we already checked)
                insertLine = i;
                indent = depsOpen.group(1);
                break;
            }
        }

        if (insertLine < 0) {
            // No <dependencies> at all — insert before </project>
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (PROJECT_CLOSE.matcher(lines.get(i)).find()) {
                    insertLine = i;
                    indent = "    ";
                    break;
                }
            }
        }

        if (insertLine < 0) {
            // Malformed POM — append at the end
            insertLine = lines.size();
        }

        List<String> block = renderDepMgmtBlock(imports, indent);
        // Add a trailing blank line if the line we're inserting before isn't already blank
        if (insertLine < lines.size() && !lines.get(insertLine).isBlank()) {
            block.add("");
        }
        lines.addAll(insertLine, block);

        return joinLines(lines, original, lineEnding);
    }

    // --- helpers ---

    /**
     * @return the location of the first outer {@code <dependencyManagement>} block,
     *         or null if none exists.
     */
    private DepMgmtLocation findExistingDepMgmt(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher open = DEP_MGMT_OPEN.matcher(lines.get(i));
            if (open.find()) {
                String indent = open.group(1);
                // Find matching close
                for (int j = i + 1; j < lines.size(); j++) {
                    if (DEP_MGMT_CLOSE.matcher(lines.get(j)).find()) {
                        return new DepMgmtLocation(i, j, indent);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Renders a full {@code <dependencyManagement>} block (including the
     * {@code <dependencies>} wrapper) at the given indent level.
     */
    private List<String> renderDepMgmtBlock(List<BomImport> imports, String indent) {
        List<String> out = new ArrayList<>();
        out.add(indent + "<dependencyManagement>");
        out.add(indent + "    <dependencies>");
        out.addAll(renderImportDependencies(imports, indent + "        "));
        out.add(indent + "    </dependencies>");
        out.add(indent + "</dependencyManagement>");
        return out;
    }

    /**
     * Renders one or more {@code <dependency>} entries (without any surrounding
     * {@code <dependencies>} wrapper) at the given indent level.
     */
    private List<String> renderImportDependencies(List<BomImport> imports, String indent) {
        String childIndent = indent + "    ";
        List<String> out = new ArrayList<>();
        for (BomImport imp : imports) {
            out.add(indent + "<dependency>");
            out.add(childIndent + "<groupId>" + imp.groupId() + "</groupId>");
            out.add(childIndent + "<artifactId>" + imp.artifactId() + "</artifactId>");
            out.add(childIndent + "<version>" + imp.version() + "</version>");
            out.add(childIndent + "<type>pom</type>");
            out.add(childIndent + "<scope>import</scope>");
            out.add(indent + "</dependency>");
        }
        return out;
    }

    private String joinLines(List<String> lines, String original, String lineEnding) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1) {
                sb.append(lineEnding);
            }
        }
        if (original.endsWith("\n") || original.endsWith("\r\n")) {
            sb.append(lineEnding);
        }
        return sb.toString();
    }

    private record DepMgmtLocation(int openLine, int closeLine, String indent) {}
}
