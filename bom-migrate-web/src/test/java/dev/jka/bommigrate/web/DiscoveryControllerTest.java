package dev.jka.bommigrate.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jka.bommigrate.core.discovery.BomCandidate;
import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.BomModuleAssignment;
import dev.jka.bommigrate.core.discovery.ConflictSeverity;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
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
}
