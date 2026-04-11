package dev.jka.bommigrate.web.service;

import dev.jka.bommigrate.core.discovery.ScanMetadata;
import dev.jka.bommigrate.core.migrator.PomAnalyzer;
import dev.jka.bommigrate.core.migrator.PomWriter;
import dev.jka.bommigrate.core.model.DependencyManagementMap;
import dev.jka.bommigrate.core.model.MigrationAction;
import dev.jka.bommigrate.core.model.MigrationCandidate;
import dev.jka.bommigrate.core.model.MigrationReport;
import dev.jka.bommigrate.core.resolver.DefaultBomResolver;
import dev.jka.bommigrate.web.dto.FlaggedDependency;
import dev.jka.bommigrate.web.dto.MigrationPreviewResponse;
import dev.jka.bommigrate.web.dto.ServicePreview;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Composes the existing core APIs ({@link DefaultBomResolver}, {@link PomAnalyzer},
 * {@link PomWriter}) to produce a per-service migration preview: for each scanned
 * POM, what it would look like if migrated against the just-generated BOM.
 *
 * <p>Preview only — never writes back to the service POMs.
 */
@Component
public class MigrationPreviewService {

    private final DiscoverySessionService session;
    private final DefaultBomResolver bomResolver = new DefaultBomResolver();
    private final PomAnalyzer pomAnalyzer = new PomAnalyzer();
    private final PomWriter pomWriter = new PomWriter();

    public MigrationPreviewService(DiscoverySessionService session) {
        this.session = session;
    }

    /**
     * Builds the full preview response for the current session state.
     *
     * @throws IllegalStateException if no BOM has been generated yet
     * @throws IOException           on parse/read errors against the generated BOM or service POMs
     */
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

        List<Path> servicePoms = session.getScannedPomPaths();
        ScanMetadata metadata = session.getScanMetadata();
        List<String> displayNames = metadata.scannedSources();

        List<ServicePreview> services = new ArrayList<>();
        for (int i = 0; i < servicePoms.size(); i++) {
            Path pom = servicePoms.get(i);
            String display = i < displayNames.size() ? displayNames.get(i) : pom.getFileName().toString();

            MigrationReport report = pomAnalyzer.analyze(pom, bomMap);
            String modified = pomWriter.applyStrips(pom, report);

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
                    modified,
                    flagged
            ));
        }

        String snippet = buildBomImportSnippet();
        return new MigrationPreviewResponse(snippet, services);
    }

    private String buildBomImportSnippet() {
        return """
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>%s</groupId>
                            <artifactId>%s</artifactId>
                            <version>%s</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                """.formatted(
                session.getParentGroupId(),
                session.getParentArtifactId(),
                session.getParentVersion()
        );
    }
}
