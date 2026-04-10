package dev.jka.bommigrate.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyManagementMapTest {

    @Test
    void lookupByKey() {
        var dep = new ResolvedDependency("com.google.guava", "guava", "33.0.0-jre", "jar", "");
        var map = new DependencyManagementMap(List.of(dep));

        assertThat(map.lookup("com.google.guava:guava:jar:")).isPresent().get().isEqualTo(dep);
    }

    @Test
    void lookupByGroupIdAndArtifactId() {
        var dep = new ResolvedDependency("com.google.guava", "guava", "33.0.0-jre", "jar", "");
        var map = new DependencyManagementMap(List.of(dep));

        assertThat(map.lookup("com.google.guava", "guava")).isPresent().get().isEqualTo(dep);
    }

    @Test
    void lookupMissingReturnsEmpty() {
        var dep = new ResolvedDependency("com.google.guava", "guava", "33.0.0-jre", "jar", "");
        var map = new DependencyManagementMap(List.of(dep));

        assertThat(map.lookup("org.slf4j", "slf4j-api")).isEmpty();
    }

    @Test
    void classifierDistinguishesDeps() {
        var dep1 = new ResolvedDependency("com.google.guava", "guava", "33.0.0-jre", "jar", "");
        var dep2 = new ResolvedDependency("com.google.guava", "guava", "33.0.0-jre", "jar", "tests");
        var map = new DependencyManagementMap(List.of(dep1, dep2));

        assertThat(map.size()).isEqualTo(2);
        assertThat(map.lookup("com.google.guava:guava:jar:")).isPresent();
        assertThat(map.lookup("com.google.guava:guava:jar:tests")).isPresent();
    }

    @Test
    void firstWinsForDuplicates() {
        var dep1 = new ResolvedDependency("g", "a", "1.0", "jar", "");
        var dep2 = new ResolvedDependency("g", "a", "2.0", "jar", "");
        var map = new DependencyManagementMap(List.of(dep1, dep2));

        assertThat(map.size()).isEqualTo(1);
        assertThat(map.lookup("g", "a")).isPresent().get().satisfies(d ->
                assertThat(d.version()).isEqualTo("1.0"));
    }

    @Test
    void manages() {
        var dep = new ResolvedDependency("g", "a", "1.0", "jar", "");
        var other = new ResolvedDependency("g", "b", "1.0", "jar", "");
        var map = new DependencyManagementMap(List.of(dep));

        assertThat(map.manages(dep)).isTrue();
        assertThat(map.manages(other)).isFalse();
    }

    @Test
    void allReturnsAllEntries() {
        var dep1 = new ResolvedDependency("g", "a", "1.0", "jar", "");
        var dep2 = new ResolvedDependency("g", "b", "2.0", "jar", "");
        var map = new DependencyManagementMap(List.of(dep1, dep2));

        assertThat(map.all()).containsExactlyInAnyOrder(dep1, dep2);
    }
}
