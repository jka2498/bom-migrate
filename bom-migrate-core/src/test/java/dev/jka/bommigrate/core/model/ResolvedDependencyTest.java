package dev.jka.bommigrate.core.model;

import org.apache.maven.model.Dependency;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolvedDependencyTest {

    @Test
    void keyWithDefaultTypeAndClassifier() {
        var dep = new ResolvedDependency("com.google.guava", "guava", "33.0.0-jre", null, null);
        assertThat(dep.key()).isEqualTo("com.google.guava:guava:jar:");
    }

    @Test
    void keyWithExplicitTypeAndClassifier() {
        var dep = new ResolvedDependency("com.google.guava", "guava", "33.0.0-jre", "test-jar", "tests");
        assertThat(dep.key()).isEqualTo("com.google.guava:guava:test-jar:tests");
    }

    @Test
    void typeDefaultsToJar() {
        var dep = new ResolvedDependency("g", "a", "1.0", null, null);
        assertThat(dep.type()).isEqualTo("jar");
    }

    @Test
    void classifierDefaultsToEmpty() {
        var dep = new ResolvedDependency("g", "a", "1.0", "jar", null);
        assertThat(dep.classifier()).isEmpty();
    }

    @Test
    void blankGroupIdThrows() {
        assertThatThrownBy(() -> new ResolvedDependency("", "a", "1.0", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullGroupIdThrows() {
        assertThatThrownBy(() -> new ResolvedDependency(null, "a", "1.0", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromMavenDependency() {
        Dependency mavenDep = new Dependency();
        mavenDep.setGroupId("org.slf4j");
        mavenDep.setArtifactId("slf4j-api");
        mavenDep.setVersion("2.0.9");

        ResolvedDependency resolved = ResolvedDependency.fromMavenDependency(mavenDep);

        assertThat(resolved.groupId()).isEqualTo("org.slf4j");
        assertThat(resolved.artifactId()).isEqualTo("slf4j-api");
        assertThat(resolved.version()).isEqualTo("2.0.9");
        assertThat(resolved.type()).isEqualTo("jar");
        assertThat(resolved.classifier()).isEmpty();
    }

    @Test
    void fromMavenDependencyWithResolvedVersion() {
        Dependency mavenDep = new Dependency();
        mavenDep.setGroupId("org.slf4j");
        mavenDep.setArtifactId("slf4j-api");
        mavenDep.setVersion("${slf4j.version}");

        ResolvedDependency resolved = ResolvedDependency.fromMavenDependency(mavenDep, "2.0.9");

        assertThat(resolved.version()).isEqualTo("2.0.9");
    }
}
