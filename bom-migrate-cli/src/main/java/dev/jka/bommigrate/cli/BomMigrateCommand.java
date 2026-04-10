package dev.jka.bommigrate.cli;

import dev.jka.bommigrate.core.migrator.PomAnalyzer;
import dev.jka.bommigrate.core.migrator.PomWriter;
import dev.jka.bommigrate.core.model.DependencyManagementMap;
import dev.jka.bommigrate.core.model.MigrationReport;
import dev.jka.bommigrate.core.resolver.DefaultBomResolver;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "bom-migrate",
        mixinStandardHelpOptions = true,
        version = "bom-migrate 0.1.0",
        description = "Remove hardcoded <version> tags from microservice POMs when managed by a Maven BOM."
)
public class BomMigrateCommand implements Callable<Integer> {

    @Option(names = {"--bom", "-b"}, required = true,
            description = "Path to the BOM pom.xml or its parent directory")
    private Path bomPath;

    @Option(names = {"--target", "-t"}, required = true, arity = "1..*",
            description = "One or more paths to target microservice pom.xml files or directories")
    private List<Path> targetPaths;

    @Option(names = "--dry-run", defaultValue = "false",
            description = "Print report and diff without modifying files")
    private boolean dryRun;

    @Option(names = "--include-transitive-bom-imports", defaultValue = "false",
            description = "Also resolve BOMs imported via <scope>import</scope> in the BOM")
    private boolean includeTransitiveBomImports;

    @Option(names = "--open-pr", defaultValue = "false",
            description = "Create a GitHub PR per repo with the changes")
    private boolean openPr;

    @Option(names = "--github-token",
            description = "GitHub personal access token (repo scope). Falls back to GITHUB_TOKEN env var.")
    private String githubToken;

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
        // Validate PR options
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

        // Discover target POMs
        List<Path> pomFiles = discoverTargetPoms();
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

        // Summary
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

    private List<Path> discoverTargetPoms() {
        List<Path> pomFiles = new ArrayList<>();
        for (Path target : targetPaths) {
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
                // Accept any XML file path directly
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

    public static void main(String[] args) {
        int exitCode = new CommandLine(new BomMigrateCommand()).execute(args);
        System.exit(exitCode);
    }
}
