package dev.jka.bommigrate.web.controller;

import dev.jka.bommigrate.core.discovery.DependencyFrequencyAnalyser;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
import dev.jka.bommigrate.core.discovery.ScanMetadata;
import dev.jka.bommigrate.core.model.PomModelReader;
import dev.jka.bommigrate.web.service.DiscoverySessionService;
import org.apache.maven.model.Model;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
     * <p>When {@code paths} is missing, blank, or just a bare filename (no
     * directory separator), the controller reads the uploaded POM's
     * {@code <artifactId>} and uses {@code <artifactId>/pom.xml} as the
     * display name. This is the only way to give meaningful labels to
     * individually-picked files, because the native file picker never
     * exposes the original filesystem path to JavaScript. Falls back to a
     * numbered placeholder ({@code 0/pom.xml}) if the POM can't be parsed.
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
        Set<String> usedDisplayNames = new LinkedHashSet<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file.isEmpty()) {
                continue;
            }

            String originalName = file.getOriginalFilename() != null
                    ? file.getOriginalFilename()
                    : "pom-" + i + ".xml";

            String rawPath = usePaths ? paths.get(i) : null;
            String safePath = rawPath != null && !rawPath.isBlank()
                    ? sanitiseRelativePath(rawPath, i)
                    : null;
            // Informative = the browser gave us a meaningful directory path
            // (webkitRelativePath from the folder picker, typically). Bare
            // "pom.xml" is NOT informative — the regular file picker can't
            // do better than that, and we'd rather show the artifactId.
            boolean informative = safePath != null && safePath.contains("/");

            String displayName;
            Path target;
            if (informative) {
                target = tempRoot.resolve(safePath);
                Files.createDirectories(target.getParent());
                file.transferTo(target.toFile());
                displayName = safePath;
            } else {
                // Numbered subdir: always unique on disk, regardless of how
                // many POMs collide on name.
                Path serviceDir = tempRoot.resolve(String.valueOf(i));
                Files.createDirectories(serviceDir);
                target = serviceDir.resolve(safeFileName(originalName));
                file.transferTo(target.toFile());
                // Now that the file is on disk, try to derive a human-readable
                // display name from its <artifactId>. Falls back to the
                // numbered layout if the POM is unparseable or has no id.
                String artifactDisplay = tryArtifactIdDisplayName(target);
                displayName = artifactDisplay != null ? artifactDisplay : (i + "/pom.xml");
            }

            displayName = disambiguate(displayName, usedDisplayNames);
            usedDisplayNames.add(displayName);

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
     * Reads the POM's {@code <artifactId>} and returns it formatted as
     * {@code artifactId/pom.xml} for display in the scanned-sources panel.
     * Returns {@code null} if the file isn't parseable or has no artifactId
     * — caller should fall back to the numbered layout.
     */
    private String tryArtifactIdDisplayName(Path pomFile) {
        try {
            Model model = PomModelReader.parseModel(pomFile);
            String artifactId = model.getArtifactId();
            if (artifactId == null || artifactId.isBlank()) {
                return null;
            }
            return artifactId + "/pom.xml";
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * If {@code name} is already in {@code used}, appends a parenthesised
     * counter ({@code " (2)"}, {@code " (3)"}, ...) until it's unique. The
     * filesystem path is already guaranteed unique by the caller — this
     * only affects the display string shown in the scanned-sources panel.
     */
    private String disambiguate(String name, Set<String> used) {
        if (!used.contains(name)) {
            return name;
        }
        int suffix = 2;
        String candidate = name + " (" + suffix + ")";
        while (used.contains(candidate)) {
            suffix++;
            candidate = name + " (" + suffix + ")";
        }
        return candidate;
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
