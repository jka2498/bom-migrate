package dev.jka.bommigrate.cli;

import dev.jka.bommigrate.core.discovery.BomGenerationPlan;
import dev.jka.bommigrate.core.discovery.BomGenerator;
import dev.jka.bommigrate.core.discovery.BomModule;
import dev.jka.bommigrate.core.discovery.DependencyFrequencyAnalyser;
import dev.jka.bommigrate.core.discovery.DiscoveryReport;
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

    @ArgGroup(exclusive = true, multiplicity = "1")
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
        // 1. Gather target POMs (local or from org)
        List<Path> pomPaths;
        if (targetSource.org != null) {
            pomPaths = fetchFromOrg();
        } else {
            pomPaths = discoverLocalPoms();
        }

        if (pomPaths.isEmpty()) {
            System.err.println("No target POM files found.");
            return 1;
        }

        // 2. Run discovery analysis
        System.out.println("Analysing " + pomPaths.size() + " POM(s)...");
        DependencyFrequencyAnalyser analyser = new DependencyFrequencyAnalyser();
        DiscoveryReport report = analyser.analyse(pomPaths, minFrequency);

        DiscoveryReportFormatter formatter = new DiscoveryReportFormatter();
        System.out.println();
        System.out.println(formatter.formatSummary(report));

        // 3. Determine BOM modules
        List<BomModule> modules = ModuleDefinitionWizard.parseModuleList(bomModules);

        // 4. Interactive review (web or CLI wizard)
        if (web) {
            try {
                WebServerLauncher.start(report, modules, webPort, outputDir,
                        bomGroupId, bomArtifactId, bomVersion);
                System.out.println("Press Ctrl+C to stop the web server when done.");
                // Block the main thread so the server stays up
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }

        if (System.console() == null && !dryRun) {
            System.err.println("No interactive terminal detected. Printing full report only.");
            for (int i = 0; i < report.candidates().size(); i++) {
                System.out.println(formatter.formatCandidate(report.candidates().get(i), i + 1, report.candidates().size()));
            }
            return 0;
        }

        if (dryRun) {
            for (int i = 0; i < report.candidates().size(); i++) {
                System.out.println(formatter.formatCandidate(report.candidates().get(i), i + 1, report.candidates().size()));
            }
            return 0;
        }

        CandidateReviewWizard wizard = new CandidateReviewWizard();
        BomGenerationPlan plan = wizard.review(report, modules, bomGroupId, bomArtifactId, bomVersion);

        // 5. Generate BOM
        System.out.println();
        System.out.println("Generating BOM at: " + outputDir.toAbsolutePath());
        BomGenerator generator = new BomGenerator();
        List<Path> written = generator.writeTo(plan, outputDir);
        for (Path p : written) {
            System.out.println("  wrote " + p);
        }

        return 0;
    }

    private List<Path> fetchFromOrg() throws IOException {
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
        for (ClonedRepo repo : result.clonedRepos()) {
            pomFiles.addAll(repo.pomPaths());
        }
        return pomFiles;
    }

    private List<Path> discoverLocalPoms() {
        List<Path> pomFiles = new ArrayList<>();
        for (Path target : targetSource.targetPaths) {
            if (Files.isRegularFile(target)) {
                pomFiles.add(target);
            } else if (Files.isDirectory(target)) {
                Path pom = target.resolve("pom.xml");
                if (Files.exists(pom)) {
                    pomFiles.add(pom);
                } else {
                    System.err.println("Warning: No pom.xml found in " + target);
                }
            }
        }
        return pomFiles;
    }

    private String resolveGitHubToken() {
        if (githubToken != null && !githubToken.isBlank()) {
            return githubToken;
        }
        return System.getenv("GITHUB_TOKEN");
    }
}
