package dev.jka.bommigrate.core.resolver;

import dev.jka.bommigrate.core.model.DependencyManagementMap;
import dev.jka.bommigrate.core.model.ResolvedDependency;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of {@link BomResolver} that parses BOM POMs using
 * {@link MavenXpp3Reader}, resolves properties, and handles multi-module structures.
 */
public final class DefaultBomResolver implements BomResolver {

    @Override
    public DependencyManagementMap resolve(Path bomPath, boolean includeTransitiveBomImports) throws IOException {
        Path pomFile = toPomFile(bomPath);
        Model model = parseModel(pomFile);

        PropertyInterpolator interpolator = buildInterpolator(model);

        List<ResolvedDependency> allDeps = new ArrayList<>();

        // Collect deps from this POM's dependencyManagement
        allDeps.addAll(extractManagedDependencies(model, interpolator, includeTransitiveBomImports, pomFile.getParent()));

        // If multi-module, recurse into child modules
        if (model.getModules() != null) {
            for (String moduleName : model.getModules()) {
                Path childPomDir = pomFile.getParent().resolve(moduleName);
                Path childPom = toPomFile(childPomDir);
                if (Files.exists(childPom)) {
                    Model childModel = parseModel(childPom);
                    PropertyInterpolator childInterpolator = buildInterpolator(childModel);
                    // Inherit parent properties
                    childInterpolator.withAdditionalProperties(model.getProperties());
                    allDeps.addAll(extractManagedDependencies(childModel, childInterpolator,
                            includeTransitiveBomImports, childPom.getParent()));
                }
            }
        }

        return new DependencyManagementMap(allDeps);
    }

    private List<ResolvedDependency> extractManagedDependencies(
            Model model,
            PropertyInterpolator interpolator,
            boolean includeTransitiveBomImports,
            Path basedir) throws IOException {

        List<ResolvedDependency> result = new ArrayList<>();
        DependencyManagement depMgmt = model.getDependencyManagement();
        if (depMgmt == null || depMgmt.getDependencies() == null) {
            return result;
        }

        for (Dependency dep : depMgmt.getDependencies()) {
            // Check if this is a BOM import
            if ("pom".equals(dep.getType()) && "import".equals(dep.getScope())) {
                if (includeTransitiveBomImports) {
                    result.addAll(resolveTransitiveImport(dep, interpolator, basedir));
                }
                continue;
            }

            String resolvedVersion = interpolator.interpolate(dep.getVersion());
            result.add(ResolvedDependency.fromMavenDependency(dep, resolvedVersion));
        }

        return result;
    }

    private List<ResolvedDependency> resolveTransitiveImport(
            Dependency importDep,
            PropertyInterpolator interpolator,
            Path basedir) throws IOException {

        // Try to find the imported BOM on the filesystem by artifactId
        // This handles the common case where the BOM is a sibling module
        String artifactId = interpolator.interpolate(importDep.getArtifactId());
        Path candidatePath = basedir.resolve(artifactId);

        if (Files.isDirectory(candidatePath)) {
            Path candidatePom = candidatePath.resolve("pom.xml");
            if (Files.exists(candidatePom)) {
                Model importedModel = parseModel(candidatePom);
                PropertyInterpolator importedInterpolator = buildInterpolator(importedModel);
                return extractManagedDependencies(importedModel, importedInterpolator, false, candidatePom.getParent());
            }
        }

        // Also try the parent directory (imported BOM might be a sibling at parent level)
        Path parentCandidate = basedir.getParent();
        if (parentCandidate != null) {
            Path siblingPath = parentCandidate.resolve(artifactId);
            if (Files.isDirectory(siblingPath)) {
                Path siblingPom = siblingPath.resolve("pom.xml");
                if (Files.exists(siblingPom)) {
                    Model importedModel = parseModel(siblingPom);
                    PropertyInterpolator importedInterpolator = buildInterpolator(importedModel);
                    return extractManagedDependencies(importedModel, importedInterpolator, false, siblingPom.getParent());
                }
            }
        }

        // Cannot resolve remote BOM imports in V1 — skip with a message
        System.err.println("[WARN] Cannot resolve BOM import " + importDep.getGroupId()
                + ":" + artifactId + " — not found on filesystem. Skipping.");
        return List.of();
    }

    private PropertyInterpolator buildInterpolator(Model model) {
        PropertyInterpolator interpolator = new PropertyInterpolator(model.getProperties());
        interpolator.withProjectCoordinates(
                model.getGroupId() != null ? model.getGroupId()
                        : (model.getParent() != null ? model.getParent().getGroupId() : null),
                model.getArtifactId(),
                model.getVersion() != null ? model.getVersion()
                        : (model.getParent() != null ? model.getParent().getVersion() : null)
        );
        return interpolator;
    }

    static Model parseModel(Path pomFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(pomFile)) {
            return new MavenXpp3Reader().read(reader);
        } catch (XmlPullParserException e) {
            throw new IOException("Failed to parse POM: " + pomFile, e);
        }
    }

    private Path toPomFile(Path path) {
        if (Files.isDirectory(path)) {
            return path.resolve("pom.xml");
        }
        return path;
    }
}
