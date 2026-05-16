package dev.jka.bommigrate.core.resolver;

import dev.jka.bommigrate.core.model.DependencyManagementMap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceBomMatcherTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures/multi-bom");
    private static final Path BOM_DIR = FIXTURES.resolve("generated-bom");
    private static final String BOM_GROUP_ID = "com.example";
    private static final String BOM_ARTIFACT_ID = "my-bom";
    private static final List<String> CHILD_MODULES = List.of("backend", "misc");

    private final ServiceBomMatcher matcher = new ServiceBomMatcher();
    private final DefaultBomResolver resolver = new DefaultBomResolver();

    private DependencyManagementMap fullBomMap() throws Exception {
        return resolver.resolve(BOM_DIR, true);
    }

    @Test
    void matchesOnlyImportedChildModules() throws Exception {
        Path servicePom = FIXTURES.resolve("service-imports-backend-only.xml");
        DependencyManagementMap full = fullBomMap();

        ServiceBomMatcher.MatchResult result = matcher.match(
                servicePom, BOM_DIR, full, BOM_GROUP_ID, BOM_ARTIFACT_ID, CHILD_MODULES);

        assertThat(result.matchedModules()).containsExactly("backend");
        // Only guava (from backend), NOT slf4j (from misc)
        assertThat(result.effectiveBomMap().lookup("com.google.guava", "guava")).isPresent();
        assertThat(result.effectiveBomMap().lookup("org.slf4j", "slf4j-api")).isEmpty();
        assertThat(result.effectiveBomMap().size()).isEqualTo(1);
    }

    @Test
    void matchesBothChildrenWhenBothImported() throws Exception {
        Path servicePom = FIXTURES.resolve("service-imports-both.xml");
        DependencyManagementMap full = fullBomMap();

        ServiceBomMatcher.MatchResult result = matcher.match(
                servicePom, BOM_DIR, full, BOM_GROUP_ID, BOM_ARTIFACT_ID, CHILD_MODULES);

        assertThat(result.matchedModules()).containsExactly("backend", "misc");
        // Both guava and slf4j should be present
        assertThat(result.effectiveBomMap().lookup("com.google.guava", "guava")).isPresent();
        assertThat(result.effectiveBomMap().lookup("org.slf4j", "slf4j-api")).isPresent();
        assertThat(result.effectiveBomMap().size()).isEqualTo(2);
    }

    @Test
    void fallsBackToFullMapWhenParentImported() throws Exception {
        Path servicePom = FIXTURES.resolve("service-imports-parent.xml");
        DependencyManagementMap full = fullBomMap();

        ServiceBomMatcher.MatchResult result = matcher.match(
                servicePom, BOM_DIR, full, BOM_GROUP_ID, BOM_ARTIFACT_ID, CHILD_MODULES);

        // Parent import → full map (all children)
        assertThat(result.matchedModules()).containsExactly("backend", "misc");
        assertThat(result.effectiveBomMap()).isSameAs(full);
        // Spring Boot BOM should be an unmatched import
        assertThat(result.unmatchedImports())
                .contains("org.springframework.boot:spring-boot-dependencies");
    }

    @Test
    void fallsBackToFullMapWhenNoMatchFound() throws Exception {
        // Service POM with no BOM imports at all
        Path servicePom = FIXTURES.resolve("service-imports-backend-only.xml");
        DependencyManagementMap full = fullBomMap();

        // Pass a different BOM groupId so nothing matches
        ServiceBomMatcher.MatchResult result = matcher.match(
                servicePom, BOM_DIR, full, "com.other", "other-bom", CHILD_MODULES);

        // No match → fallback to full map
        assertThat(result.matchedModules()).isEmpty();
        assertThat(result.effectiveBomMap()).isSameAs(full);
    }

    @Test
    void returnsFullMapForSingleModuleBom() throws Exception {
        Path servicePom = FIXTURES.resolve("service-imports-backend-only.xml");
        DependencyManagementMap full = fullBomMap();

        // Empty child modules list → single-module BOM → always full map
        ServiceBomMatcher.MatchResult result = matcher.match(
                servicePom, BOM_DIR, full, BOM_GROUP_ID, BOM_ARTIFACT_ID, List.of());

        assertThat(result.effectiveBomMap()).isSameAs(full);
    }
}
