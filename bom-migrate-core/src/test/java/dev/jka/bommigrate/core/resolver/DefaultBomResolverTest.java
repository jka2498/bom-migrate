package dev.jka.bommigrate.core.resolver;

import dev.jka.bommigrate.core.model.DependencyManagementMap;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultBomResolverTest {

    private final DefaultBomResolver resolver = new DefaultBomResolver();

    private Path fixture(String relativePath) {
        return Path.of("src/test/resources/fixtures").resolve(relativePath);
    }

    @Test
    void resolveSimpleBom() throws IOException {
        DependencyManagementMap map = resolver.resolve(fixture("bom/simple-bom.xml"), false);

        assertThat(map.size()).isEqualTo(3);
        assertThat(map.lookup("com.google.guava", "guava")).isPresent()
                .get().satisfies(d -> assertThat(d.version()).isEqualTo("33.0.0-jre"));
        assertThat(map.lookup("org.slf4j", "slf4j-api")).isPresent()
                .get().satisfies(d -> assertThat(d.version()).isEqualTo("2.0.9"));
        assertThat(map.lookup("com.fasterxml.jackson.core", "jackson-databind")).isPresent()
                .get().satisfies(d -> assertThat(d.version()).isEqualTo("2.16.0"));
    }

    @Test
    void resolvePropertyBasedBom() throws IOException {
        DependencyManagementMap map = resolver.resolve(fixture("bom/bom-with-properties.xml"), false);

        assertThat(map.size()).isEqualTo(3);
        assertThat(map.lookup("org.springframework", "spring-core")).isPresent()
                .get().satisfies(d -> assertThat(d.version()).isEqualTo("6.1.0"));
        assertThat(map.lookup("org.springframework", "spring-context")).isPresent()
                .get().satisfies(d -> assertThat(d.version()).isEqualTo("6.1.0"));
        assertThat(map.lookup("com.fasterxml.jackson.core", "jackson-databind")).isPresent()
                .get().satisfies(d -> assertThat(d.version()).isEqualTo("2.16.0"));
    }

    @Test
    void resolveMultiModuleBom() throws IOException {
        DependencyManagementMap map = resolver.resolve(fixture("bom/multi-module/pom.xml"), false);

        assertThat(map.size()).isEqualTo(4);
        assertThat(map.lookup("com.google.guava", "guava")).isPresent();
        assertThat(map.lookup("org.slf4j", "slf4j-api")).isPresent();
        assertThat(map.lookup("com.fasterxml.jackson.core", "jackson-databind")).isPresent();
        assertThat(map.lookup("com.fasterxml.jackson.core", "jackson-core")).isPresent();
    }

    @Test
    void resolveBomImportingBomWithTransitiveEnabled() throws IOException {
        DependencyManagementMap map = resolver.resolve(fixture("bom/bom-importing-bom.xml"), true);

        // Own dependency
        assertThat(map.lookup("org.apache.commons", "commons-lang3")).isPresent()
                .get().satisfies(d -> assertThat(d.version()).isEqualTo("3.14.0"));

        // Transitively imported from simple-bom (adjacent directory)
        assertThat(map.lookup("com.google.guava", "guava")).isPresent();
    }

    @Test
    void resolveBomImportingBomWithTransitiveDisabled() throws IOException {
        DependencyManagementMap map = resolver.resolve(fixture("bom/bom-importing-bom.xml"), false);

        // Own dependency should be present
        assertThat(map.lookup("org.apache.commons", "commons-lang3")).isPresent();

        // Transitive imports should NOT be present
        assertThat(map.lookup("com.google.guava", "guava")).isEmpty();
    }
}
