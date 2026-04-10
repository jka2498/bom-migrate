package dev.jka.bommigrate.core.discovery;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the multi-module BOM file structure from a {@link BomGenerationPlan}.
 * Uses {@link MavenXpp3Writer} from the existing {@code maven-model} dependency
 * to produce valid POM XML — no new dependencies needed.
 */
public final class BomGenerator {

    /**
     * Generates all POM files for the BOM structure and returns them as a
     * {@code relativePath → POM XML content} map.
     *
     * <p>Single-module plans produce a single flat BOM (just {@code pom.xml}).
     * Multi-module plans produce an aggregator parent POM plus one child POM per module.
     *
     * @param plan the generation plan
     * @return map from relative file path to POM XML content
     */
    public Map<String, String> generate(BomGenerationPlan plan) {
        Map<String, String> result = new LinkedHashMap<>();

        if (plan.isSingleModule()) {
            result.put("pom.xml", generateSingleModule(plan));
            return result;
        }

        // Multi-module: aggregator parent + one child per module
        result.put("pom.xml", generateAggregatorParent(plan));
        Map<BomModule, List<BomModuleAssignment>> grouped = plan.groupedByModule();
        for (BomModule module : plan.modules()) {
            String childArtifact = plan.parentArtifactId() + "-" + module.name();
            String childPath = childArtifact + "/pom.xml";
            result.put(childPath, generateChildModule(plan, module, grouped.get(module), childArtifact));
        }
        return result;
    }

    /**
     * Writes the generated BOM files to {@code outputDir}, creating subdirectories as needed.
     */
    public List<Path> writeTo(BomGenerationPlan plan, Path outputDir) throws IOException {
        Map<String, String> files = generate(plan);
        List<Path> written = new ArrayList<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path target = outputDir.resolve(entry.getKey());
            Files.createDirectories(target.getParent() != null ? target.getParent() : outputDir);
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
            written.add(target);
        }
        return written;
    }

    // --- internal POM builders ---

    private String generateSingleModule(BomGenerationPlan plan) {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId(plan.parentGroupId());
        model.setArtifactId(plan.parentArtifactId());
        model.setVersion(plan.parentVersion());
        model.setPackaging("pom");

        DependencyManagement depMgmt = new DependencyManagement();
        List<BomModuleAssignment> sorted = new ArrayList<>(plan.assignments());
        sorted.sort(Comparator.comparing(a -> a.candidate().key()));
        for (BomModuleAssignment assignment : sorted) {
            depMgmt.addDependency(toDependency(assignment));
        }
        model.setDependencyManagement(depMgmt);

        return writeModel(model);
    }

    private String generateAggregatorParent(BomGenerationPlan plan) {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId(plan.parentGroupId());
        model.setArtifactId(plan.parentArtifactId());
        model.setVersion(plan.parentVersion());
        model.setPackaging("pom");

        for (BomModule module : plan.modules()) {
            String childArtifact = plan.parentArtifactId() + "-" + module.name();
            model.addModule(childArtifact);
        }

        return writeModel(model);
    }

    private String generateChildModule(BomGenerationPlan plan, BomModule module,
                                       List<BomModuleAssignment> assignments, String childArtifactId) {
        Model model = new Model();
        model.setModelVersion("4.0.0");

        Parent parent = new Parent();
        parent.setGroupId(plan.parentGroupId());
        parent.setArtifactId(plan.parentArtifactId());
        parent.setVersion(plan.parentVersion());
        parent.setRelativePath("../pom.xml");
        model.setParent(parent);

        model.setArtifactId(childArtifactId);
        model.setPackaging("pom");

        DependencyManagement depMgmt = new DependencyManagement();
        List<BomModuleAssignment> sorted = new ArrayList<>(
                assignments != null ? assignments : List.of());
        sorted.sort(Comparator.comparing(a -> a.candidate().key()));
        for (BomModuleAssignment assignment : sorted) {
            depMgmt.addDependency(toDependency(assignment));
        }
        model.setDependencyManagement(depMgmt);

        return writeModel(model);
    }

    private Dependency toDependency(BomModuleAssignment assignment) {
        Dependency dep = new Dependency();
        dep.setGroupId(assignment.candidate().groupId());
        dep.setArtifactId(assignment.candidate().artifactId());
        dep.setVersion(assignment.confirmedVersion());
        return dep;
    }

    private String writeModel(Model model) {
        try {
            StringWriter sw = new StringWriter();
            new MavenXpp3Writer().write(sw, model);
            return sw.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
