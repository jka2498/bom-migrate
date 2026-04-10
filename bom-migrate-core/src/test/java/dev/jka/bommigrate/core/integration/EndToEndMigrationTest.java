package dev.jka.bommigrate.core.integration;

import dev.jka.bommigrate.core.migrator.PomAnalyzer;
import dev.jka.bommigrate.core.migrator.PomWriter;
import dev.jka.bommigrate.core.model.DependencyManagementMap;
import dev.jka.bommigrate.core.model.MigrationAction;
import dev.jka.bommigrate.core.model.MigrationReport;
import dev.jka.bommigrate.core.resolver.DefaultBomResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test: loads BOM fixture, analyzes target fixture,
 * applies changes, and verifies output matches expected fixture.
 */
class EndToEndMigrationTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @Test
    void fullPipelineSimpleService() throws IOException {
        // 1. Resolve BOM
        DefaultBomResolver resolver = new DefaultBomResolver();
        DependencyManagementMap bomMap = resolver.resolve(
                FIXTURES.resolve("bom/simple-bom.xml"), false);

        assertThat(bomMap.size()).isEqualTo(3);

        // 2. Analyze target
        PomAnalyzer analyzer = new PomAnalyzer();
        Path targetPom = FIXTURES.resolve("target/simple-service-pom.xml");
        MigrationReport report = analyzer.analyze(targetPom, bomMap);

        assertThat(report.stripCount()).isEqualTo(2);
        assertThat(report.flagCount()).isEqualTo(1);
        assertThat(report.hasChanges()).isTrue();

        // 3. Apply changes
        PomWriter writer = new PomWriter();
        String modified = writer.applyStrips(targetPom, report);

        // 4. Verify output matches expected
        String expected = Files.readString(FIXTURES.resolve("expected/simple-service-pom-migrated.xml"));
        assertThat(modified).isEqualTo(expected);
    }

    @Test
    void fullPipelineWithPropertyVersions() throws IOException {
        DefaultBomResolver resolver = new DefaultBomResolver();
        DependencyManagementMap bomMap = resolver.resolve(
                FIXTURES.resolve("bom/simple-bom.xml"), false);

        PomAnalyzer analyzer = new PomAnalyzer();
        Path targetPom = FIXTURES.resolve("target/service-with-property-versions.xml");
        MigrationReport report = analyzer.analyze(targetPom, bomMap);

        assertThat(report.stripCount()).isEqualTo(2);
        assertThat(report.flagCount()).isEqualTo(1);

        PomWriter writer = new PomWriter();
        String modified = writer.applyStrips(targetPom, report);

        String expected = Files.readString(FIXTURES.resolve("expected/service-with-property-versions-migrated.xml"));
        assertThat(modified).isEqualTo(expected);
    }

    @Test
    void fullPipelineMultiModuleBom() throws IOException {
        DefaultBomResolver resolver = new DefaultBomResolver();
        DependencyManagementMap bomMap = resolver.resolve(
                FIXTURES.resolve("bom/multi-module/pom.xml"), false);

        // Multi-module BOM should have deps from both child modules
        assertThat(bomMap.size()).isEqualTo(4);
        assertThat(bomMap.lookup("com.google.guava", "guava")).isPresent();
        assertThat(bomMap.lookup("org.slf4j", "slf4j-api")).isPresent();
        assertThat(bomMap.lookup("com.fasterxml.jackson.core", "jackson-databind")).isPresent();
        assertThat(bomMap.lookup("com.fasterxml.jackson.core", "jackson-core")).isPresent();

        // Analyze against multi-module BOM
        PomAnalyzer analyzer = new PomAnalyzer();
        MigrationReport report = analyzer.analyze(
                FIXTURES.resolve("target/simple-service-pom.xml"), bomMap);

        // Guava and SLF4J match -> STRIP
        assertThat(report.byAction(MigrationAction.STRIP)).hasSize(2);
        // Jackson version mismatch (target has 2.15.0, BOM has 2.16.0) -> FLAG
        assertThat(report.byAction(MigrationAction.FLAG)).hasSize(1);
    }

    @Test
    void fullPipelineBomWithTransitiveImports() throws IOException {
        DefaultBomResolver resolver = new DefaultBomResolver();
        DependencyManagementMap bomMap = resolver.resolve(
                FIXTURES.resolve("bom/bom-importing-bom.xml"), true);

        // Should include own dep (commons-lang3) + imported deps from simple-bom
        assertThat(bomMap.lookup("org.apache.commons", "commons-lang3")).isPresent();

        PomAnalyzer analyzer = new PomAnalyzer();
        MigrationReport report = analyzer.analyze(
                FIXTURES.resolve("target/simple-service-pom.xml"), bomMap);

        // commons-lang3 should now be STRIP (BOM has 3.14.0, target has 3.14.0)
        assertThat(report.candidates()).anyMatch(c ->
                c.dependency().artifactId().equals("commons-lang3")
                        && c.action() == MigrationAction.STRIP);
    }
}
