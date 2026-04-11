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
}
