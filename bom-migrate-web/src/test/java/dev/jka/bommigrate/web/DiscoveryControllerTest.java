package dev.jka.bommigrate.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jka.bommigrate.core.discovery.BomCandidate;
import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.BomModuleAssignment;
import dev.jka.bommigrate.core.discovery.ConflictSeverity;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
import dev.jka.bommigrate.core.discovery.ScanMetadata;
import dev.jka.bommigrate.web.service.DiscoverySessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BomMigrateWebApplication.class)
class DiscoveryControllerTest {

    @Autowired
    private WebApplicationContext webAppContext;

    @Autowired
    private DiscoverySessionService session;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webAppContext).build();
    }

    @Test
    void getDiscoveryReturnsReport() throws Exception {
        BomCandidate guava = new BomCandidate(
                "com.google.guava", "guava",
                Map.of("33.0.0-jre", 3),
                3, 3, "33.0.0-jre", ConflictSeverity.NONE, 0.95);
        session.setReport(new DiscoveryReport(List.of(guava), 3, Instant.now()));

        mockMvc.perform(get("/api/discovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalServicesScanned").value(3))
                .andExpect(jsonPath("$.candidates[0].groupId").value("com.google.guava"));
    }

    @Test
    void getDiscoveryReturnsNoContentWhenEmpty() throws Exception {
        session.setReport(null);
        mockMvc.perform(get("/api/discovery"))
                .andExpect(status().isNoContent());
    }

    @Test
    void setModulesPersistsAcrossRequests() throws Exception {
        List<BomModule> modules = List.of(new BomModule("core"), new BomModule("misc"));
        mockMvc.perform(post("/api/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(modules)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("core"))
                .andExpect(jsonPath("$[1].name").value("misc"));

        mockMvc.perform(get("/api/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("core"));
    }

    @Test
    void confirmStoresAssignments() throws Exception {
        BomCandidate guava = new BomCandidate(
                "com.google.guava", "guava",
                Map.of("33.0.0-jre", 3),
                3, 3, "33.0.0-jre", ConflictSeverity.NONE, 0.95);
        session.setReport(new DiscoveryReport(List.of(guava), 3, Instant.now()));

        BomModule core = new BomModule("core");
        session.setModules(List.of(core));

        BomModuleAssignment assignment = new BomModuleAssignment(guava, core, "33.0.0-jre");
        String body = "{\"assignments\":[" + mapper.writeValueAsString(assignment) + "]}";

        mockMvc.perform(post("/api/discovery/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));
    }

    @Test
    void getScanMetadataReturnsSources() throws Exception {
        ScanMetadata metadata = new ScanMetadata(
                List.of("my-repo: service-a/pom.xml", "my-repo: service-b/pom.xml"),
                List.of("python-service", "terraform-repo"),
                List.of("no-pom-repo"),
                List.of(new ScanMetadata.FailedClone("private-repo", "403 forbidden"))
        );
        session.setScanMetadata(metadata);

        mockMvc.perform(get("/api/scan/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scannedSources.length()").value(2))
                .andExpect(jsonPath("$.scannedSources[0]").value("my-repo: service-a/pom.xml"))
                .andExpect(jsonPath("$.skippedByLanguage[0]").value("python-service"))
                .andExpect(jsonPath("$.skippedNoPom[0]").value("no-pom-repo"))
                .andExpect(jsonPath("$.failedClones[0].repoName").value("private-repo"))
                .andExpect(jsonPath("$.failedClones[0].reason").value("403 forbidden"));
    }

    @Test
    void getScanMetadataReturnsEmptyWhenUnset() throws Exception {
        session.setScanMetadata(null);
        mockMvc.perform(get("/api/scan/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scannedSources.length()").value(0));
    }

    @Test
    void getVersionFormatDefaultsToInline() throws Exception {
        mockMvc.perform(get("/api/bom/version-format"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionFormat").value("INLINE"));
    }

    @Test
    void postVersionFormatPersistsChoice() throws Exception {
        mockMvc.perform(post("/api/bom/version-format")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionFormat\":\"PROPERTIES\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionFormat").value("PROPERTIES"));

        mockMvc.perform(get("/api/bom/version-format"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionFormat").value("PROPERTIES"));
    }

    @Test
    void generateAcceptsVersionFormatInRequestBody() throws Exception {
        BomCandidate guava = new BomCandidate(
                "com.google.guava", "guava",
                Map.of("33.0.0-jre", 1),
                1, 1, "33.0.0-jre", ConflictSeverity.NONE, 0.95);
        session.setReport(new DiscoveryReport(List.of(guava), 1, Instant.now()));

        BomModule core = new BomModule("core");
        session.setModules(List.of(core));
        session.setAssignments(List.of(new BomModuleAssignment(guava, core, "33.0.0-jre")));

        mockMvc.perform(post("/api/bom/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionFormat\":\"PROPERTIES\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files['pom.xml']").exists());
    }
}
