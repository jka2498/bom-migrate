# bom-migrate

A CLI tool that automates removing hardcoded `<version>` tags from microservice Maven POMs when those versions are managed by a BOM (Bill of Materials).

## The Problem

When a Java team adopts a Maven BOM for dependency governance, every microservice POM still has hardcoded `<version>` tags for dependencies that are now managed by the BOM. Manually removing these across dozens of repos is tedious and error-prone.

## What bom-migrate Does

1. **Parses** your BOM (including multi-module BOMs and property-based versions)
2. **Compares** each microservice POM's dependencies against the BOM
3. **Classifies** each dependency:
   - **STRIP** — version matches the BOM exactly, safe to remove the `<version>` tag
   - **FLAG** — version is managed by the BOM but differs, needs human review
   - **SKIP** — not managed by the BOM, left untouched
4. **Applies** changes while preserving POM formatting (no reformatting of untouched lines)
5. **Reports** clearly what was changed, flagged, and skipped

## Requirements

- **Java 17** or later to run (the tool's JVM version is independent of your microservices' Java version — teams on Java 17 services can run this tool without any JVM changes)
- **Java 21** to build from source
- **Maven 3.8+** to build from source

## Build

```bash
mvn clean package
```

This produces a fat JAR at `bom-migrate-cli/target/bom-migrate-cli-0.1.0-SNAPSHOT.jar`.

## Usage

### Dry run against a single microservice (no token needed)

```bash
java -jar bom-migrate-cli/target/bom-migrate-cli-0.1.0-SNAPSHOT.jar \
  --bom ./my-bom \
  --target ./my-service \
  --dry-run
```

### Apply changes locally (no token needed)

```bash
java -jar bom-migrate-cli/target/bom-migrate-cli-0.1.0-SNAPSHOT.jar \
  --bom ./my-bom \
  --target ./my-service
```

### Multi-module BOM, multiple targets

```bash
java -jar bom-migrate-cli/target/bom-migrate-cli-0.1.0-SNAPSHOT.jar \
  --bom ./bom-parent \
  --target ./services/* \
  --dry-run
```

### Open PRs (works for both public and private repos)

Token can be passed as a flag or via `GITHUB_TOKEN` env var:

```bash
java -jar bom-migrate-cli/target/bom-migrate-cli-0.1.0-SNAPSHOT.jar \
  --bom ./my-bom \
  --target ./my-service \
  --open-pr \
  --github-token $GITHUB_TOKEN
```

Or using the environment variable (preferred — avoids token in shell history):

```bash
export GITHUB_TOKEN=ghp_yourtoken
java -jar bom-migrate-cli/target/bom-migrate-cli-0.1.0-SNAPSHOT.jar \
  --bom ./my-bom \
  --target ./my-service \
  --open-pr
```

## CLI Options

| Option | Description |
|---|---|
| `--bom`, `-b` | Path to the BOM pom.xml or parent directory (required) |
| `--target`, `-t` | One or more paths to target POM files or directories (required) |
| `--dry-run` | Print report and diff without modifying files |
| `--include-transitive-bom-imports` | Also resolve BOMs imported via `<scope>import</scope>` |
| `--open-pr` | Create a GitHub PR per repo with the changes |
| `--github-token` | GitHub personal access token with `repo` scope (falls back to `GITHUB_TOKEN` env var) |
| `--help` | Show help |
| `--version` | Show version |

## Architecture

```
bom-migrate/
├── bom-migrate-core/    # Pure library — no CLI or I/O dependencies
│   ├── model/           # ResolvedDependency, MigrationReport, etc.
│   ├── resolver/        # BOM parsing, property interpolation
│   └── migrator/        # POM analysis, format-preserving writes
└── bom-migrate-cli/     # Picocli CLI, output formatting, GitHub PR
```

The core module is usable as a standalone library for programmatic integration.

## Edge Cases Handled

- Property-based versions in both BOMs and microservice POMs
- Multi-module BOM structures (parent aggregator + child BOM modules)
- BOM importing other BOMs (configurable transitive resolution)
- Version ranges (detected and skipped)
- Classifier and type variants (exact `groupId:artifactId:type:classifier` matching)
- Duplicate dependency declarations (flagged for review)
- Dependencies with no version tag (already managed, skipped)

## V1 Limitations

- **Parent POM inheritance of managed dependencies** — If a microservice POM inherits `<dependencyManagement>` entries from its `<parent>` (not via a BOM import but through direct parent POM inheritance), the tool does not resolve the parent chain to discover those managed versions. It only resolves properties inherited from the parent for version comparison. A dependency managed solely through parent inheritance will be classified as SKIP ("not managed by BOM") rather than being matched. Workaround: pass the parent POM as an additional `--bom` input if it also acts as a dependency manager.
- Operates on local file paths only (no remote repo cloning)
- Remote BOM imports (by GAV coordinates not on filesystem) are skipped with a warning
- Gradle projects are not supported
- Plugin dependencies (`<build><plugins>`) are not processed
