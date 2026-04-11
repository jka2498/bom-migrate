package dev.jka.bommigrate.web.controller;

import dev.jka.bommigrate.core.discovery.BomGenerationPlan;
import dev.jka.bommigrate.core.discovery.BomGenerator;
import dev.jka.bommigrate.core.discovery.CoordinateSuggester;
import dev.jka.bommigrate.core.discovery.VersionFormat;
import dev.jka.bommigrate.web.service.DiscoverySessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for BOM generation and version-format configuration.
 */
@RestController
@RequestMapping("/api/bom")
public class BomController {

    private final DiscoverySessionService session;
    private final BomGenerator generator = new BomGenerator();
    private final CoordinateSuggester coordinateSuggester = new CoordinateSuggester();

    public BomController(DiscoverySessionService session) {
        this.session = session;
    }

    public record GenerateRequest(VersionFormat versionFormat) {}

    public record GenerateResponse(Map<String, String> files, List<String> written) {}

    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(@RequestBody(required = false) GenerateRequest request) throws IOException {
        if (request != null && request.versionFormat() != null) {
            session.setVersionFormat(request.versionFormat());
        }

        BomGenerationPlan plan = session.buildPlan();
        Map<String, String> files = generator.generate(plan);

        List<String> writtenPaths = List.of();
        Path outputDir = session.getOutputDir();
        if (outputDir != null) {
            writtenPaths = generator.writeTo(plan, outputDir).stream()
                    .map(Path::toString)
                    .toList();
        }

        // Snapshot the session state that produced this BOM. MigrationPreviewService
        // compares this to the current signature on every preview and rejects the
        // request if they diverge — that's how the UI knows the BOM is stale.
        session.setLastGeneratedSignature(session.computeCurrentSignature());

        return ResponseEntity.ok(new GenerateResponse(files, writtenPaths));
    }

    public record VersionFormatResponse(VersionFormat versionFormat) {}

    @GetMapping("/version-format")
    public ResponseEntity<VersionFormatResponse> getVersionFormat() {
        return ResponseEntity.ok(new VersionFormatResponse(session.getVersionFormat()));
    }

    @PostMapping("/version-format")
    public ResponseEntity<VersionFormatResponse> setVersionFormat(@RequestBody VersionFormatResponse request) {
        session.setVersionFormat(request.versionFormat());
        return ResponseEntity.ok(new VersionFormatResponse(session.getVersionFormat()));
    }

    public record CoordinatesResponse(String groupId, String artifactId, String version) {}

    /**
     * Returns the current BOM coordinates. If the session still has the
     * built-in defaults, suggest a better groupId derived from the scanned
     * POMs' common prefix so the UI can pre-fill the form.
     */
    @GetMapping("/coordinates")
    public ResponseEntity<CoordinatesResponse> getCoordinates() {
        String groupId = session.getParentGroupId();
        String artifactId = session.getParentArtifactId();
        String version = session.getParentVersion();

        if ("com.example".equals(groupId) && "my-bom".equals(artifactId) && "1.0.0".equals(version)) {
            CoordinateSuggester.Suggestion suggestion = coordinateSuggester.suggest(session.getScannedPomPaths());
            return ResponseEntity.ok(new CoordinatesResponse(
                    suggestion.groupId(), suggestion.artifactId(), suggestion.version()));
        }
        return ResponseEntity.ok(new CoordinatesResponse(groupId, artifactId, version));
    }

    @PostMapping("/coordinates")
    public ResponseEntity<CoordinatesResponse> setCoordinates(@RequestBody CoordinatesResponse request) {
        session.setParentCoordinates(request.groupId(), request.artifactId(), request.version());
        return ResponseEntity.ok(new CoordinatesResponse(
                session.getParentGroupId(),
                session.getParentArtifactId(),
                session.getParentVersion()));
    }
}
