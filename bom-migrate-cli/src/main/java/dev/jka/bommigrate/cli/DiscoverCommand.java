package dev.jka.bommigrate.cli;

import dev.jka.bommigrate.core.discovery.BomGenerationPlan;
import dev.jka.bommigrate.core.discovery.BomGenerator;
import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.DependencyFrequencyAnalyser;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
import dev.jka.bommigrate.core.discovery.ScanMetadata;
import dev.jka.bommigrate.core.discovery.VersionFormat;
import dev.jka.bommigrate.core.migrator.PomAnalyzer;
import dev.jka.bommigrate.core.migrator.PomWriter;
import dev.jka.bommigrate.core.model.DependencyManagementMap;
import dev.jka.bommigrate.core.model.MigrationReport;
import dev.jka.bommigrate.core.resolver.DefaultBomResolver;
import dev.jka.bommigrate.github.ClonedRepo;
import dev.jka.bommigrate.github.OrgScanResult;
import dev.jka.bommigrate.github.OrgScanner;
import dev.jka.bommigrate.web.WebServerLauncher;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Scans service POMs and produces BOM candidates ranked by frequency and
 * version consistency. Supports interactive review (CLI wizard or web UI).
 */
@Command(
        name = "discover",
        mixinStandardHelpOptions = true,
        description = "Scan services and discover BOM candidates."
)
public class DiscoverCommand implements Callable<Integer> {

    @ArgGroup(exclusive = true, multiplicity = "0..1")
    TargetSource targetSource;

    static class TargetSource {
        @Option(names = {"--target", "-t"}, arity = "1..*",
                description = "One or more paths to target microservice pom.xml files or directories")
        List<Path> targetPaths;

        @Option(names = "--org",
                description = "GitHub organisation to fetch repos from")
        String org;
    }

    @Option(names = "--bom-modules",
            description = "Comma-separated list of BOM module names (e.g. 'backend-core,misc')")
    String bomModules;

    @Option(names = "--web", defaultValue = "false",
            description = "Launch web UI for interactive candidate review")
    boolean web;

    @Option(names = "--web-port", defaultValue = "8080",
            description = "Port for the web UI (default: 8080)")
    int webPort;

    @Option(names = "--also-migrate", defaultValue = "false",
            description = "After generating the BOM, immediately run migration against all scanned services")
    boolean alsoMigrate;

    @Option(names = "--dry-run", defaultValue = "false",
            description = "Show the report and plan without writing files or opening PRs")
    boolean dryRun;

    @Option(names = "--open-pr", defaultValue = "false",
            description = "Create a GitHub PR with the generated BOM")
    boolean openPr;

    @Option(names = "--github-token",
            description = "GitHub personal access token. Falls back to GITHUB_TOKEN env var.")
    String githubToken;

    @Option(names = "--repo-filter",
            description = "Glob pattern to filter org repos by name (e.g. 'claims-*', 'claims-digital-*-service')")
    String repoFilter;

    @Option(names = "--include-all-languages", defaultValue = "false",
            description = "Do not filter by primary language; scan all matching repos")
    boolean includeAllLanguages;

    @Option(names = "--keep-clones", defaultValue = "false",
            description = "Do not delete cloned repos after the run")
    boolean keepClones;

    @Option(names = "--clone-dir",
            description = "Override the temp directory for clones")
    Path cloneDir;

    @Option(names = "--output-dir", defaultValue = ".",
            description = "Directory to write the generated BOM into")
    Path outputDir;

    @Option(names = "--bom-group-id", defaultValue = "com.example",
            description = "groupId for the generated BOM parent POM")
    String bomGroupId;

    @Option(names = "--bom-artifact-id", defaultValue = "my-bom",
            description = "artifactId for the generated BOM parent POM")
    String bomArtifactId;

    @Option(names = "--bom-version", defaultValue = "1.0.0",
            description = "version for the generated BOM parent POM")
    String bomVersion;

    @Option(names = "--min-frequency", defaultValue = "1",
            description = "Exclude candidates used by fewer than this many services")
    int minFrequency;

    @Option(names = "--version-format", defaultValue = "INLINE",
            description = "How versions are emitted in the generated BOM: ${COMPLETION-CANDIDATES} (default: INLINE)")
    VersionFormat versionFormat;

    @Override
    public Integer call() {
        try {
            return execute();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private int execute() throws IOException {
        // Validate: non-web mode requires a target source
        boolean hasTarget = targetSource != null
                && (targetSource.org != null || (targetSource.targetPaths != null && !targetSource.targetPaths.isEmpty()));
        if (!web && !hasTarget) {
            System.err.println("Error: discover requires --target or --org (unless --web is used for empty start).");
            return 1;
        }

        List<BomModule> modules = ModuleDefinitionWizard.parseModuleList(bomModules);

        // Web mode starts the server regardless of whether we have a target.
        // If a target is provided, we scan first and seed the session.
        // If no target, the server starts empty and the user uploads POMs via the UI.
        if (web) {
            DiscoveryReport report;
            ScanMetadata metadata;
            List<Path> scannedPomPaths;

            if (hasTarget) {
                ScanResult scan = gatherScan();
                if (scan.pomPaths.isEmpty()) {
                    System.err.println("No target POM files found.");
                    return 1;
                }
                System.out.println("Analysing " + scan.pomPaths.size() + " POM(s)...");
                report = new DependencyFrequencyAnalyser().analyse(scan.pomPaths, minFrequency);
                metadata = scan.metadata;
                scannedPomPaths = scan.pomPaths;

                DiscoveryReportFormatter formatter = new DiscoveryReportFormatter();
                System.out.println();
                System.out.println(formatter.formatSummary(report));
                printScannedSources(metadata);
            } else {
                System.out.println("Starting web UI with no pre-scanned POMs. Upload files from the browser.");
                report = DiscoveryReport.empty();
                metadata = ScanMetadata.empty();
                scannedPomPaths = List.of();
            }

            if (alsoMigrate) {
                System.err.println("[WARN] --also-migrate is ignored in --web mode. "
                        + "Use the web UI's migration preview to copy modified POMs instead.");
            }

            try {
                WebServerLauncher.start(report, metadata, scannedPomPaths, modules, webPort, outputDir,
                        bomGroupId, bomArtifactId, bomVersion, versionFormat);
                System.out.println("Press Ctrl+C to stop the web server when done.");
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }

        // Non-web path: target is required (already validated above)
        ScanResult scan = gatherScan();
        if (scan.pomPaths.isEmpty()) {
            System.err.println("No target POM files found.");
            return 1;
        }

        System.out.println("Analysing " + scan.pomPaths.size() + " POM(s)...");
        DependencyFrequencyAnalyser analyser = new DependencyFrequencyAnalyser();
        DiscoveryReport report = analyser.analyse(scan.pomPaths, minFrequency);

        DiscoveryReportFormatter formatter = new DiscoveryReportFormatter();
        System.out.println();
        System.out.println(formatter.formatSummary(report));
        printScannedSources(scan.metadata);

        boolean interactive = System.console() != null;

        // Non-interactive or dry-run without --also-migrate: print full report and return
        if ((!interactive || dryRun) && !alsoMigrate) {
            if (!interactive) {
                System.err.println("No interactive terminal detected. Printing full report only.");
            }
            for (int i = 0; i < report.candidates().size(); i++) {
                System.out.println(formatter.formatCandidate(report.candidates().get(i), i + 1, report.candidates().size()));
            }
            return 0;
        }

        // Build the plan: interactive wizard if we have a terminal, otherwise auto-accept
        // all candidates into the default module (needed for --dry-run --also-migrate
        // and for pure non-interactive CI usage with --also-migrate).
        BomGenerationPlan plan;
        if (interactive && !dryRun) {
            CandidateReviewWizard wizard = new CandidateReviewWizard();
            plan = wizard.review(report, modules, bomGroupId, bomArtifactId, bomVersion, versionFormat);
        } else {
            plan = autoAcceptAllCandidates(report, modules);
        }

        // 5. Generate BOM (to outputDir normally, temp dir in dry-run so we don't touch the user's FS)
        Path bomDir = dryRun ? Files.createTempDirectory("bom-migrate-dryrun-") : outputDir;
        System.out.println();
        System.out.println(dryRun
                ? "Generating BOM in dry-run temp dir: " + bomDir.toAbsolutePath()
                : "Generating BOM at: " + bomDir.toAbsolutePath());
        BomGenerator generator = new BomGenerator();
        List<Path> written = generator.writeTo(plan, bomDir);
        if (!dryRun) {
            for (Path p : written) {
                System.out.println("  wrote " + p);
            }
        }

        // 6. Optionally run migration against the same scanned services using the just-generated BOM
        if (alsoMigrate) {
            System.out.println();
            System.out.println("Running migration against " + scan.pomPaths.size() + " scanned service(s)...");
            runMigration(scan.pomPaths, bomDir);
        }

        return 0;
    }

    /**
     * Non-interactive fallback: accept every candidate at its suggested version,
     * all into the first (default) module. Used for --dry-run and CI scenarios
     * where we need a concrete plan without user input.
     */
    private BomGenerationPlan autoAcceptAllCandidates(DiscoveryReport report, List<BomModule> modules) {
        BomModule target = modules.get(0);
        List<dev.jka.bommigrate.core.discovery.BomModuleAssignment> assignments = new ArrayList<>();
        for (dev.jka.bommigrate.core.discovery.BomCandidate c : report.candidates()) {
            assignments.add(new dev.jka.bommigrate.core.discovery.BomModuleAssignment(
                    c, target, c.suggestedVersion()));
        }
        return new BomGenerationPlan(
                bomGroupId, bomArtifactId, bomVersion,
                modules, assignments, versionFormat);
    }

    private ScanResult gatherScan() throws IOException {
        if (targetSource != null && targetSource.org != null) {
            return fetchFromOrg();
        }
        return discoverLocalPoms();
    }

    /**
     * Runs migration against each scanned service using the just-generated BOM.
     * Honours {@code --dry-run} (prints diffs only) vs apply mode (writes changes back).
     */
    private void runMigration(List<Path> servicePoms, Path bomDir) throws IOException {
        DefaultBomResolver resolver = new DefaultBomResolver();
        DependencyManagementMap bomMap = resolver.resolve(bomDir, true);

        PomAnalyzer analyser = new PomAnalyzer();
        PomWriter writer = new PomWriter();
        ReportFormatter formatter = new ReportFormatter();
        int applied = 0;

        for (Path servicePom : servicePoms) {
            MigrationReport report = analyser.analyze(servicePom, bomMap);
            System.out.println(formatter.format(report));

            if (!report.hasChanges()) {
                System.out.println();
                continue;
            }

            if (dryRun) {
                System.out.println(writer.generateDiff(servicePom, report));
            } else {
                String modified = writer.applyStrips(servicePom, report);
                writer.writeTo(servicePom, modified);
                System.out.println("  Changes applied to " + servicePom);
                applied++;
            }
            System.out.println();
        }

        System.out.println("Migration complete. "
                + (dryRun ? "Dry run — no files were modified." : applied + " file(s) modified."));
    }

    private ScanResult fetchFromOrg() throws IOException {
        String token = resolveGitHubToken();
        if (token == null || token.isBlank()) {
            throw new IOException("--org requires a GitHub token. Pass --github-token or set GITHUB_TOKEN env var.");
        }

        Path effectiveCloneDir = cloneDir != null
                ? cloneDir
                : Files.createTempDirectory("bom-migrate-clones-");

        System.out.println("Scanning GitHub org: " + targetSource.org
                + (repoFilter != null ? " (filter: " + repoFilter + ")" : ""));

        OrgScanner scanner = new OrgScanner(token);
        OrgScanResult result = scanner.scan(targetSource.org, repoFilter, includeAllLanguages,
                token, effectiveCloneDir, keepClones);

        System.out.println("  cloned: " + result.clonedRepos().size()
                + ", skipped (non-JVM): " + result.skippedByLanguage().size()
                + ", skipped (no pom): " + result.skippedNoPom().size()
                + ", failed: " + result.failedToClone().size());

        List<Path> pomFiles = new ArrayList<>();
        List<String> scannedSources = new ArrayList<>();
        for (ClonedRepo repo : result.clonedRepos()) {
            for (Path pomPath : repo.pomPaths()) {
                pomFiles.add(pomPath);
                // e.g. "my-repo: src/service-a/pom.xml"
                Path relative = repo.clonePath().relativize(pomPath);
                scannedSources.add(repo.repoName() + ": " + relative);
            }
        }

        List<ScanMetadata.FailedClone> failed = result.failedToClone().stream()
                .map(f -> new ScanMetadata.FailedClone(f.repoName(), f.reason()))
                .toList();

        ScanMetadata metadata = new ScanMetadata(
                scannedSources,
                result.skippedByLanguage(),
                result.skippedNoPom(),
                failed
        );

        return new ScanResult(pomFiles, metadata);
    }

    private ScanResult discoverLocalPoms() {
        List<Path> pomFiles = new ArrayList<>();
        List<String> scannedSources = new ArrayList<>();
        for (Path target : targetSource.targetPaths) {
            if (Files.isRegularFile(target)) {
                pomFiles.add(target);
                scannedSources.add(target.toString());
            } else if (Files.isDirectory(target)) {
                Path pom = target.resolve("pom.xml");
                if (Files.exists(pom)) {
                    pomFiles.add(pom);
                    scannedSources.add(pom.toString());
                } else {
                    System.err.println("Warning: No pom.xml found in " + target);
                }
            }
        }
        return new ScanResult(pomFiles, ScanMetadata.localOnly(scannedSources));
    }

    private void printScannedSources(ScanMetadata metadata) {
        System.out.println("Scanned sources: " + metadata.scannedSources().size());
        for (String source : metadata.scannedSources()) {
            System.out.println("  - " + source);
        }
        if (!metadata.skippedByLanguage().isEmpty()) {
            System.out.println("Skipped by language filter: " + metadata.skippedByLanguage().size());
            for (String repo : metadata.skippedByLanguage()) {
                System.out.println("  - " + repo);
            }
        }
        if (!metadata.skippedNoPom().isEmpty()) {
            System.out.println("Skipped (no pom.xml): " + metadata.skippedNoPom().size());
            for (String repo : metadata.skippedNoPom()) {
                System.out.println("  - " + repo);
            }
        }
        if (!metadata.failedClones().isEmpty()) {
            System.out.println("Failed to clone: " + metadata.failedClones().size());
            for (ScanMetadata.FailedClone failure : metadata.failedClones()) {
                System.out.println("  - " + failure.repoName() + ": " + failure.reason());
            }
        }
        System.out.println();
    }

    private String resolveGitHubToken() {
        if (githubToken != null && !githubToken.isBlank()) {
            return githubToken;
        }
        return System.getenv("GITHUB_TOKEN");
    }

    /** Internal bundle of POM paths + scan metadata. */
    private record ScanResult(List<Path> pomPaths, ScanMetadata metadata) {}
}
