package dev.jka.bommigrate.core.model;

import dev.jka.bommigrate.core.resolver.PropertyInterpolator;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared helper for parsing POMs and building interpolators.
 * Extracted from {@code PomAnalyzer} and {@code DefaultBomResolver} to avoid duplication.
 */
public final class PomModelReader {

    private PomModelReader() {
    }

    /**
     * Parses the given POM file using {@link MavenXpp3Reader}.
     *
     * @param pomFile absolute path to a {@code pom.xml}
     * @return the parsed Maven {@link Model}
     * @throws IOException on parse or I/O errors
     */
    public static Model parseModel(Path pomFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(pomFile)) {
            return new MavenXpp3Reader().read(reader);
        } catch (XmlPullParserException e) {
            throw new IOException("Failed to parse POM: " + pomFile, e);
        }
    }

    /**
     * Builds a {@link PropertyInterpolator} seeded with the model's own properties
     * and project coordinates (groupId/artifactId/version), falling back to parent
     * coordinates if the model inherits them.
     */
    public static PropertyInterpolator buildInterpolator(Model model) {
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
}
