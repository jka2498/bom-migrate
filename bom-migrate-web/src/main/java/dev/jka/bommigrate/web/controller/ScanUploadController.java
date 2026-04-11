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

    /**
     * Accepts uploaded POM files from the web UI. Supports an optional
     * sibling {@code paths} parameter (one entry per file) that carries the
     * relative path the file had on disk when the user picked it — e.g.
     * {@code service-a/pom.xml}, {@code service-b/pom.xml} — so the preview
     * UI can show meaningful labels even though every POM is literally named
     * {@code pom.xml}. Browsers populate {@code webkitRelativePath} for files
     * chosen via a directory picker; the frontend forwards that verbatim.
     *
     * <p>When {@code paths} is absent or the lengths don't match, the
     * controller falls back to the previous numbered-subdirectory layout.
     */
    @PostMapping("/upload")
    public ResponseEntity<DiscoveryReport> uploadAndScan(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "paths", required = false) List<String> paths
    ) throws IOException {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        boolean usePaths = paths != null && paths.size() == files.size();

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

            String displayName;
            Path target;
            if (usePaths) {
                String rawPath = paths.get(i);
                String safePath = sanitiseRelativePath(rawPath, i);
                target = tempRoot.resolve(safePath);
                Files.createDirectories(target.getParent());
                displayName = safePath;
            } else {
                // Legacy layout: put each file in its own numbered subdir to avoid
                // name collisions when multiple "pom.xml" files are uploaded.
                Path serviceDir = tempRoot.resolve(String.valueOf(i));
                Files.createDirectories(serviceDir);
                target = serviceDir.resolve(safeFileName(originalName));
                displayName = originalName;
            }
            file.transferTo(target.toFile());

            writtenPoms.add(target);
            displayNames.add(displayName);
        }

        DiscoveryReport report = analyser.analyse(writtenPoms);

        session.setReport(report);
        session.setScannedPomPaths(writtenPoms);
        session.setScanMetadata(ScanMetadata.localOnly(displayNames));
        // Reset any prior assignments since the scan changed
        session.setAssignments(List.of());
        // Invalidate any previously-generated BOM — it was built against a
        // different set of inputs. See DiscoverySessionService#computeCurrentSignature.
        session.setLastGeneratedSignature(null);

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

    /**
     * Turns a user-supplied relative path (from {@code webkitRelativePath})
     * into a safe sub-path under the temp dir. Rejects traversal segments
     * and backslashes, normalises leading slashes, and falls back to a
     * numbered path if the result is empty. The returned string is both
     * the filesystem sub-path and the display name — so the UI sees the
     * same thing the backend stored.
     */
    private String sanitiseRelativePath(String raw, int index) {
        if (raw == null || raw.isBlank()) {
            return index + "/pom.xml";
        }
        String normalised = raw.replace('\\', '/');
        // Strip leading slashes
        while (normalised.startsWith("/")) {
            normalised = normalised.substring(1);
        }
        // Reject path traversal
        for (String segment : normalised.split("/")) {
            if (segment.equals("..")) {
                return index + "/pom.xml";
            }
        }
        if (normalised.isBlank()) {
            return index + "/pom.xml";
        }
        return normalised;
    }
}
