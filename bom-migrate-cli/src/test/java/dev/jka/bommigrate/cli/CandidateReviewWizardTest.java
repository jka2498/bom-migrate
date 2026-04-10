package dev.jka.bommigrate.cli;

import dev.jka.bommigrate.core.discovery.BomCandidate;
import dev.jka.bommigrate.core.discovery.BomGenerationPlan;
import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.ConflictSeverity;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateReviewWizardTest {

    @Test
    void acceptsAllCandidatesOnYesYesYes() throws Exception {
        DiscoveryReport report = buildTwoCandidateReport();
        BomModule defaultModule = BomModule.defaultModule();

        // "y\n\ny\n\n" = include candidate 1 (accept suggested version), include candidate 2 (accept suggested version)
        BufferedReader input = new BufferedReader(new StringReader("y\n\ny\n\n"));
        PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        CandidateReviewWizard wizard = new CandidateReviewWizard(input, output);
        BomGenerationPlan plan = wizard.review(report, List.of(defaultModule), "com.ex", "my-bom", "1.0");

        assertThat(plan.assignments()).hasSize(2);
        assertThat(plan.assignments()).extracting(a -> a.candidate().key())
                .containsExactlyInAnyOrder("com.ex:guava", "com.ex:slf4j");
    }

    @Test
    void excludesCandidateOnNo() throws Exception {
        DiscoveryReport report = buildTwoCandidateReport();
        BomModule defaultModule = BomModule.defaultModule();

        // First candidate: n, second: y
        BufferedReader input = new BufferedReader(new StringReader("n\ny\n\n"));
        PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        CandidateReviewWizard wizard = new CandidateReviewWizard(input, output);
        BomGenerationPlan plan = wizard.review(report, List.of(defaultModule), "com.ex", "my-bom", "1.0");

        assertThat(plan.assignments()).hasSize(1);
    }

    @Test
    void usesCustomVersionWhenProvided() throws Exception {
        DiscoveryReport report = buildTwoCandidateReport();
        BomModule defaultModule = BomModule.defaultModule();

        // First: include, override version. Second: exclude.
        BufferedReader input = new BufferedReader(new StringReader("y\n99.0.0\nn\n"));
        PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        CandidateReviewWizard wizard = new CandidateReviewWizard(input, output);
        BomGenerationPlan plan = wizard.review(report, List.of(defaultModule), "com.ex", "my-bom", "1.0");

        assertThat(plan.assignments()).hasSize(1);
        assertThat(plan.assignments().get(0).confirmedVersion()).isEqualTo("99.0.0");
    }

    @Test
    void assignsToModuleWhenMultipleDefined() throws Exception {
        DiscoveryReport report = buildTwoCandidateReport();
        BomModule core = new BomModule("core");
        BomModule misc = new BomModule("misc");

        // First: include, default version, assign to "misc". Second: exclude.
        BufferedReader input = new BufferedReader(new StringReader("y\n\nmisc\nn\n"));
        PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        CandidateReviewWizard wizard = new CandidateReviewWizard(input, output);
        BomGenerationPlan plan = wizard.review(report, List.of(core, misc), "com.ex", "my-bom", "1.0");

        assertThat(plan.assignments()).hasSize(1);
        assertThat(plan.assignments().get(0).module().name()).isEqualTo("misc");
    }

    private DiscoveryReport buildTwoCandidateReport() {
        BomCandidate c1 = new BomCandidate("com.ex", "guava",
                Map.of("33.0.0", 3), 3, 3, "33.0.0",
                ConflictSeverity.NONE, 0.95);
        BomCandidate c2 = new BomCandidate("com.ex", "slf4j",
                Map.of("2.0.0", 3), 3, 3, "2.0.0",
                ConflictSeverity.NONE, 0.90);
        return new DiscoveryReport(List.of(c1, c2), 3, Instant.now());
    }
}
