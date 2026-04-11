package dev.jka.bommigrate.web.service;

import dev.jka.bommigrate.core.discovery.BomGenerationPlan;
import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.BomModuleAssignment;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
import dev.jka.bommigrate.core.discovery.ScanMetadata;
import dev.jka.bommigrate.core.discovery.VersionFormat;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Holds the in-memory state for a single discovery session. The web server
 * is started per-run (one CLI invocation = one session), so a singleton
 * component is sufficient.
 */
@Component
public class DiscoverySessionService {

    private DiscoveryReport report = DiscoveryReport.empty();
    private ScanMetadata scanMetadata = ScanMetadata.empty();
    private List<Path> scannedPomPaths = new ArrayList<>();
    private List<BomModule> modules = new ArrayList<>();
    private List<BomModuleAssignment> assignments = new ArrayList<>();
    private Path outputDir;
    private String parentGroupId = "com.example";
    private String parentArtifactId = "my-bom";
    private String parentVersion = "1.0.0";
    private VersionFormat versionFormat = VersionFormat.INLINE;
    /**
     * Signature of the session state that was used for the last successful
     * BOM generation. Compared against {@link #computeCurrentSignature()} to
     * detect stale previews after the user edits coordinates, modules or
     * assignments. {@code null} means no generation has happened yet.
     */
    private String lastGeneratedSignature;

    public synchronized DiscoveryReport getReport() {
        return report;
    }

    public synchronized void setReport(DiscoveryReport report) {
        this.report = report;
    }

    public synchronized ScanMetadata getScanMetadata() {
        return scanMetadata;
    }

    public synchronized void setScanMetadata(ScanMetadata scanMetadata) {
        this.scanMetadata = scanMetadata != null ? scanMetadata : ScanMetadata.empty();
    }

    public synchronized List<Path> getScannedPomPaths() {
        return new ArrayList<>(scannedPomPaths);
    }

    public synchronized void setScannedPomPaths(List<Path> scannedPomPaths) {
        this.scannedPomPaths = scannedPomPaths != null ? new ArrayList<>(scannedPomPaths) : new ArrayList<>();
    }

    public synchronized String getParentGroupId() {
        return parentGroupId;
    }

    public synchronized String getParentArtifactId() {
        return parentArtifactId;
    }

    public synchronized String getParentVersion() {
        return parentVersion;
    }

    public synchronized List<BomModule> getModules() {
        return new ArrayList<>(modules);
    }

    public synchronized void setModules(List<BomModule> modules) {
        this.modules = new ArrayList<>(modules);
    }

    public synchronized List<BomModuleAssignment> getAssignments() {
        return new ArrayList<>(assignments);
    }

    public synchronized void setAssignments(List<BomModuleAssignment> assignments) {
        this.assignments = new ArrayList<>(assignments);
    }

    public synchronized BomGenerationPlan buildPlan() {
        return new BomGenerationPlan(
                parentGroupId, parentArtifactId, parentVersion,
                modules, assignments, versionFormat);
    }

    public synchronized VersionFormat getVersionFormat() {
        return versionFormat;
    }

    public synchronized void setVersionFormat(VersionFormat versionFormat) {
        this.versionFormat = versionFormat != null ? versionFormat : VersionFormat.INLINE;
    }

    public synchronized Path getOutputDir() {
        return outputDir;
    }

    public synchronized void setOutputDir(Path outputDir) {
        this.outputDir = outputDir;
    }

    public synchronized void setParentCoordinates(String groupId, String artifactId, String version) {
        this.parentGroupId = groupId;
        this.parentArtifactId = artifactId;
        this.parentVersion = version;
    }

    public synchronized String getLastGeneratedSignature() {
        return lastGeneratedSignature;
    }

    public synchronized void setLastGeneratedSignature(String signature) {
        this.lastGeneratedSignature = signature;
    }

    /**
     * Computes a stable string signature of everything that affects the
     * migration preview: coordinates, version format, modules (by name),
     * and confirmed assignments (GAV + target module). Comparing this to
     * {@link #getLastGeneratedSignature()} tells us whether the on-disk BOM
     * still matches the current session state.
     *
     * <p>The signature is a plain delimited string (not a cryptographic hash)
     * — it never needs to leave the server, so readable is fine and cheaper.
     */
    public synchronized String computeCurrentSignature() {
        StringJoiner sj = new StringJoiner("|");
        sj.add(parentGroupId == null ? "" : parentGroupId);
        sj.add(parentArtifactId == null ? "" : parentArtifactId);
        sj.add(parentVersion == null ? "" : parentVersion);
        sj.add(versionFormat == null ? VersionFormat.INLINE.name() : versionFormat.name());

        StringJoiner modulesPart = new StringJoiner(",");
        for (BomModule module : modules) {
            modulesPart.add(module.name());
        }
        sj.add(modulesPart.toString());

        StringJoiner assignPart = new StringJoiner(",");
        for (BomModuleAssignment assignment : assignments) {
            assignPart.add(assignment.candidate().groupId()
                    + ":" + assignment.candidate().artifactId()
                    + "=" + assignment.confirmedVersion()
                    + "@" + assignment.module().name());
        }
        sj.add(assignPart.toString());
        return sj.toString();
    }
}
