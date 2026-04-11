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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Generates the multi-module BOM file structure from a {@link BomGenerationPlan}.
 * Uses {@link MavenXpp3Writer} from the existing {@code maven-model} dependency
 * to produce valid POM XML — no new dependencies needed.
 *
 * <p>Supports two version formats:
 * <ul>
 *   <li>{@link VersionFormat#INLINE} — versions written directly on each {@code <dependency>}</li>
 *   <li>{@link VersionFormat#PROPERTIES} — versions extracted to {@code <properties>}
 *       and referenced via {@code ${artifactId.version}}</li>
 * </ul>
 *
 * <p>Child module artifactIds use the user-provided module names directly,
 * with no parent-artifactId prefix.
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
            String childArtifact = module.name();
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

        List<BomModuleAssignment> sorted = sortedAssignments(plan.assignments());
        applyVersionFormat(model, sorted, plan.versionFormat());

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
            model.addModule(module.name());
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

        List<BomModuleAssignment> sorted = sortedAssignments(
                assignments != null ? assignments : List.of());
        applyVersionFormat(model, sorted, plan.versionFormat());

        return writeModel(model);
    }

    /**
     * Builds the {@code <dependencyManagement>} section (and {@code <properties>}
     * section if applicable) according to the chosen format.
     */
    private void applyVersionFormat(Model model, List<BomModuleAssignment> assignments, VersionFormat format) {
        DependencyManagement depMgmt = new DependencyManagement();

        if (format == VersionFormat.PROPERTIES) {
            // Build property name → version map and per-assignment property name lookup
            Map<String, String> propertyValues = new LinkedHashMap<>(); // propName → version
            Map<BomModuleAssignment, String> propertyNameByAssignment = new LinkedHashMap<>();
            Set<String> usedPropertyNames = new HashSet<>();

            for (BomModuleAssignment assignment : assignments) {
                String propName = derivePropertyName(assignment, usedPropertyNames);
                usedPropertyNames.add(propName);
                propertyValues.put(propName, assignment.confirmedVersion());
                propertyNameByAssignment.put(assignment, propName);
            }

            Properties props = new Properties();
            // LinkedHashMap preserves insertion order, but Properties uses a Hashtable —
            // we add entries in order to keep the writer's output deterministic
            for (Map.Entry<String, String> entry : propertyValues.entrySet()) {
                props.setProperty(entry.getKey(), entry.getValue());
            }
            model.setProperties(props);

            for (BomModuleAssignment assignment : assignments) {
                Dependency dep = new Dependency();
                dep.setGroupId(assignment.candidate().groupId());
                dep.setArtifactId(assignment.candidate().artifactId());
                dep.setVersion("${" + propertyNameByAssignment.get(assignment) + "}");
                depMgmt.addDependency(dep);
            }
        } else {
            // INLINE
            for (BomModuleAssignment assignment : assignments) {
                Dependency dep = new Dependency();
                dep.setGroupId(assignment.candidate().groupId());
                dep.setArtifactId(assignment.candidate().artifactId());
                dep.setVersion(assignment.confirmedVersion());
                depMgmt.addDependency(dep);
            }
        }

        model.setDependencyManagement(depMgmt);
    }

    /**
     * Derives a property name from an assignment. Default form: {@code artifactId.version}.
     * On collision (rare — different groups, same artifactId), falls back to
     * {@code groupId.artifactId.version}.
     */
    private String derivePropertyName(BomModuleAssignment assignment, Set<String> usedNames) {
        String simple = assignment.candidate().artifactId() + ".version";
        if (!usedNames.contains(simple)) {
            return simple;
        }
        return assignment.candidate().groupId() + "." + assignment.candidate().artifactId() + ".version";
    }

    private List<BomModuleAssignment> sortedAssignments(List<BomModuleAssignment> input) {
        List<BomModuleAssignment> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing(a -> a.candidate().key()));
        return sorted;
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
