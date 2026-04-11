package dev.jka.bommigrate.web.controller;

import dev.jka.bommigrate.core.discovery.DependencyFrequencyAnalyser;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
import dev.jka.bommigrate.core.discovery.ScanMetadata;
import dev.jka.bommigrate.web.service.DiscoverySessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Accepts native file uploads from the web UI's file picker. Writes each
 * uploaded POM to a per-run temp directory, runs discovery analysis,
 * and seeds the session with the resulting report and real filesystem paths.
 *
 * <p>The uploaded files live in a temp dir and are never written back to. The
 * user's view of the modified POMs is copy-paste only.
 */
@RestController
@RequestMapping("/api/scan")
public class ScanUploadController {

    private final DiscoverySessionService session;
    private final DependencyFrequencyAnalyser analyser = new DependencyFrequencyAnalyser();

    public ScanUploadController(DiscoverySessionService session) {
        this.session = session;
    }

    @PostMapping("/upload")
    public ResponseEntity<DiscoveryReport> uploadAndScan(
            @RequestParam("files") List<MultipartFile> files
    ) throws IOException {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // Fresh temp dir for this upload batch
        Path tempRoot = Files.createTempDirectory("bom-migrate-upload-");

        List<Path> writtenPoms = new ArrayList<>();
        List<String> displayNames = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file.isEmpty()) {
                continue;
            }

            String originalName = file.getOriginalFilename() != null
                    ? file.getOriginalFilename()
                    : "pom-" + i + ".xml";

            // Put each file in its own subdirectory to avoid name collisions
            // when multiple "pom.xml" files are uploaded
            Path serviceDir = tempRoot.resolve(String.valueOf(i));
            Files.createDirectories(serviceDir);
            Path target = serviceDir.resolve(safeFileName(originalName));
            file.transferTo(target.toFile());

            writtenPoms.add(target);
            displayNames.add(originalName);
        }

        DiscoveryReport report = analyser.analyse(writtenPoms);

        session.setReport(report);
        session.setScannedPomPaths(writtenPoms);
        session.setScanMetadata(ScanMetadata.localOnly(displayNames));
        // Reset any prior assignments since the scan changed
        session.setAssignments(List.of());

        return ResponseEntity.ok(report);
    }

    /**
     * Strips any path separators or unsafe chars from an uploaded filename.
     * Prevents accidental directory traversal via crafted Content-Disposition.
     */
    private String safeFileName(String name) {
        String base = name.replaceAll(".*[/\\\\]", "");
        if (base.isBlank()) {
            return "pom.xml";
        }
        return base;
    }
}
