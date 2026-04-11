package dev.jka.bommigrate.web.service;

import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.ScanMetadata;
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
import java.util.List;

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

        DependencyManagementMap bomMap = bomResolver.resolve(outputDir, true);

        List<BomImportInserter.BomImport> imports = buildBomImports();

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
            String finalContent = bomImportInserter.insertImports(stripped, imports);

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

        String snippet = buildBomImportSnippet(imports);
        return new MigrationPreviewResponse(snippet, services);
    }

    /**
     * Builds the list of BOM imports to insert. For single-module plans this is
     * the parent BOM. For multi-module plans it's one entry per child module
     * (the aggregator parent itself is not importable — it's a build aggregator).
     */
    private List<BomImportInserter.BomImport> buildBomImports() {
        List<BomModule> modules = session.getModules();
        String groupId = session.getParentGroupId();
        String version = session.getParentVersion();

        // Single-module: import the flat BOM directly
        if (modules == null || modules.size() <= 1) {
            return List.of(new BomImportInserter.BomImport(
                    groupId, session.getParentArtifactId(), version));
        }

        // Multi-module: import each child module (artifactId == module name, no prefix)
        List<BomImportInserter.BomImport> out = new ArrayList<>();
        for (BomModule module : modules) {
            out.add(new BomImportInserter.BomImport(groupId, module.name(), version));
        }
        return out;
    }

    /**
     * Builds the copy-pasteable {@code <dependencyManagement>} snippet for
     * the "Import this BOM" panel. Multi-module plans get one {@code <dependency>}
     * per child BOM.
     */
    private String buildBomImportSnippet(List<BomImportInserter.BomImport> imports) {
        StringBuilder sb = new StringBuilder();
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
