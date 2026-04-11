package dev.jka.bommigrate.web;

import dev.jka.bommigrate.core.discovery.BomCandidate;
import dev.jka.bommigrate.core.discovery.BomGenerationPlan;
import dev.jka.bommigrate.core.discovery.BomGenerator;
import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.BomModuleAssignment;
import dev.jka.bommigrate.core.discovery.ConflictSeverity;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
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

        // 4. Call the preview endpoint
        mockMvc.perform(get("/api/migration/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bomImportSnippet").exists())
                .andExpect(jsonPath("$.bomImportSnippet").value(org.hamcrest.Matchers.containsString("<artifactId>my-bom</artifactId>")))
                .andExpect(jsonPath("$.bomImportSnippet").value(org.hamcrest.Matchers.containsString("<scope>import</scope>")))
                .andExpect(jsonPath("$.services[0].displayName").value("service-a/pom.xml"))
                .andExpect(jsonPath("$.services[0].stripCount").value(1))
                .andExpect(jsonPath("$.services[0].modifiedContent").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<version>33.0.0-jre</version>"))));
    }
}
