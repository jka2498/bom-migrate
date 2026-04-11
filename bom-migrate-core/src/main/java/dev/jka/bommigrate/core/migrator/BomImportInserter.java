package dev.jka.bommigrate.core.migrator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inserts a {@code <dependencyManagement>} BOM import block — and optionally
 * a matching {@code <properties>} block — into a service POM, preserving
 * formatting as much as possible.
 *
 * <p>Three orthogonal operations:
 * <ol>
 *   <li><b>Dependency management</b>: if the POM already has a
 *       {@code <dependencyManagement>} section, new imports are appended as
 *       additional {@code <dependency>} entries just before the closing
 *       {@code </dependencies>} inside that block. Otherwise a new block is
 *       inserted immediately before the first {@code <dependencies>}, or
 *       before {@code </project>} if none exists.</li>
 *   <li><b>Properties (optional)</b>: if {@code propertiesToAdd} is non-empty
 *       (the caller picked {@link dev.jka.bommigrate.core.discovery.VersionFormat#PROPERTIES}),
 *       each entry is added to the POM's {@code <properties>} block.
 *       Existing entries with the same key are left alone — the POM takes
 *       priority over the suggested value. If there's no {@code <properties>}
 *       block at all, a new one is created before {@code <dependencyManagement>}
 *       or {@code <dependencies>}.</li>
 *   <li><b>Version values</b>: the {@link BomImport#version()} string is
 *       inlined verbatim — the caller decides whether to pass a literal
 *       version like {@code "1.0.0"} or a property reference like
 *       {@code "${my-bom.version}"}. The inserter does not interpret it.</li>
 * </ol>
 */
public final class BomImportInserter {

    private static final Pattern DEP_MGMT_OPEN = Pattern.compile("^(\\s*)<dependencyManagement\\s*>");
    private static final Pattern DEP_MGMT_CLOSE = Pattern.compile("^\\s*</dependencyManagement\\s*>");
    private static final Pattern DEPENDENCIES_OPEN = Pattern.compile("^(\\s*)<dependencies\\s*>");
    private static final Pattern DEPENDENCIES_CLOSE = Pattern.compile("^\\s*</dependencies\\s*>");
    private static final Pattern PROPERTIES_OPEN = Pattern.compile("^(\\s*)<properties\\s*>");
    private static final Pattern PROPERTIES_CLOSE = Pattern.compile("^\\s*</properties\\s*>");
    private static final Pattern PROJECT_CLOSE = Pattern.compile("^\\s*</project\\s*>");

    /** A single BOM to import: groupId, artifactId, version (literal or ${...} reference). */
    public record BomImport(String groupId, String artifactId, String version) {}

    /**
     * Inserts BOM import entries into the given POM content. No properties
     * are added — use {@link #insertImports(String, List, Map)} for the
     * properties-format variant.
     */
    public String insertImports(String pomContent, List<BomImport> imports) {
        return insertImports(pomContent, imports, Map.of());
    }

    /**
     * Inserts BOM import entries and (optionally) a set of properties into
     * the given POM content.
     *
     * @param pomContent      the full POM XML (for example, output of {@link PomWriter#applyStrips})
     * @param imports         the BOM imports to add to {@code <dependencyManagement>}
     * @param propertiesToAdd property name → value map to add to {@code <properties>};
     *                        pass an empty map to skip property insertion
     * @return the modified POM content
     */
    public String insertImports(String pomContent, List<BomImport> imports, Map<String, String> propertiesToAdd) {
        if (imports == null || imports.isEmpty()) {
            return pomContent;
        }

        String lineEnding = pomContent.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(pomContent.lines().toList());

        // Step 1: insert the dependencyManagement imports
        DepMgmtLocation existing = findExistingDepMgmt(lines);
        if (existing != null) {
            insertIntoExistingDepMgmt(lines, existing, imports);
        } else {
            insertNewDepMgmtBlock(lines, imports);
        }

        // Step 2: insert or merge the <properties> block if requested
        if (propertiesToAdd != null && !propertiesToAdd.isEmpty()) {
            upsertProperties(lines, propertiesToAdd);
        }

        return joinLines(lines, pomContent, lineEnding);
    }

    // --- dependency management ---

    private void insertIntoExistingDepMgmt(List<String> lines, DepMgmtLocation loc, List<BomImport> imports) {
        int depsCloseLine = -1;
        for (int i = loc.openLine + 1; i <= loc.closeLine; i++) {
            if (DEPENDENCIES_CLOSE.matcher(lines.get(i)).find()) {
                depsCloseLine = i;
                break;
            }
        }

        String baseIndent = loc.indent;
        // dependency block is two levels deeper than <dependencyManagement> (via <dependencies>)
        String depIndent = baseIndent + "        ";

        List<String> importLines = renderImportDependencies(imports, depIndent);

        if (depsCloseLine >= 0) {
            lines.addAll(depsCloseLine, importLines);
        } else {
            // Rare: depMgmt with no <dependencies> child. Wrap the new entries.
            List<String> wrapped = new ArrayList<>();
            wrapped.add(baseIndent + "    <dependencies>");
            wrapped.addAll(importLines);
            wrapped.add(baseIndent + "    </dependencies>");
            lines.addAll(loc.closeLine, wrapped);
        }
    }

    private void insertNewDepMgmtBlock(List<String> lines, List<BomImport> imports) {
        int insertLine = -1;
        String indent = "    ";

        for (int i = 0; i < lines.size(); i++) {
            Matcher depsOpen = DEPENDENCIES_OPEN.matcher(lines.get(i));
            if (depsOpen.find()) {
                insertLine = i;
                indent = depsOpen.group(1);
                break;
            }
        }

        if (insertLine < 0) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (PROJECT_CLOSE.matcher(lines.get(i)).find()) {
                    insertLine = i;
                    indent = "    ";
                    break;
                }
            }
        }

        if (insertLine < 0) {
            insertLine = lines.size();
        }

        List<String> block = renderDepMgmtBlock(imports, indent);
        if (insertLine < lines.size() && !lines.get(insertLine).isBlank()) {
            block.add("");
        }
        lines.addAll(insertLine, block);
    }

    // --- properties ---

    /**
     * Inserts new properties into an existing {@code <properties>} block,
     * or creates a new block if none exists. Existing properties with the
     * same key are not overwritten (the POM wins).
     */
    private void upsertProperties(List<String> lines, Map<String, String> propertiesToAdd) {
        PropertiesLocation existing = findExistingProperties(lines);
        if (existing != null) {
            mergeIntoExistingProperties(lines, existing, propertiesToAdd);
        } else {
            insertNewPropertiesBlock(lines, propertiesToAdd);
        }
    }

    private void mergeIntoExistingProperties(List<String> lines, PropertiesLocation loc,
                                              Map<String, String> propertiesToAdd) {
        // Figure out which keys already exist in the block so we don't duplicate
        List<String> existingKeys = new ArrayList<>();
        Pattern keyPattern = Pattern.compile("^\\s*<([^/\\s>]+)>");
        for (int i = loc.openLine + 1; i < loc.closeLine; i++) {
            Matcher m = keyPattern.matcher(lines.get(i));
            if (m.find()) {
                existingKeys.add(m.group(1));
            }
        }

        // Child indent = property block indent + 4 spaces
        String propIndent = loc.indent + "    ";

        List<String> toInsert = new ArrayList<>();
        for (Map.Entry<String, String> entry : propertiesToAdd.entrySet()) {
            if (existingKeys.contains(entry.getKey())) {
                continue;
            }
            toInsert.add(propIndent + "<" + entry.getKey() + ">" + entry.getValue() + "</" + entry.getKey() + ">");
        }

        if (!toInsert.isEmpty()) {
            lines.addAll(loc.closeLine, toInsert);
        }
    }

    private void insertNewPropertiesBlock(List<String> lines, Map<String, String> propertiesToAdd) {
        // Insert before the first <dependencyManagement> or <dependencies>, whichever comes first
        int insertLine = -1;
        String indent = "    ";

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher dm = DEP_MGMT_OPEN.matcher(line);
            Matcher deps = DEPENDENCIES_OPEN.matcher(line);
            if (dm.find()) {
                insertLine = i;
                indent = dm.group(1);
                break;
            }
            if (deps.find()) {
                insertLine = i;
                indent = deps.group(1);
                break;
            }
        }

        if (insertLine < 0) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (PROJECT_CLOSE.matcher(lines.get(i)).find()) {
                    insertLine = i;
                    indent = "    ";
                    break;
                }
            }
        }

        if (insertLine < 0) {
            insertLine = lines.size();
        }

        List<String> block = new ArrayList<>();
        block.add(indent + "<properties>");
        String childIndent = indent + "    ";
        for (Map.Entry<String, String> entry : propertiesToAdd.entrySet()) {
            block.add(childIndent + "<" + entry.getKey() + ">" + entry.getValue() + "</" + entry.getKey() + ">");
        }
        block.add(indent + "</properties>");
        // Blank line for visual separation if the next line isn't already blank
        if (insertLine < lines.size() && !lines.get(insertLine).isBlank()) {
            block.add("");
        }
        lines.addAll(insertLine, block);
    }

    // --- rendering helpers ---

    private List<String> renderDepMgmtBlock(List<BomImport> imports, String indent) {
        List<String> out = new ArrayList<>();
        out.add(indent + "<dependencyManagement>");
        out.add(indent + "    <dependencies>");
        out.addAll(renderImportDependencies(imports, indent + "        "));
        out.add(indent + "    </dependencies>");
        out.add(indent + "</dependencyManagement>");
        return out;
    }

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

    // --- locators ---

    private DepMgmtLocation findExistingDepMgmt(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher open = DEP_MGMT_OPEN.matcher(lines.get(i));
            if (open.find()) {
                String indent = open.group(1);
                for (int j = i + 1; j < lines.size(); j++) {
                    if (DEP_MGMT_CLOSE.matcher(lines.get(j)).find()) {
                        return new DepMgmtLocation(i, j, indent);
                    }
                }
            }
        }
        return null;
    }

    private PropertiesLocation findExistingProperties(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher open = PROPERTIES_OPEN.matcher(lines.get(i));
            if (open.find()) {
                String indent = open.group(1);
                for (int j = i + 1; j < lines.size(); j++) {
                    if (PROPERTIES_CLOSE.matcher(lines.get(j)).find()) {
                        return new PropertiesLocation(i, j, indent);
                    }
                }
            }
        }
        return null;
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
    private record PropertiesLocation(int openLine, int closeLine, String indent) {}
}
