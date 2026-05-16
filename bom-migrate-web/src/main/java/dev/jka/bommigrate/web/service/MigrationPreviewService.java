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
import dev.jka.bommigrate.core.resolver.ServiceBomMatcher;
import dev.jka.bommigrate.web.dto.DiffLine;
import dev.jka.bommigrate.web.dto.FlaggedDependency;
import dev.jka.bommigrate.web.dto.MigrationPreviewResponse;
import dev.jka.bommigrate.web.dto.ServicePreview;
import dev.jka.bommigrate.web.dto.VersionChange;
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
    private final ServiceBomMatcher bomMatcher = new ServiceBomMatcher();
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

        DependencyManagementMap fullBomMap = bomResolver.resolve(outputDir, true);

        // Multi-module BOM metadata for per-service filtering
        String bomGroupId = session.getParentGroupId();
        String bomArtifactId = session.getParentArtifactId();
        List<BomModule> modules = session.getModules();
        List<String> childModuleNames = modules != null && modules.size() > 1
                ? modules.stream().map(BomModule::name).toList()
                : List.of();

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

            // Filter BOM map to only child modules this service imports
            ServiceBomMatcher.MatchResult matchResult = bomMatcher.match(
                    pom, outputDir, fullBomMap, bomGroupId, bomArtifactId, childModuleNames);
            DependencyManagementMap bomMap = matchResult.effectiveBomMap();

            // forceStripMismatches=true: the user already confirmed versions in the
            // candidates table, so version mismatches should be stripped (not flagged).
            MigrationReport report = pomAnalyzer.analyze(pom, bomMap, true);
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

            List<VersionChange> versionChanges = new ArrayList<>();
            for (MigrationCandidate candidate : report.byAction(MigrationAction.STRIP)) {
                if (candidate.reason() != null && candidate.reason().startsWith("version changed:")) {
                    versionChanges.add(new VersionChange(
                            candidate.dependency().groupId(),
                            candidate.dependency().artifactId(),
                            candidate.dependency().version(),
                            candidate.bomVersion()
                    ));
                }
            }

            services.add(new ServicePreview(
                    display,
                    report.stripCount(),
                    report.flagCount(),
                    report.skipCount(),
                    finalContent,
                    diffLines,
                    flagged,
                    versionChanges
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
     * Builds the copy-pasteable snippet for the "Import this BOM" panel.
     *
     * <p>When plugin assignments exist, the snippet shows {@code <parent>}
     * usage — because Maven's BOM import ({@code <scope>import</scope>})
     * only covers {@code <dependencyManagement>}, not {@code <pluginManagement>}.
     * Using the BOM as a parent gives services both.
     *
     * <p>For multi-module BOMs with plugins, the snippet shows both:
     * a {@code <parent>} for the aggregator (plugins) and
     * {@code <scope>import</scope>} entries for child modules (deps).
     */
    private String buildBomImportSnippet(List<BomImportInserter.BomImport> imports,
                                          Map<String, String> propertiesToAdd) {
        boolean hasPlugins = !session.getPluginAssignments().isEmpty();
        List<BomModule> modules = session.getModules();
        boolean isMultiModule = modules != null && modules.size() > 1;

        StringBuilder sb = new StringBuilder();

        if (hasPlugins) {
            // Option 1: parent (gets everything)
            sb.append("<!-- Option 1: Use as parent (inherits dependency + plugin management) -->\n");
            sb.append("<parent>\n");
            sb.append("    <groupId>").append(session.getParentGroupId()).append("</groupId>\n");
            sb.append("    <artifactId>").append(session.getParentArtifactId()).append("</artifactId>\n");
            sb.append("    <version>").append(session.getParentVersion()).append("</version>\n");
            sb.append("    <relativePath/>\n");
            sb.append("</parent>\n");
            sb.append("\n");

            // Option 2: BOM import (deps only)
            sb.append("<!-- Option 2: Import as BOM (dependency management only, plugins need manual version) -->\n");
        }

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
