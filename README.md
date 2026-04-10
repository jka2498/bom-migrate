# bom-migrate

A CLI tool that automates removing hardcoded `<version>` tags from microservice Maven POMs when those versions are managed by a BOM (Bill of Materials), and **discovers BOM candidates** by scanning services for commonly-used dependencies.

## The Problem

When a Java team adopts a Maven BOM for dependency governance, every microservice POM still has hardcoded `<version>` tags for dependencies that are now managed by the BOM. Manually removing these across dozens of repos is tedious and error-prone.

And if you **don't** have a BOM yet, figuring out which dependencies to include is a manual spreadsheet exercise — `bom-migrate discover` automates that too.

## What bom-migrate Does

### `migrate` — remove hardcoded versions

1. **Parses** your BOM (including multi-module BOMs and property-based versions)
2. **Compares** each microservice POM's dependencies against the BOM
3. **Classifies** each dependency:
   - **STRIP** — version matches the BOM exactly, safe to remove the `<version>` tag
   - **FLAG** — version is managed by the BOM but differs, needs human review
   - **SKIP** — not managed by the BOM, left untouched
4. **Applies** changes while preserving POM formatting (no reformatting of untouched lines)
5. **Cleans up** orphaned version properties (e.g. `<guava.version>`) if no longer referenced
6. **Reports** clearly what was changed, flagged, and skipped

### `discover` — find BOM candidates across services (V2)

1. **Scans** a set of service POMs (from local paths or a GitHub org)
2. **Ranks** dependencies by frequency and version consistency
3. **Flags** major version conflicts across services
4. **Interactively reviews** each candidate via CLI wizard or web UI
5. **Generates** a multi-module BOM structure ready to commit

## Requirements

- **Java 17** or later to run (independent of your services' Java version)
- **Java 21** to build from source
- **Maven 3.8+** to build from source

## Build

```bash
mvn clean package
```

Produces a fat JAR at `bom-migrate-cli/target/bom-migrate-cli-<version>.jar`.

## Usage — `migrate`

### Dry run against a local service (no token needed)

```bash
java -jar bom-migrate-cli/target/bom-migrate-cli-*.jar migrate \
  --bom ./my-bom \
  --target ./my-service \
  --dry-run
```

### Apply changes locally

```bash
java -jar bom-migrate-cli/target/bom-migrate-cli-*.jar migrate \
  --bom ./my-bom \
  --target ./my-service
```

### Multi-module BOM, multiple targets

```bash
java -jar bom-migrate-cli/target/bom-migrate-cli-*.jar migrate \
  --bom ./bom-parent \
  --target ./services/* \
  --dry-run
```

### Org fetch with glob filter (V2)

```bash
# Prefix match
java -jar bom-migrate-cli/target/bom-migrate-cli-*.jar migrate \
  --bom ./my-bom \
  --org my-org \
  --repo-filter "claims-*"

# Wildcard in middle
java -jar bom-migrate-cli/target/bom-migrate-cli-*.jar migrate \
  --bom ./my-bom \
  --org my-org \
  --repo-filter "claims-digital-*-service"

# Skip the Java primary-language pre-filter (e.g. for hybrid repos)
java -jar bom-migrate-cli/target/bom-migrate-cli-*.jar migrate \
  --bom ./my-bom \
  --org my-org \
  --repo-filter "claims-*" \
  --include-all-languages
```

### Open PRs (works for both public and private repos)

```bash
export GITHUB_TOKEN=ghp_yourtoken
java -jar bom-migrate-cli/target/bom-migrate-cli-*.jar migrate \
  --bom ./my-bom \
  --target ./my-service \
  --open-pr
```

### Legacy V1 syntax (still works)

```bash
# Leading flag without subcommand is automatically rewritten to "migrate ..."
java -jar bom-migrate-cli/target/bom-migrate-cli-*.jar --bom ./my-bom --target ./my-service
```

## Usage — `discover` (V2)

### Discover candidates from local services

```bash
java -jar bom-migrate-cli/target/bom-migrate-cli-*.jar discover --target ./services/*
```

Runs the discovery analysis, then prompts interactively to include/exclude each candidate.

### Discover with BOM module assignment

Split your generated BOM into multiple child modules (e.g. `backend-core`, `misc`):

```bash
java -jar bom-migrate-cli/target/bom-migrate-cli-*.jar discover \
  --target ./services/* \
  --bom-modules "backend-core,misc"
```

### Discover with web UI

```bash
java -jar bom-migrate-cli/target/bom-migrate-cli-*.jar discover \
  --target ./services/* \
  --web
```

Starts a local web server (default port 8080), opens the browser, and lets you review candidates visually with sortable columns, bulk module assignment, and a "Generate BOM" button.

### Discover from a GitHub org with the web UI

```bash
export GITHUB_TOKEN=ghp_yourtoken
java -jar bom-migrate-cli/target/bom-migrate-cli-*.jar discover \
  --org my-org \
  --repo-filter "claims-*" \
  --bom-modules "backend-core,misc" \
  --web
```

### Full flow: discover + generate BOM + migrate all services + open PRs

```bash
java -jar bom-migrate-cli/target/bom-migrate-cli-*.jar discover \
  --org my-org \
  --bom-modules "backend-core,misc" \
  --web \
  --also-migrate \
  --open-pr
```

## CLI Options Reference

### `migrate` subcommand

| Option | Description |
|---|---|
| `--bom`, `-b` | Path to the BOM pom.xml or parent directory (required) |
| `--target`, `-t` | One or more paths to target POM files or directories (exclusive with `--org`) |
| `--org` | GitHub organisation to fetch repos from (exclusive with `--target`) |
| `--repo-filter` | Glob pattern to filter org repos (e.g. `claims-*`) |
| `--include-all-languages` | Skip the Java primary-language pre-filter |
| `--keep-clones` | Don't delete cloned repos after the run |
| `--clone-dir` | Override the temp directory for clones |
| `--dry-run` | Print report and diff without modifying files |
| `--include-transitive-bom-imports` | Resolve BOMs imported via `<scope>import</scope>` |
| `--open-pr` | Create a GitHub PR per repo with the changes |
| `--github-token` | GitHub token (falls back to `GITHUB_TOKEN` env var) |

### `discover` subcommand

| Option | Description |
|---|---|
| `--target`, `-t` | Local paths to service POMs (exclusive with `--org`) |
| `--org` | GitHub organisation to fetch from (exclusive with `--target`) |
| `--repo-filter` | Glob pattern for org repos |
| `--include-all-languages` | Skip the Java primary-language pre-filter |
| `--bom-modules` | Comma-separated BOM module names (e.g. `"backend-core,misc"`) |
| `--bom-group-id` | groupId for the generated BOM (default `com.example`) |
| `--bom-artifact-id` | artifactId for the generated BOM (default `my-bom`) |
| `--bom-version` | version for the generated BOM (default `1.0.0`) |
| `--output-dir` | Directory to write the generated BOM into (default `.`) |
| `--min-frequency` | Exclude candidates used by fewer than N services (default 1) |
| `--web` | Launch web UI for interactive candidate review |
| `--web-port` | Port for the web UI (default 8080) |
| `--also-migrate` | After generating, immediately migrate all scanned services |
| `--dry-run` | Show the report without writing files or opening PRs |
| `--open-pr` | Create a GitHub PR with the generated BOM |
| `--github-token` | GitHub token (falls back to `GITHUB_TOKEN` env var) |

## Glob Filter Syntax

Org `--repo-filter` patterns use standard shell globs via `java.nio.file.PathMatcher`:

- `*` — matches any characters except `/`
- `?` — matches any single character
- `[abc]` — matches any character in set
- `{foo,bar}` — matches either alternative

Examples:
- `claims-*` — all repos starting with `claims-`
- `claims-digital-sl-*` — prefix match
- `claims-digital-*-service` — wildcard in middle
- `*-service` — suffix match

## Architecture

```
bom-migrate/
├── bom-migrate-core/       # Pure library — no CLI, web, or GitHub deps
│   ├── model/              # ResolvedDependency, MigrationReport, PomModelReader
│   ├── resolver/           # BOM parsing, property interpolation
│   ├── migrator/           # POM analysis, format-preserving writes
│   └── discovery/          # DependencyFrequencyAnalyser, BomGenerator, etc.
├── bom-migrate-github/     # GitHub org fetching and shallow cloning (JGit)
│   └── GitHubOrgFetcher, RepoCloner, OrgScanner
├── bom-migrate-web/        # Spring Boot web UI for discovery review
│   ├── controller/         # REST API
│   ├── service/            # DiscoverySessionService
│   └── static/             # Pre-built HTML/CSS/JS frontend
└── bom-migrate-cli/        # Picocli CLI (migrate + discover subcommands)
```

## Edge Cases Handled

- Property-based versions in both BOMs and microservice POMs
- Multi-module BOM structures (parent aggregator + child BOM modules)
- BOM importing other BOMs (configurable transitive resolution)
- Version ranges (detected and skipped)
- Classifier and type variants (exact `groupId:artifactId:type:classifier` matching)
- Duplicate dependency declarations (flagged for review)
- Dependencies with no version tag (already managed, skipped)
- Orphaned version properties auto-cleaned after stripping
- Major version conflicts across services flagged as HIGH priority
- Monorepo detection (multiple POMs per cloned repo)

## Limitations

- **Parent POM inheritance of managed dependencies** — A dependency managed solely through `<parent>` POM inheritance (not via BOM import) is classified as SKIP. Workaround: pass the parent POM as an additional `--bom` input.
- **Remote BOM imports** — BOMs imported by GAV coordinates that aren't on the filesystem are skipped with a warning.
- **Gradle projects** — not supported.
- **Plugin dependencies** — `<build><plugins>` sections are not processed.
- **Web UI is pre-built** — `bom-migrate-web/src/main/resources/static/` contains vanilla HTML/CSS/JS. No Node.js is used by the Maven build. Edit the files directly to customise.
