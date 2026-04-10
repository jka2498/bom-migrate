package dev.jka.bommigrate.core.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * The full migration result for a single POM file.
 */
public final class MigrationReport {

    private final Path pomPath;
    private final List<MigrationCandidate> candidates;

    public MigrationReport(Path pomPath, List<MigrationCandidate> candidates) {
        this.pomPath = pomPath;
        this.candidates = Collections.unmodifiableList(candidates);
    }

    public Path pomPath() {
        return pomPath;
    }

    public List<MigrationCandidate> candidates() {
        return candidates;
    }

    public List<MigrationCandidate> byAction(MigrationAction action) {
        return candidates.stream()
                .filter(c -> c.action() == action)
                .toList();
    }

    public int stripCount() {
        return byAction(MigrationAction.STRIP).size();
    }

    public int flagCount() {
        return byAction(MigrationAction.FLAG).size();
    }

    public int skipCount() {
        return byAction(MigrationAction.SKIP).size();
    }

    public boolean hasChanges() {
        return stripCount() > 0;
    }
}
