package dev.jka.bommigrate.core.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BomGeneratorTest {

    private final BomGenerator generator = new BomGenerator();

    @Test
    void singleModuleProducesFlatBom() {
        BomModule defaultModule = BomModule.defaultModule();
        BomCandidate guava = new BomCandidate(
                "com.google.guava", "guava",
                Map.of("33.0.0-jre", 3),
                3, 3, "33.0.0-jre", ConflictSeverity.NONE, 0.95);

        BomGenerationPlan plan = new BomGenerationPlan(
                "com.example", "my-bom", "1.0.0",
                List.of(defaultModule),
                List.of(new BomModuleAssignment(guava, defaultModule, "33.0.0-jre"))
        );

        Map<String, String> files = generator.generate(plan);

        assertThat(files).hasSize(1).containsKey("pom.xml");
        String pom = files.get("pom.xml");
        assertThat(pom).contains("<artifactId>my-bom</artifactId>");
        assertThat(pom).contains("<packaging>pom</packaging>");
        assertThat(pom).contains("<groupId>com.google.guava</groupId>");
        assertThat(pom).contains("<artifactId>guava</artifactId>");
        assertThat(pom).contains("<version>33.0.0-jre</version>");
        assertThat(pom).contains("<dependencyManagement>");
        assertThat(pom).doesNotContain("<modules>");
    }

    @Test
    void multiModuleProducesAggregatorAndChildren() {
        BomModule backend = new BomModule("backend-core");
        BomModule misc = new BomModule("misc");

        BomCandidate guava = new BomCandidate(
                "com.google.guava", "guava",
                Map.of("33.0.0-jre", 3),
                3, 3, "33.0.0-jre", ConflictSeverity.NONE, 0.95);
        BomCandidate commons = new BomCandidate(
                "org.apache.commons", "commons-lang3",
                Map.of("3.14.0", 2),
                2, 3, "3.14.0", ConflictSeverity.NONE, 0.75);

        BomGenerationPlan plan = new BomGenerationPlan(
                "com.example", "my-bom", "1.0.0",
                List.of(backend, misc),
                List.of(
                        new BomModuleAssignment(guava, backend, "33.0.0-jre"),
                        new BomModuleAssignment(commons, misc, "3.14.0")
                )
        );

        Map<String, String> files = generator.generate(plan);

        assertThat(files).hasSize(3)
                .containsKeys("pom.xml", "my-bom-backend-core/pom.xml", "my-bom-misc/pom.xml");

        // Aggregator POM lists both modules
        String aggregator = files.get("pom.xml");
        assertThat(aggregator).contains("<module>my-bom-backend-core</module>");
        assertThat(aggregator).contains("<module>my-bom-misc</module>");

        // backend-core child has guava only
        String backendChild = files.get("my-bom-backend-core/pom.xml");
        assertThat(backendChild).contains("<artifactId>my-bom-backend-core</artifactId>");
        assertThat(backendChild).contains("<groupId>com.google.guava</groupId>");
        assertThat(backendChild).doesNotContain("commons-lang3");
        assertThat(backendChild).contains("<artifactId>my-bom</artifactId>"); // parent

        // misc child has commons-lang3 only
        String miscChild = files.get("my-bom-misc/pom.xml");
        assertThat(miscChild).contains("<groupId>org.apache.commons</groupId>");
        assertThat(miscChild).contains("<artifactId>commons-lang3</artifactId>");
        assertThat(miscChild).doesNotContain("guava");
    }

    @Test
    void writeToPersistsFilesToDisk(@TempDir Path tempDir) throws IOException {
        BomModule defaultModule = BomModule.defaultModule();
        BomCandidate guava = new BomCandidate(
                "com.google.guava", "guava", Map.of("33.0.0-jre", 1),
                1, 1, "33.0.0-jre", ConflictSeverity.NONE, 0.95);

        BomGenerationPlan plan = new BomGenerationPlan(
                "com.example", "my-bom", "1.0.0",
                List.of(defaultModule),
                List.of(new BomModuleAssignment(guava, defaultModule, "33.0.0-jre"))
        );

        List<Path> written = generator.writeTo(plan, tempDir);

        assertThat(written).hasSize(1);
        assertThat(Files.readString(tempDir.resolve("pom.xml")))
                .contains("<artifactId>guava</artifactId>");
    }

    @Test
    void assignmentToUnknownModuleRejected() {
        BomModule defined = new BomModule("defined");
        BomModule undefined = new BomModule("undefined");

        BomCandidate guava = new BomCandidate(
                "com.google.guava", "guava", Map.of("33.0.0-jre", 1),
                1, 1, "33.0.0-jre", ConflictSeverity.NONE, 0.95);

        assertThatThrownBy(() -> new BomGenerationPlan(
                "com.example", "my-bom", "1.0.0",
                List.of(defined),
                List.of(new BomModuleAssignment(guava, undefined, "33.0.0-jre"))
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown module");
    }

    @Test
    void candidatesSortedAlphabeticallyInOutput() {
        BomModule defaultModule = BomModule.defaultModule();
        BomCandidate zzz = new BomCandidate("zzz.example", "zzz", Map.of("1.0", 1),
                1, 1, "1.0", ConflictSeverity.NONE, 0.5);
        BomCandidate aaa = new BomCandidate("aaa.example", "aaa", Map.of("1.0", 1),
                1, 1, "1.0", ConflictSeverity.NONE, 0.5);

        BomGenerationPlan plan = new BomGenerationPlan(
                "com.example", "my-bom", "1.0.0",
                List.of(defaultModule),
                List.of(
                        new BomModuleAssignment(zzz, defaultModule, "1.0"),
                        new BomModuleAssignment(aaa, defaultModule, "1.0")
                )
        );

        String pom = generator.generate(plan).get("pom.xml");
        int aaaIdx = pom.indexOf("aaa.example");
        int zzzIdx = pom.indexOf("zzz.example");
        assertThat(aaaIdx).isPositive().isLessThan(zzzIdx);
    }
}
