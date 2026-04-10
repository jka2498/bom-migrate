package dev.jka.bommigrate.core.migrator;

import dev.jka.bommigrate.core.model.DependencyManagementMap;
import dev.jka.bommigrate.core.model.MigrationAction;
import dev.jka.bommigrate.core.model.MigrationCandidate;
import dev.jka.bommigrate.core.model.MigrationReport;
import dev.jka.bommigrate.core.model.ResolvedDependency;
import dev.jka.bommigrate.core.resolver.DefaultBomResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PomAnalyzerTest {

    private static DependencyManagementMap simpleBomMap;
    private final PomAnalyzer analyzer = new PomAnalyzer();

    @BeforeAll
    static void loadBom() throws IOException {
        simpleBomMap = new DefaultBomResolver().resolve(
                Path.of("src/test/resources/fixtures/bom/simple-bom.xml"), false);
    }

    private Path targetFixture(String name) {
        return Path.of("src/test/resources/fixtures/target").resolve(name);
    }

    @Test
    void simpleServiceClassification() throws IOException {
        MigrationReport report = analyzer.analyze(
                targetFixture("simple-service-pom.xml"), simpleBomMap);

        assertThat(report.stripCount()).isEqualTo(2);
        assertThat(report.flagCount()).isEqualTo(1);
        assertThat(report.skipCount()).isEqualTo(2);

        // Guava should be STRIP
        assertHasCandidate(report, "com.google.guava", "guava", MigrationAction.STRIP);

        // SLF4J should be STRIP
        assertHasCandidate(report, "org.slf4j", "slf4j-api", MigrationAction.STRIP);

        // Jackson should be FLAG (version mismatch)
        assertHasCandidate(report, "com.fasterxml.jackson.core", "jackson-databind", MigrationAction.FLAG);

        // Commons-lang3 should be SKIP (not in BOM)
        assertHasCandidate(report, "org.apache.commons", "commons-lang3", MigrationAction.SKIP);
    }

    @Test
    void propertyVersionResolution() throws IOException {
        MigrationReport report = analyzer.analyze(
                targetFixture("service-with-property-versions.xml"), simpleBomMap);

        // Guava ${guava.version} resolves to 33.0.0-jre -> STRIP
        assertHasCandidate(report, "com.google.guava", "guava", MigrationAction.STRIP);

        // SLF4J ${slf4j.version} resolves to 2.0.9 -> STRIP
        assertHasCandidate(report, "org.slf4j", "slf4j-api", MigrationAction.STRIP);

        // Jackson ${jackson.version} resolves to 2.15.0, BOM has 2.16.0 -> FLAG
        assertHasCandidate(report, "com.fasterxml.jackson.core", "jackson-databind", MigrationAction.FLAG);
    }

    @Test
    void classifierDistinguishesEntries() throws IOException {
        MigrationReport report = analyzer.analyze(
                targetFixture("service-with-classifier.xml"), simpleBomMap);

        // Guava without classifier -> STRIP
        List<MigrationCandidate> strips = report.byAction(MigrationAction.STRIP);
        assertThat(strips).anyMatch(c -> c.dependency().classifier().isEmpty()
                && c.dependency().artifactId().equals("guava"));

        // Guava with classifier=tests -> SKIP (not in BOM since BOM only has empty classifier)
        List<MigrationCandidate> skips = report.byAction(MigrationAction.SKIP);
        assertThat(skips).anyMatch(c -> c.dependency().classifier().equals("tests")
                && c.dependency().artifactId().equals("guava"));
    }

    @Test
    void versionMismatchFlagged() throws IOException {
        MigrationReport report = analyzer.analyze(
                targetFixture("service-version-mismatch.xml"), simpleBomMap);

        assertThat(report.flagCount()).isEqualTo(2);
        assertThat(report.stripCount()).isZero();

        MigrationCandidate guava = findCandidate(report, "com.google.guava", "guava");
        assertThat(guava.action()).isEqualTo(MigrationAction.FLAG);
        assertThat(guava.reason()).contains("version mismatch");
    }

    @Test
    void duplicateDeclarationsFlagged() throws IOException {
        MigrationReport report = analyzer.analyze(
                targetFixture("service-with-duplicates.xml"), simpleBomMap);

        List<MigrationCandidate> flags = report.byAction(MigrationAction.FLAG);
        assertThat(flags).hasSize(2);
        assertThat(flags).allMatch(c -> c.reason().contains("duplicate"));
    }

    @Test
    void versionRangeSkipped() throws IOException {
        MigrationReport report = analyzer.analyze(
                targetFixture("service-with-version-range.xml"), simpleBomMap);

        MigrationCandidate guava = findCandidate(report, "com.google.guava", "guava");
        assertThat(guava.action()).isEqualTo(MigrationAction.SKIP);
        assertThat(guava.reason()).contains("version range");

        // SLF4J should still be STRIP
        assertHasCandidate(report, "org.slf4j", "slf4j-api", MigrationAction.STRIP);
    }

    @Test
    void hasChangesReflectsStrips() throws IOException {
        MigrationReport report = analyzer.analyze(
                targetFixture("simple-service-pom.xml"), simpleBomMap);
        assertThat(report.hasChanges()).isTrue();

        MigrationReport noChanges = analyzer.analyze(
                targetFixture("service-version-mismatch.xml"), simpleBomMap);
        assertThat(noChanges.hasChanges()).isFalse();
    }

    private void assertHasCandidate(MigrationReport report, String groupId, String artifactId,
                                    MigrationAction expectedAction) {
        MigrationCandidate candidate = findCandidate(report, groupId, artifactId);
        assertThat(candidate.action())
                .as("Expected %s:%s to be %s but was %s: %s",
                        groupId, artifactId, expectedAction, candidate.action(), candidate.reason())
                .isEqualTo(expectedAction);
    }

    private MigrationCandidate findCandidate(MigrationReport report, String groupId, String artifactId) {
        return report.candidates().stream()
                .filter(c -> c.dependency().groupId().equals(groupId)
                        && c.dependency().artifactId().equals(artifactId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No candidate found for " + groupId + ":" + artifactId));
    }
}
