package dev.jka.bommigrate.web.service;

import dev.jka.bommigrate.core.discovery.BomGenerationPlan;
import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.BomModuleAssignment;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
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

    private DiscoveryReport report;
    private List<BomModule> modules = new ArrayList<>();
    private List<BomModuleAssignment> assignments = new ArrayList<>();
    private Path outputDir;
    private String parentGroupId = "com.example";
    private String parentArtifactId = "my-bom";
    private String parentVersion = "1.0.0";

    public synchronized DiscoveryReport getReport() {
        return report;
    }

    public synchronized void setReport(DiscoveryReport report) {
        this.report = report;
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
                modules, assignments);
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
