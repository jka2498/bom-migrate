package dev.jka.bommigrate.web;

import dev.jka.bommigrate.web.service.DiscoverySessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BomMigrateWebApplication.class)
class ScanUploadControllerTest {

    @Autowired
    private WebApplicationContext webAppContext;

    @Autowired
    private DiscoverySessionService session;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webAppContext).build();
        // Reset session between tests
        session.setReport(dev.jka.bommigrate.core.discovery.DiscoveryReport.empty());
        session.setScannedPomPaths(java.util.List.of());
    }

    @Test
    void uploadSingleServicePomSeedsSessionAndReturnsReport() throws Exception {
        String pomXml = """
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
                """;

        MockMultipartFile file = new MockMultipartFile(
                "files", "pom.xml", "application/xml",
                pomXml.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/scan/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalServicesScanned").value(1))
                .andExpect(jsonPath("$.candidates[0].groupId").value("com.google.guava"));

        // Session should be seeded with the temp path
        assertThat(session.getScannedPomPaths()).hasSize(1);
        assertThat(session.getReport().totalServicesScanned()).isEqualTo(1);
        assertThat(session.getScanMetadata().scannedSources()).containsExactly("pom.xml");
    }

    @Test
    void uploadMultipleServicePomsDedupesAndFindsCommonDeps() throws Exception {
        String serviceA = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project><modelVersion>4.0.0</modelVersion>
                    <groupId>com.ex</groupId><artifactId>a</artifactId><version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        String serviceB = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project><modelVersion>4.0.0</modelVersion>
                    <groupId>com.ex</groupId><artifactId>b</artifactId><version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """;

        MockMultipartFile a = new MockMultipartFile("files", "pom.xml", "application/xml",
                serviceA.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile b = new MockMultipartFile("files", "pom.xml", "application/xml",
                serviceB.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/scan/upload").file(a).file(b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalServicesScanned").value(2))
                .andExpect(jsonPath("$.candidates[0].serviceCount").value(2));

        assertThat(session.getScannedPomPaths()).hasSize(2);
        // Both files have the same name but should be placed in their own subdirs
        assertThat(session.getScannedPomPaths().get(0))
                .isNotEqualTo(session.getScannedPomPaths().get(1));
    }

    @Test
    void uploadEmptyReturnsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/scan/upload"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadWithPathsUsesPathsForDisplayNamesAndLayout() throws Exception {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project><modelVersion>4.0.0</modelVersion>
                    <groupId>com.ex</groupId><artifactId>a</artifactId><version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        MockMultipartFile a = new MockMultipartFile("files", "pom.xml", "application/xml",
                pom.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile b = new MockMultipartFile("files", "pom.xml", "application/xml",
                pom.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/scan/upload")
                        .file(a).file(b)
                        .param("paths", "my-repo/service-a/pom.xml")
                        .param("paths", "my-repo/service-b/pom.xml"))
                .andExpect(status().isOk());

        // Display names come from the paths field, not the raw filename
        assertThat(session.getScanMetadata().scannedSources())
                .containsExactly("my-repo/service-a/pom.xml", "my-repo/service-b/pom.xml");
        // Files live under the sub-path on disk
        assertThat(session.getScannedPomPaths().get(0).toString())
                .endsWith("my-repo/service-a/pom.xml");
        assertThat(session.getScannedPomPaths().get(1).toString())
                .endsWith("my-repo/service-b/pom.xml");
    }

    @Test
    void uploadWithTraversalPathFallsBackToSafeLayout() throws Exception {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project><modelVersion>4.0.0</modelVersion>
                    <groupId>com.ex</groupId><artifactId>a</artifactId><version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        MockMultipartFile file = new MockMultipartFile("files", "pom.xml", "application/xml",
                pom.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/scan/upload")
                        .file(file)
                        .param("paths", "../../etc/passwd/pom.xml"))
                .andExpect(status().isOk());

        // Traversal must be rejected — falls back to the numbered layout
        assertThat(session.getScanMetadata().scannedSources()).containsExactly("0/pom.xml");
        assertThat(session.getScannedPomPaths().get(0).toString()).endsWith("0/pom.xml");
    }

    @Test
    void uploadClearsLastGeneratedSignature() throws Exception {
        // Seed a stale signature, then upload — it should be cleared.
        session.setLastGeneratedSignature("stale");

        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project><modelVersion>4.0.0</modelVersion>
                    <groupId>com.ex</groupId><artifactId>a</artifactId><version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        MockMultipartFile file = new MockMultipartFile("files", "pom.xml", "application/xml",
                pom.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/scan/upload").file(file))
                .andExpect(status().isOk());

        assertThat(session.getLastGeneratedSignature()).isNull();
    }
}
