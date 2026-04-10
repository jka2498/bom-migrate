package dev.jka.bommigrate.core.migrator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VersionLineLocatorTest {

    private final VersionLineLocator locator = new VersionLineLocator();

    @Test
    void locatesVersionInSimplePom() throws IOException {
        List<String> lines = readFixture("target/simple-service-pom.xml");
        Map<String, VersionLineLocator.VersionElementLocation> locations = locator.locateVersionElements(lines);

        // Should find version elements for dependencies in <dependencies>, not in <dependencyManagement>
        assertThat(locations).containsKey("com.google.guava:guava:jar:");
        assertThat(locations).containsKey("org.slf4j:slf4j-api:jar:");
        assertThat(locations).containsKey("com.fasterxml.jackson.core:jackson-databind:jar:");
        assertThat(locations).containsKey("org.apache.commons:commons-lang3:jar:");

        // junit has no version tag, should not be in the map
        assertThat(locations).doesNotContainKey("junit:junit:jar:");
    }

    @Test
    void versionLineContentMatchesExpected() throws IOException {
        List<String> lines = readFixture("target/simple-service-pom.xml");
        Map<String, VersionLineLocator.VersionElementLocation> locations = locator.locateVersionElements(lines);

        var guavaLoc = locations.get("com.google.guava:guava:jar:");
        assertThat(guavaLoc).isNotNull();
        String guavaVersionLine = lines.get(guavaLoc.startLine());
        assertThat(guavaVersionLine).contains("<version>33.0.0-jre</version>");
    }

    @Test
    void skipsDependencyManagementSection() {
        List<String> lines = List.of(
                "<project>",
                "  <dependencyManagement>",
                "    <dependencies>",
                "      <dependency>",
                "        <groupId>com.example</groupId>",
                "        <artifactId>managed</artifactId>",
                "        <version>1.0.0</version>",
                "      </dependency>",
                "    </dependencies>",
                "  </dependencyManagement>",
                "  <dependencies>",
                "    <dependency>",
                "      <groupId>com.example</groupId>",
                "      <artifactId>direct</artifactId>",
                "      <version>2.0.0</version>",
                "    </dependency>",
                "  </dependencies>",
                "</project>"
        );

        Map<String, VersionLineLocator.VersionElementLocation> locations = locator.locateVersionElements(lines);

        // Should find 'direct' but not 'managed'
        assertThat(locations).containsKey("com.example:direct:jar:");
        assertThat(locations).doesNotContainKey("com.example:managed:jar:");
    }

    @Test
    void handlesClassifierAndType() {
        List<String> lines = List.of(
                "<project>",
                "  <dependencies>",
                "    <dependency>",
                "      <groupId>com.example</groupId>",
                "      <artifactId>lib</artifactId>",
                "      <version>1.0.0</version>",
                "      <type>test-jar</type>",
                "      <classifier>tests</classifier>",
                "    </dependency>",
                "  </dependencies>",
                "</project>"
        );

        Map<String, VersionLineLocator.VersionElementLocation> locations = locator.locateVersionElements(lines);

        assertThat(locations).containsKey("com.example:lib:test-jar:tests");
    }

    @Test
    void handlesPropertyBasedVersions() throws IOException {
        List<String> lines = readFixture("target/service-with-property-versions.xml");
        Map<String, VersionLineLocator.VersionElementLocation> locations = locator.locateVersionElements(lines);

        // Should locate version elements even when they contain ${...}
        assertThat(locations).containsKey("com.google.guava:guava:jar:");
        assertThat(locations).containsKey("org.slf4j:slf4j-api:jar:");
    }

    private List<String> readFixture(String path) throws IOException {
        return Files.readAllLines(Path.of("src/test/resources/fixtures").resolve(path));
    }
}
