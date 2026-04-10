package dev.jka.bommigrate.core.migrator;

import dev.jka.bommigrate.core.model.DependencyManagementMap;
import dev.jka.bommigrate.core.model.MigrationReport;
import dev.jka.bommigrate.core.resolver.DefaultBomResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PomWriterTest {

    private static DependencyManagementMap simpleBomMap;
    private final PomAnalyzer analyzer = new PomAnalyzer();
    private final PomWriter writer = new PomWriter();

    @TempDir
    Path tempDir;

    @BeforeAll
    static void loadBom() throws IOException {
        simpleBomMap = new DefaultBomResolver().resolve(
                Path.of("src/test/resources/fixtures/bom/simple-bom.xml"), false);
    }

    @Test
    void applyStripsMatchesExpectedOutput() throws IOException {
        Path targetPom = Path.of("src/test/resources/fixtures/target/simple-service-pom.xml");
        Path expectedPom = Path.of("src/test/resources/fixtures/expected/simple-service-pom-migrated.xml");

        MigrationReport report = analyzer.analyze(targetPom, simpleBomMap);
        String modified = writer.applyStrips(targetPom, report);

        String expected = Files.readString(expectedPom);
        assertThat(modified).isEqualTo(expected);
    }

    @Test
    void applyStripsWithPropertyVersions() throws IOException {
        Path targetPom = Path.of("src/test/resources/fixtures/target/service-with-property-versions.xml");
        Path expectedPom = Path.of("src/test/resources/fixtures/expected/service-with-property-versions-migrated.xml");

        MigrationReport report = analyzer.analyze(targetPom, simpleBomMap);
        String modified = writer.applyStrips(targetPom, report);

        String expected = Files.readString(expectedPom);
        assertThat(modified).isEqualTo(expected);
    }

    @Test
    void writeToCreatesFile() throws IOException {
        Path targetPom = Path.of("src/test/resources/fixtures/target/simple-service-pom.xml");
        MigrationReport report = analyzer.analyze(targetPom, simpleBomMap);
        String modified = writer.applyStrips(targetPom, report);

        Path outputFile = tempDir.resolve("output-pom.xml");
        writer.writeTo(outputFile, modified);

        assertThat(outputFile).exists();
        assertThat(Files.readString(outputFile)).isEqualTo(modified);
    }

    @Test
    void noStripsProducesUnchangedOutput() throws IOException {
        Path targetPom = Path.of("src/test/resources/fixtures/target/service-version-mismatch.xml");
        MigrationReport report = analyzer.analyze(targetPom, simpleBomMap);

        assertThat(report.hasChanges()).isFalse();

        String modified = writer.applyStrips(targetPom, report);
        String original = Files.readString(targetPom);
        assertThat(modified).isEqualTo(original);
    }

    @Test
    void generateDiffShowsRemovedLines() throws IOException {
        Path targetPom = Path.of("src/test/resources/fixtures/target/simple-service-pom.xml");
        MigrationReport report = analyzer.analyze(targetPom, simpleBomMap);

        String diff = writer.generateDiff(targetPom, report);
        assertThat(diff).contains("- ");
        assertThat(diff).contains("<version>33.0.0-jre</version>");
        assertThat(diff).contains("<version>2.0.9</version>");
    }
}
