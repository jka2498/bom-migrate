package dev.jka.bommigrate.web.controller;

import dev.jka.bommigrate.core.discovery.BomGenerationPlan;
import dev.jka.bommigrate.core.discovery.BomGenerator;
import dev.jka.bommigrate.web.service.DiscoverySessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint for generating the BOM file structure from the current session state.
 */
@RestController
@RequestMapping("/api/bom")
public class BomController {

    private final DiscoverySessionService session;
    private final BomGenerator generator = new BomGenerator();

    public BomController(DiscoverySessionService session) {
        this.session = session;
    }

    public record GenerateResponse(Map<String, String> files, List<String> written) {}

    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate() throws IOException {
        BomGenerationPlan plan = session.buildPlan();
        Map<String, String> files = generator.generate(plan);

        List<String> writtenPaths = List.of();
        Path outputDir = session.getOutputDir();
        if (outputDir != null) {
            writtenPaths = generator.writeTo(plan, outputDir).stream()
                    .map(Path::toString)
                    .toList();
        }

        return ResponseEntity.ok(new GenerateResponse(files, writtenPaths));
    }
}
