# bom-migrate — Claude Code Conventions

## Build & Run

```bash
# Build everything (compile + test)
mvn clean verify

# Run tests only
mvn test

# Run tests for core module only
mvn test -pl bom-migrate-core

# Build fat JAR
mvn clean package -DskipTests

# Run the CLI
java -jar bom-migrate-cli/target/bom-migrate-cli-0.1.0-SNAPSHOT.jar --help
```

## Java Version

- **Build requires:** Java 21
- **Runtime minimum:** Java 17
- Parent POM uses `maven.compiler.release=17` (NOT `source`/`target`) to enforce cross-compilation
- **Never use Java 18+ language features or APIs.** Java 17 features are fine: records, sealed classes, pattern matching in instanceof, switch expressions, text blocks.

## Project Structure

- `bom-migrate-core` — Pure library: parsing, resolution, diffing logic. No CLI or I/O dependencies.
- `bom-migrate-cli` — CLI entry point: Picocli wiring, output formatting, GitHub PR integration.

## Code Style

- Records for value types (ResolvedDependency, MigrationCandidate, etc.)
- Final classes where inheritance isn't needed
- Package-private visibility by default; public only for API surface
- No field injection; constructor parameters or builder patterns
- Formatting-preserving XML edits via raw line manipulation, never DOM serialization

## Testing

- JUnit 5 + AssertJ
- Fixture POMs in `bom-migrate-core/src/test/resources/fixtures/`
- Tests must use real fixture POM files, not string literals (except for VersionLineLocator inline tests)
- Expected output fixtures in `fixtures/expected/` for exact string comparison
