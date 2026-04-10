# bom-migrate — Claude Code Conventions

## Build & Run

```bash
# Build everything (compile + test) — all 4 modules
mvn clean verify

# Run tests only
mvn test

# Run tests for a specific module
mvn test -pl bom-migrate-core
mvn test -pl bom-migrate-github
mvn test -pl bom-migrate-web
mvn test -pl bom-migrate-cli

# Build fat JAR
mvn clean package -DskipTests

# Run the CLI (shows parent help with subcommands)
java -jar bom-migrate-cli/target/bom-migrate-cli-1.2.0-SNAPSHOT.jar --help

# Run migrate subcommand
java -jar bom-migrate-cli/target/bom-migrate-cli-1.2.0-SNAPSHOT.jar migrate --bom ./my-bom --target ./my-service

# Run discover subcommand
java -jar bom-migrate-cli/target/bom-migrate-cli-1.2.0-SNAPSHOT.jar discover --target ./services/*
```

## Java Version

- **Build requires:** Java 21
- **Runtime minimum:** Java 17
- Parent POM uses `maven.compiler.release=17` (NOT `source`/`target`) to enforce cross-compilation
- **Never use Java 18+ language features or APIs.** Java 17 features are fine: records, sealed classes, pattern matching in instanceof, switch expressions, text blocks.

## Project Structure (4 modules)

- `bom-migrate-core` — Pure library: parsing, resolution, diffing, discovery engine. No CLI, web, or GitHub dependencies.
- `bom-migrate-github` — GitHub org fetching and shallow cloning via JGit. Depends on core.
- `bom-migrate-web` — Spring Boot 3.x web UI for discovery review. Depends on core only.
- `bom-migrate-cli` — CLI entry point with Picocli subcommands (`migrate`, `discover`). Depends on all above.

**Dependency direction:** core ← github, core ← web, core ← cli (and cli pulls in github + web).

## Subcommand Structure

- `bom-migrate migrate ...` — V1 behaviour: remove hardcoded `<version>` tags managed by a BOM
- `bom-migrate discover ...` — V2 feature: find BOM candidates across services
- **Legacy fallback**: `bom-migrate --bom X --target Y` (no subcommand) is rewritten to `bom-migrate migrate --bom X --target Y` in `main()` for backward compatibility with V1 scripts

## Code Style

- Records for value types (ResolvedDependency, MigrationCandidate, BomCandidate, etc.)
- Final classes where inheritance isn't needed
- Package-private visibility by default; public only for API surface
- No field injection; constructor parameters or builder patterns
- Formatting-preserving XML edits via raw line manipulation, never DOM serialization
- BOM generation uses `MavenXpp3Writer` (from existing `maven-model` dependency) — no new dependencies for XML writing
- Picocli `@ArgGroup` with mutually-exclusive target sources must be **static nested classes** (not standalone types), otherwise the picocli annotation processor fails to compile

## Testing

- JUnit 5 + AssertJ
- Fixture POMs in `bom-migrate-core/src/test/resources/fixtures/`
- Discovery fixture POMs in `bom-migrate-core/src/test/resources/fixtures/discovery/`
- Tests must use real fixture POM files, not string literals (except for VersionLineLocator and scoring tests)
- Expected output fixtures in `fixtures/expected/` for exact string comparison
- Spring Boot tests use `@SpringBootTest` with `MockMvc` for REST API coverage
- GitHub API tests avoid network — only test pure logic (`RepoFilter`, POM discovery, cleanup)

## Shade Plugin + Spring Boot

The CLI fat JAR uses `maven-shade-plugin` and must preserve Spring Boot auto-config metadata via `AppendingTransformer`:
- `META-INF/spring.factories`
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `META-INF/spring/aot.factories`
- Plus `ServicesResourceTransformer` for `META-INF/services/*`

Without these transformers, Spring Boot won't find its auto-config classes inside the shaded JAR and the embedded Tomcat server won't start.

## Web Frontend

`bom-migrate-web/src/main/resources/static/` contains pre-built static assets (vanilla HTML/CSS/JS). This is intentionally **not** rebuilt by Maven — no `frontend-maven-plugin`, no Node.js required to build the Java project.

To modify the frontend: edit `index.html`, `assets/app.css`, `assets/app.js` directly and commit. For a future React+Vite rewrite, build separately and copy the output into `static/`.

## GitHub Org Fetching

- Uses `org.kohsuke:github-api` for listing + metadata
- Uses JGit for shallow cloning (`setDepth(1)`)
- Falls back to full clone if shallow is rejected by the remote
- Two-stage Java detection: pre-clone language filter (`GHRepository.getLanguage()`) + post-clone `pom.xml` verification
- `--include-all-languages` escape hatch disables the language pre-filter
- Token is never logged or printed; passed to JGit via `UsernamePasswordCredentialsProvider("x-access-token", token)`
