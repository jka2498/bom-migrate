package dev.jka.bommigrate.web.service;

import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.ScanMetadata;
import dev.jka.bommigrate.core.discovery.VersionFormat;
import dev.jka.bommigrate.core.migrator.BomImportInserter;
import dev.jka.bommigrate.core.migrator.PomAnalyzer;
import dev.jka.bommigrate.core.migrator.PomDiff;
import dev.jka.bommigrate.core.migrator.PomWriter;
import dev.jka.bommigrate.core.model.DependencyManagementMap;
import dev.jka.bommigrate.core.model.MigrationAction;
import dev.jka.bommigrate.core.model.MigrationCandidate;
import dev.jka.bommigrate.core.model.MigrationReport;
import dev.jka.bommigrate.core.resolver.DefaultBomResolver;
import dev.jka.bommigrate.web.dto.DiffLine;
import dev.jka.bommigrate.web.dto.FlaggedDependency;
import dev.jka.bommigrate.web.dto.MigrationPreviewResponse;
import dev.jka.bommigrate.web.dto.ServicePreview;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composes the core APIs to produce the web UI's migration preview:
 * for each scanned service POM, what it would look like if migrated against
 * the just-generated BOM — with versions stripped AND the BOM import block
 * inserted. Preview only — never writes back to the service POMs.
 */
@Component
public class MigrationPreviewService {

    private final DiscoverySessionService session;
    private final DefaultBomResolver bomResolver = new DefaultBomResolver();
    private final PomAnalyzer pomAnalyzer = new PomAnalyzer();
    private final PomWriter pomWriter = new PomWriter();
    private final BomImportInserter bomImportInserter = new BomImportInserter();

    public MigrationPreviewService(DiscoverySessionService session) {
        this.session = session;
    }

    public MigrationPreviewResponse buildPreview() throws IOException {
        Path outputDir = session.getOutputDir();
        if (outputDir == null || !Files.isDirectory(outputDir)) {
            throw new IllegalStateException("No BOM has been generated yet");
        }
        Path bomPom = outputDir.resolve("pom.xml");
        if (!Files.exists(bomPom)) {
            throw new IllegalStateException("No BOM has been generated yet");
        }

        // Staleness guard: the on-disk BOM was written for a specific snapshot
        // of coordinates/modules/assignments/versionFormat. If any of those
        // have changed since, the preview we'd build against it would be a
        // lie. Force the user to regenerate.
        String expected = session.getLastGeneratedSignature();
        String current = session.computeCurrentSignature();
        if (expected == null || !expected.equals(current)) {
            throw new StalePreviewException(
                    "Generated BOM is out of date with the current session state; regenerate it");
        }

        DependencyManagementMap bomMap = bomResolver.resolve(outputDir, true);

        List<BomImportInserter.BomImport> imports = buildBomImports();
        Map<String, String> bomImportProperties = buildBomImportProperties();

        List<Path> servicePoms = session.getScannedPomPaths();
        ScanMetadata metadata = session.getScanMetadata();
        List<String> displayNames = metadata.scannedSources();

        List<ServicePreview> services = new ArrayList<>();
        for (int i = 0; i < servicePoms.size(); i++) {
            Path pom = servicePoms.get(i);
            String display = i < displayNames.size() ? displayNames.get(i) : pom.getFileName().toString();

            String originalContent = Files.readString(pom, StandardCharsets.UTF_8);

            MigrationReport report = pomAnalyzer.analyze(pom, bomMap);
            String stripped = pomWriter.applyStrips(pom, report);
            String finalContent = bomImportInserter.insertImports(stripped, imports, bomImportProperties);

            PomDiff diff = PomDiff.between(originalContent, finalContent);
            List<DiffLine> diffLines = diff.lines().stream()
                    .map(l -> new DiffLine(l.status().name(), l.content(), l.oldNumber(), l.newNumber()))
                    .toList();

            List<FlaggedDependency> flagged = new ArrayList<>();
            for (MigrationCandidate candidate : report.byAction(MigrationAction.FLAG)) {
                flagged.add(new FlaggedDependency(
                        candidate.dependency().groupId(),
                        candidate.dependency().artifactId(),
                        candidate.dependency().version(),
                        candidate.bomVersion(),
                        candidate.reason()
                ));
            }

            services.add(new ServicePreview(
                    display,
                    report.stripCount(),
                    report.flagCount(),
                    report.skipCount(),
                    finalContent,
                    diffLines,
                    flagged
            ));
        }

        String snippet = buildBomImportSnippet(imports, bomImportProperties);
        return new MigrationPreviewResponse(snippet, services);
    }

    /**
     * Builds the list of BOM imports to insert. For single-module plans this is
     * the parent BOM. For multi-module plans it's one entry per child module
     * (the aggregator parent itself is not importable — it's a build aggregator).
     *
     * <p>When the session's {@link VersionFormat} is {@code PROPERTIES}, each
     * import's version string is a property reference (e.g.
     * {@code ${backend-core.version}}) instead of a literal, and the matching
     * properties are produced by {@link #buildBomImportProperties()}.
     */
    private List<BomImportInserter.BomImport> buildBomImports() {
        List<BomModule> modules = session.getModules();
        String groupId = session.getParentGroupId();
        String version = session.getParentVersion();
        boolean useProperties = session.getVersionFormat() == VersionFormat.PROPERTIES;

        // Single-module: import the flat BOM directly
        if (modules == null || modules.size() <= 1) {
            String artifactId = session.getParentArtifactId();
            String versionValue = useProperties ? "${" + artifactId + ".version}" : version;
            return List.of(new BomImportInserter.BomImport(groupId, artifactId, versionValue));
        }

        // Multi-module: import each child module (artifactId == module name, no prefix)
        List<BomImportInserter.BomImport> out = new ArrayList<>();
        for (BomModule module : modules) {
            String versionValue = useProperties ? "${" + module.name() + ".version}" : version;
            out.add(new BomImportInserter.BomImport(groupId, module.name(), versionValue));
        }
        return out;
    }

    /**
     * Builds the {@code <properties>} map to merge into each service POM
     * (and to prepend to the copy-pasteable snippet) when
     * {@code VersionFormat.PROPERTIES} is in effect. Each child BOM gets a
     * {@code <artifactId>.version} entry pointing at the parent BOM version;
     * in a multi-module plan all entries share the same value.
     *
     * <p>Returns an empty map for {@code INLINE} format — the inserter then
     * skips the properties step entirely.
     */
    private Map<String, String> buildBomImportProperties() {
        if (session.getVersionFormat() != VersionFormat.PROPERTIES) {
            return Map.of();
        }

        String version = session.getParentVersion();
        List<BomModule> modules = session.getModules();

        Map<String, String> props = new LinkedHashMap<>();
        if (modules == null || modules.size() <= 1) {
            props.put(session.getParentArtifactId() + ".version", version);
        } else {
            for (BomModule module : modules) {
                props.put(module.name() + ".version", version);
            }
        }
        return props;
    }

    /**
     * Builds the copy-pasteable snippet for the "Import this BOM" panel. When
     * {@code propertiesToAdd} is non-empty the snippet starts with a
     * {@code <properties>} block using the same keys that the inserter will
     * merge into each service POM, followed by the
     * {@code <dependencyManagement>} block.
     */
    private String buildBomImportSnippet(List<BomImportInserter.BomImport> imports,
                                          Map<String, String> propertiesToAdd) {
        StringBuilder sb = new StringBuilder();
        if (propertiesToAdd != null && !propertiesToAdd.isEmpty()) {
            sb.append("<properties>\n");
            for (Map.Entry<String, String> entry : propertiesToAdd.entrySet()) {
                sb.append("    <").append(entry.getKey()).append(">")
                        .append(entry.getValue())
                        .append("</").append(entry.getKey()).append(">\n");
            }
            sb.append("</properties>\n");
        }
        sb.append("<dependencyManagement>\n");
        sb.append("    <dependencies>\n");
        for (BomImportInserter.BomImport imp : imports) {
            sb.append("        <dependency>\n");
            sb.append("            <groupId>").append(imp.groupId()).append("</groupId>\n");
            sb.append("            <artifactId>").append(imp.artifactId()).append("</artifactId>\n");
            sb.append("            <version>").append(imp.version()).append("</version>\n");
            sb.append("            <type>pom</type>\n");
            sb.append("            <scope>import</scope>\n");
            sb.append("        </dependency>\n");
        }
        sb.append("    </dependencies>\n");
        sb.append("</dependencyManagement>\n");
        return sb.toString();
    }
}
