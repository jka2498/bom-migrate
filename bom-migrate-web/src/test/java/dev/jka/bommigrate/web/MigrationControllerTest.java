package dev.jka.bommigrate.web;

import dev.jka.bommigrate.core.discovery.BomCandidate;
import dev.jka.bommigrate.core.discovery.BomGenerationPlan;
import dev.jka.bommigrate.core.discovery.BomGenerator;
import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.BomModuleAssignment;
import dev.jka.bommigrate.core.discovery.ConflictSeverity;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
import dev.jka.bommigrate.core.discovery.VersionFormat;
import dev.jka.bommigrate.web.service.DiscoverySessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BomMigrateWebApplication.class)
class MigrationControllerTest {

    @Autowired
    private WebApplicationContext webAppContext;

    @Autowired
    private DiscoverySessionService session;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webAppContext).build();
        session.setReport(DiscoveryReport.empty());
        session.setOutputDir(null);
        session.setScannedPomPaths(List.of());
        session.setModules(List.of());
        session.setAssignments(List.of());
        session.setParentCoordinates("com.example", "my-bom", "1.0.0");
        session.setVersionFormat(VersionFormat.INLINE);
    }

    @Test
    void preconditionFailedWhenNoBomGenerated() throws Exception {
        mockMvc.perform(get("/api/migration/preview"))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void returnsImportSnippetAndServicePreviews(@TempDir Path tempDir) throws Exception {
        // 1. Generate a real BOM to the temp dir
        BomModule defaultModule = BomModule.defaultModule();
        BomCandidate guava = new BomCandidate(
                "com.google.guava", "guava",
                Map.of("33.0.0-jre", 1),
                1, 1, "33.0.0-jre", ConflictSeverity.NONE, 0.95);

        BomGenerationPlan plan = new BomGenerationPlan(
                "com.example", "my-bom", "1.0.0",
                List.of(defaultModule),
                List.of(new BomModuleAssignment(guava, defaultModule, "33.0.0-jre"))
        );
        Path bomDir = tempDir.resolve("bom");
        Files.createDirectories(bomDir);
        new BomGenerator().writeTo(plan, bomDir);

        // 2. Create a service POM that uses a managed version
        Path serviceDir = tempDir.resolve("service-a");
        Files.createDirectories(serviceDir);
        Path servicePom = serviceDir.resolve("pom.xml");
        Files.writeString(servicePom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>service-a</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);

        // 3. Seed the session
        session.setOutputDir(bomDir);
        session.setScannedPomPaths(List.of(servicePom));
        session.setScanMetadata(dev.jka.bommigrate.core.discovery.ScanMetadata.localOnly(List.of("service-a/pom.xml")));
        session.setParentCoordinates("com.example", "my-bom", "1.0.0");
        session.setReport(new DiscoveryReport(List.of(guava), 1, Instant.now()));
        session.setLastGeneratedSignature(session.computeCurrentSignature());

        // 4. Call the preview endpoint
        mockMvc.perform(get("/api/migration/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bomImportSnippet").exists())
                .andExpect(jsonPath("$.bomImportSnippet").value(org.hamcrest.Matchers.containsString("<artifactId>my-bom</artifactId>")))
                .andExpect(jsonPath("$.bomImportSnippet").value(org.hamcrest.Matchers.containsString("<scope>import</scope>")))
                .andExpect(jsonPath("$.services[0].displayName").value("service-a/pom.xml"))
                .andExpect(jsonPath("$.services[0].stripCount").value(1))
                // Version tag is stripped
                .andExpect(jsonPath("$.services[0].modifiedContent").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<version>33.0.0-jre</version>"))))
                // BOM import is inserted into the modified content
                .andExpect(jsonPath("$.services[0].modifiedContent").value(org.hamcrest.Matchers.containsString("<dependencyManagement>")))
                .andExpect(jsonPath("$.services[0].modifiedContent").value(org.hamcrest.Matchers.containsString("<scope>import</scope>")))
                // Structured diff is populated
                .andExpect(jsonPath("$.services[0].diffLines").isArray())
                .andExpect(jsonPath("$.services[0].diffLines[?(@.status == 'REMOVED')]").exists())
                .andExpect(jsonPath("$.services[0].diffLines[?(@.status == 'ADDED')]").exists());
    }

    @Test
    void multiModuleSnippetContainsAllChildBoms(@TempDir Path tempDir) throws Exception {
        BomCandidate guava = new BomCandidate(
                "com.google.guava", "guava",
                Map.of("33.0.0-jre", 1),
                1, 1, "33.0.0-jre", ConflictSeverity.NONE, 0.95);
        BomCandidate slf4j = new BomCandidate(
                "org.slf4j", "slf4j-api",
                Map.of("2.0.9", 1),
                1, 1, "2.0.9", ConflictSeverity.NONE, 0.90);

        BomModule backendCore = new BomModule("backend-core");
        BomModule misc = new BomModule("misc");

        BomGenerationPlan plan = new BomGenerationPlan(
                "com.example", "my-bom", "1.0.0",
                List.of(backendCore, misc),
                List.of(
                        new BomModuleAssignment(guava, backendCore, "33.0.0-jre"),
                        new BomModuleAssignment(slf4j, misc, "2.0.9")
                )
        );
        Path bomDir = tempDir.resolve("bom");
        Files.createDirectories(bomDir);
        new BomGenerator().writeTo(plan, bomDir);

        Path servicePom = tempDir.resolve("service-x-pom.xml");
        Files.writeString(servicePom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>service-x</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);

        session.setOutputDir(bomDir);
        session.setScannedPomPaths(List.of(servicePom));
        session.setScanMetadata(dev.jka.bommigrate.core.discovery.ScanMetadata.localOnly(List.of("service-x/pom.xml")));
        session.setParentCoordinates("com.example", "my-bom", "1.0.0");
        session.setModules(List.of(backendCore, misc));
        session.setReport(new DiscoveryReport(List.of(guava, slf4j), 1, Instant.now()));
        session.setLastGeneratedSignature(session.computeCurrentSignature());

        mockMvc.perform(get("/api/migration/preview"))
                .andExpect(status().isOk())
                // Both child BOMs should be in the import snippet
                .andExpect(jsonPath("$.bomImportSnippet").value(org.hamcrest.Matchers.containsString("<artifactId>backend-core</artifactId>")))
                .andExpect(jsonPath("$.bomImportSnippet").value(org.hamcrest.Matchers.containsString("<artifactId>misc</artifactId>")))
                // Parent BOM (aggregator) must NOT appear in the snippet
                .andExpect(jsonPath("$.bomImportSnippet").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<artifactId>my-bom</artifactId>"))))
                // Each service's modified POM also gets both imports
                .andExpect(jsonPath("$.services[0].modifiedContent").value(org.hamcrest.Matchers.containsString("<artifactId>backend-core</artifactId>")))
                .andExpect(jsonPath("$.services[0].modifiedContent").value(org.hamcrest.Matchers.containsString("<artifactId>misc</artifactId>")));
    }

    @Test
    void returnsPropertyReferenceSnippetWhenPropertiesFormat(@TempDir Path tempDir) throws Exception {
        // 1. Generate a BOM in PROPERTIES format
        BomModule defaultModule = BomModule.defaultModule();
        BomCandidate guava = new BomCandidate(
                "com.google.guava", "guava",
                Map.of("33.0.0-jre", 1),
                1, 1, "33.0.0-jre", ConflictSeverity.NONE, 0.95);

        BomGenerationPlan plan = new BomGenerationPlan(
                "com.example", "my-bom", "1.0.0",
                List.of(defaultModule),
                List.of(new BomModuleAssignment(guava, defaultModule, "33.0.0-jre")),
                VersionFormat.PROPERTIES
        );
        Path bomDir = tempDir.resolve("bom");
        Files.createDirectories(bomDir);
        new BomGenerator().writeTo(plan, bomDir);

        // 2. Service POM referencing the managed dep
        Path serviceDir = tempDir.resolve("service-a");
        Files.createDirectories(serviceDir);
        Path servicePom = serviceDir.resolve("pom.xml");
        Files.writeString(servicePom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>service-a</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);

        // 3. Seed the session with PROPERTIES format
        session.setOutputDir(bomDir);
        session.setScannedPomPaths(List.of(servicePom));
        session.setScanMetadata(dev.jka.bommigrate.core.discovery.ScanMetadata.localOnly(List.of("service-a/pom.xml")));
        session.setParentCoordinates("com.example", "my-bom", "1.0.0");
        session.setVersionFormat(VersionFormat.PROPERTIES);
        session.setReport(new DiscoveryReport(List.of(guava), 1, Instant.now()));
        session.setLastGeneratedSignature(session.computeCurrentSignature());

        // 4. Assert snippet has <properties> block AND property-reference version
        mockMvc.perform(get("/api/migration/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bomImportSnippet").value(org.hamcrest.Matchers.containsString("<properties>")))
                .andExpect(jsonPath("$.bomImportSnippet").value(org.hamcrest.Matchers.containsString("<my-bom.version>1.0.0</my-bom.version>")))
                .andExpect(jsonPath("$.bomImportSnippet").value(org.hamcrest.Matchers.containsString("<version>${my-bom.version}</version>")))
                // Literal version should NOT appear in the import entry
                .andExpect(jsonPath("$.bomImportSnippet").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<version>1.0.0</version>"))))
                // The service POM gets the same treatment: merged property + property-reference import
                .andExpect(jsonPath("$.services[0].modifiedContent").value(org.hamcrest.Matchers.containsString("<my-bom.version>1.0.0</my-bom.version>")))
                .andExpect(jsonPath("$.services[0].modifiedContent").value(org.hamcrest.Matchers.containsString("<version>${my-bom.version}</version>")));
    }

    @Test
    void returnsConflictWhenCoordinatesChangedAfterGenerate(@TempDir Path tempDir) throws Exception {
        BomModule defaultModule = BomModule.defaultModule();
        BomCandidate guava = new BomCandidate(
                "com.google.guava", "guava",
                Map.of("33.0.0-jre", 1),
                1, 1, "33.0.0-jre", ConflictSeverity.NONE, 0.95);

        BomGenerationPlan plan = new BomGenerationPlan(
                "com.example", "my-bom", "1.0.0",
                List.of(defaultModule),
                List.of(new BomModuleAssignment(guava, defaultModule, "33.0.0-jre"))
        );
        Path bomDir = tempDir.resolve("bom");
        Files.createDirectories(bomDir);
        new BomGenerator().writeTo(plan, bomDir);

        Path servicePom = tempDir.resolve("service-a-pom.xml");
        Files.writeString(servicePom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>service-a</artifactId>
                    <version>1.0.0</version>
                </project>
                """, StandardCharsets.UTF_8);

        session.setOutputDir(bomDir);
        session.setScannedPomPaths(List.of(servicePom));
        session.setScanMetadata(dev.jka.bommigrate.core.discovery.ScanMetadata.localOnly(List.of("service-a/pom.xml")));
        session.setParentCoordinates("com.example", "my-bom", "1.0.0");
        session.setReport(new DiscoveryReport(List.of(guava), 1, Instant.now()));
        // Snapshot matches the current state — simulates a successful generate
        session.setLastGeneratedSignature(session.computeCurrentSignature());

        // Now the user changes the coordinates — session state diverges from the
        // last generation signature, so the preview should refuse with 409.
        session.setParentCoordinates("com.acme", "acme-bom", "2.0.0");

        mockMvc.perform(get("/api/migration/preview"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("stale"));
    }

    @Test
    void returnsConflictWhenModulesChangedAfterGenerate(@TempDir Path tempDir) throws Exception {
        BomModule defaultModule = BomModule.defaultModule();
        BomCandidate guava = new BomCandidate(
                "com.google.guava", "guava",
                Map.of("33.0.0-jre", 1),
                1, 1, "33.0.0-jre", ConflictSeverity.NONE, 0.95);

        BomGenerationPlan plan = new BomGenerationPlan(
                "com.example", "my-bom", "1.0.0",
                List.of(defaultModule),
                List.of(new BomModuleAssignment(guava, defaultModule, "33.0.0-jre"))
        );
        Path bomDir = tempDir.resolve("bom");
        Files.createDirectories(bomDir);
        new BomGenerator().writeTo(plan, bomDir);

        Path servicePom = tempDir.resolve("service-x-pom.xml");
        Files.writeString(servicePom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>service-x</artifactId>
                    <version>1.0.0</version>
                </project>
                """, StandardCharsets.UTF_8);

        session.setOutputDir(bomDir);
        session.setScannedPomPaths(List.of(servicePom));
        session.setScanMetadata(dev.jka.bommigrate.core.discovery.ScanMetadata.localOnly(List.of("service-x/pom.xml")));
        session.setParentCoordinates("com.example", "my-bom", "1.0.0");
        session.setReport(new DiscoveryReport(List.of(guava), 1, Instant.now()));
        session.setLastGeneratedSignature(session.computeCurrentSignature());

        // User now adds a module — signature diverges.
        session.setModules(List.of(new BomModule("backend-core"), new BomModule("misc")));

        mockMvc.perform(get("/api/migration/preview"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("stale"));
    }
}
