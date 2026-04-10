package dev.jka.bommigrate.core.discovery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyFrequencyAnalyserTest {

    private static final Path SERVICES_DIR = Path.of("src/test/resources/fixtures/discovery/services");

    private final DependencyFrequencyAnalyser analyser = new DependencyFrequencyAnalyser();

    @Test
    void analyseRanksGuavaAsTopCandidate() throws IOException {
        DiscoveryReport report = analyser.analyse(List.of(
                SERVICES_DIR.resolve("service-a.xml"),
                SERVICES_DIR.resolve("service-b.xml"),
                SERVICES_DIR.resolve("service-c.xml")
        ));

        assertThat(report.totalServicesScanned()).isEqualTo(3);

        BomCandidate guava = findByKey(report, "com.google.guava:guava");
        assertThat(guava.serviceCount()).isEqualTo(3);
        assertThat(guava.conflictSeverity()).isEqualTo(ConflictSeverity.NONE);
        assertThat(guava.suggestedVersion()).isEqualTo("33.0.0-jre");

        // Guava should be the top-ranked candidate (universal + consistent)
        assertThat(report.candidates().get(0).key()).isEqualTo("com.google.guava:guava");
    }

    @Test
    void detectsMajorVersionConflictForSlf4j() throws IOException {
        DiscoveryReport report = analyser.analyse(List.of(
                SERVICES_DIR.resolve("service-a.xml"),
                SERVICES_DIR.resolve("service-b.xml"),
                SERVICES_DIR.resolve("service-c.xml")
        ));

        BomCandidate slf4j = findByKey(report, "org.slf4j:slf4j-api");
        assertThat(slf4j.serviceCount()).isEqualTo(3);
        assertThat(slf4j.conflictSeverity()).isEqualTo(ConflictSeverity.MAJOR);
        assertThat(slf4j.versions()).containsEntry("2.0.9", 2);
        assertThat(slf4j.versions()).containsEntry("1.7.36", 1);
    }

    @Test
    void detectsMinorVersionConflictForJackson() throws IOException {
        DiscoveryReport report = analyser.analyse(List.of(
                SERVICES_DIR.resolve("service-a.xml"),
                SERVICES_DIR.resolve("service-b.xml"),
                SERVICES_DIR.resolve("service-c.xml")
        ));

        BomCandidate jackson = findByKey(report, "com.fasterxml.jackson.core:jackson-databind");
        assertThat(jackson.serviceCount()).isEqualTo(2);
        assertThat(jackson.conflictSeverity()).isEqualTo(ConflictSeverity.MINOR);
    }

    @Test
    void resolvesPropertyBasedVersions() throws IOException {
        DiscoveryReport report = analyser.analyse(List.of(
                SERVICES_DIR.resolve("service-c.xml")
        ));

        BomCandidate guava = findByKey(report, "com.google.guava:guava");
        // service-c uses ${guava.version}, which should resolve to 33.0.0-jre
        assertThat(guava.versions()).containsKey("33.0.0-jre");
    }

    @Test
    void minFrequencyFiltersOutRareDeps() throws IOException {
        DiscoveryReport report = analyser.analyse(List.of(
                SERVICES_DIR.resolve("service-a.xml"),
                SERVICES_DIR.resolve("service-b.xml"),
                SERVICES_DIR.resolve("service-c.xml")
        ), 2);

        // commons-lang3 only appears in service-c, should be filtered out
        assertThat(report.candidates())
                .noneMatch(c -> c.key().equals("org.apache.commons:commons-lang3"));
    }

    @Test
    void conflictingReturnsOnlyMismatchedCandidates() throws IOException {
        DiscoveryReport report = analyser.analyse(List.of(
                SERVICES_DIR.resolve("service-a.xml"),
                SERVICES_DIR.resolve("service-b.xml"),
                SERVICES_DIR.resolve("service-c.xml")
        ));

        List<BomCandidate> conflicts = report.conflicting();
        assertThat(conflicts).extracting(BomCandidate::key)
                .contains("org.slf4j:slf4j-api", "com.fasterxml.jackson.core:jackson-databind")
                .doesNotContain("com.google.guava:guava");
    }

    @Test
    void scoreOrdersCandidates() throws IOException {
        DiscoveryReport report = analyser.analyse(List.of(
                SERVICES_DIR.resolve("service-a.xml"),
                SERVICES_DIR.resolve("service-b.xml"),
                SERVICES_DIR.resolve("service-c.xml")
        ));

        // Guava (universal + consistent) should have highest score
        // slf4j (universal but MAJOR conflict) should score lower
        BomCandidate guava = findByKey(report, "com.google.guava:guava");
        BomCandidate slf4j = findByKey(report, "org.slf4j:slf4j-api");
        assertThat(guava.score()).isGreaterThan(slf4j.score());
    }

    private BomCandidate findByKey(DiscoveryReport report, String key) {
        return report.candidates().stream()
                .filter(c -> c.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No candidate for " + key));
    }
}
