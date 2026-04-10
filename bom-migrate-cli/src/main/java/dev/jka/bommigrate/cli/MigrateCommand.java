package dev.jka.bommigrate.cli;

import dev.jka.bommigrate.core.migrator.PomAnalyzer;
import dev.jka.bommigrate.core.migrator.PomWriter;
import dev.jka.bommigrate.core.model.DependencyManagementMap;
import dev.jka.bommigrate.core.model.MigrationReport;
import dev.jka.bommigrate.core.resolver.DefaultBomResolver;
import dev.jka.bommigrate.github.ClonedRepo;
import dev.jka.bommigrate.github.OrgScanResult;
import dev.jka.bommigrate.github.OrgScanner;
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
 * Removes hardcoded {@code <version>} tags from microservice POMs managed by a BOM.
 * Supports both local target paths and GitHub org fetching.
 */
@Command(
        name = "migrate",
        mixinStandardHelpOptions = true,
        description = "Remove hardcoded <version> tags from microservice POMs managed by a BOM."
)
public class MigrateCommand implements Callable<Integer> {

    @Option(names = {"--bom", "-b"}, required = true,
            description = "Path to the BOM pom.xml or its parent directory")
    Path bomPath;

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

    @Option(names = "--dry-run", defaultValue = "false",
            description = "Print report and diff without modifying files")
    boolean dryRun;

    @Option(names = "--include-transitive-bom-imports", defaultValue = "false",
            description = "Also resolve BOMs imported via <scope>import</scope> in the BOM")
    boolean includeTransitiveBomImports;

    @Option(names = "--open-pr", defaultValue = "false",
            description = "Create a GitHub PR per repo with the changes")
    boolean openPr;

    @Option(names = "--github-token",
            description = "GitHub personal access token (repo scope). Falls back to GITHUB_TOKEN env var.")
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
        if (openPr) {
            String token = resolveGitHubToken();
            if (token == null || token.isBlank()) {
                System.err.println("Error: --open-pr requires a GitHub token. "
                        + "Pass --github-token or set GITHUB_TOKEN env var.");
                return 1;
            }
        }

        // Resolve BOM
        System.out.println("Resolving BOM: " + bomPath);
        DefaultBomResolver resolver = new DefaultBomResolver();
        DependencyManagementMap bomMap = resolver.resolve(bomPath, includeTransitiveBomImports);
        System.out.println("BOM contains " + bomMap.size() + " managed dependencies.\n");

        // Discover target POMs (local or from org)
        List<Path> pomFiles;
        if (targetSource.org != null) {
            pomFiles = fetchFromOrg();
        } else {
            pomFiles = discoverLocalPoms();
        }

        if (pomFiles.isEmpty()) {
            System.err.println("No target POM files found.");
            return 1;
        }

        // Analyze each target
        PomAnalyzer analyzer = new PomAnalyzer();
        PomWriter writer = new PomWriter();
        ReportFormatter formatter = new ReportFormatter();
        List<MigrationReport> reports = new ArrayList<>();

        for (Path pomFile : pomFiles) {
            MigrationReport report = analyzer.analyze(pomFile, bomMap);
            reports.add(report);
            System.out.println(formatter.format(report));

            if (report.hasChanges()) {
                if (dryRun) {
                    System.out.println(writer.generateDiff(pomFile, report));
                } else {
                    String modified = writer.applyStrips(pomFile, report);
                    writer.writeTo(pomFile, modified);
                    System.out.println("  Changes applied to " + pomFile);
                }
            }
            System.out.println();
        }

        System.out.println(formatter.formatSummary(reports));

        // Open PRs if requested
        if (openPr && !dryRun) {
            String token = resolveGitHubToken();
            GitHubPrService prService = new GitHubPrService(token);
            for (MigrationReport report : reports) {
                if (report.hasChanges()) {
                    try {
                        String prUrl = prService.createPullRequest(report, bomPath);
                        System.out.println("PR created: " + prUrl);
                    } catch (IOException e) {
                        System.err.println("Failed to create PR for " + report.pomPath() + ": " + e.getMessage());
                    }
                }
            }
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
        if (!includeAllLanguages) {
            System.out.println("Pre-filtering to JVM-language repos (pass --include-all-languages to disable)");
        }

        OrgScanner scanner = new OrgScanner(token);
        OrgScanResult result = scanner.scan(targetSource.org, repoFilter, includeAllLanguages,
                token, effectiveCloneDir, keepClones);

        System.out.println("  cloned: " + result.clonedRepos().size());
        System.out.println("  skipped (non-JVM): " + result.skippedByLanguage().size());
        System.out.println("  skipped (no pom.xml): " + result.skippedNoPom().size());
        System.out.println("  failed: " + result.failedToClone().size());
        for (OrgScanResult.FailedRepo f : result.failedToClone()) {
            System.err.println("  [FAIL] " + f.repoName() + ": " + f.reason());
        }
        System.out.println();

        List<Path> pomFiles = new ArrayList<>();
        for (ClonedRepo repo : result.clonedRepos()) {
            pomFiles.addAll(repo.pomPaths());
        }
        return pomFiles;
    }

    private List<Path> discoverLocalPoms() {
        List<Path> pomFiles = new ArrayList<>();
        for (Path target : targetSource.targetPaths) {
            if (Files.isRegularFile(target) && target.getFileName().toString().equals("pom.xml")) {
                pomFiles.add(target);
            } else if (Files.isDirectory(target)) {
                Path pom = target.resolve("pom.xml");
                if (Files.exists(pom)) {
                    pomFiles.add(pom);
                } else {
                    System.err.println("Warning: No pom.xml found in " + target);
                }
            } else if (Files.isRegularFile(target)) {
                pomFiles.add(target);
            } else {
                System.err.println("Warning: Target path does not exist: " + target);
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
