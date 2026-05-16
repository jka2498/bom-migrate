package dev.jka.bommigrate.core.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * The full migration result for a single POM file, covering both
 * dependency and plugin version stripping.
 */
public final class MigrationReport {

    private final Path pomPath;
    private final List<MigrationCandidate> candidates;
    private final List<MigrationCandidate> pluginCandidates;

    public MigrationReport(Path pomPath, List<MigrationCandidate> candidates) {
        this(pomPath, candidates, List.of());
    }

    public MigrationReport(Path pomPath, List<MigrationCandidate> candidates,
                           List<MigrationCandidate> pluginCandidates) {
        this.pomPath = pomPath;
        this.candidates = Collections.unmodifiableList(candidates);
        this.pluginCandidates = Collections.unmodifiableList(pluginCandidates);
    }

    public Path pomPath() {
        return pomPath;
    }

    public List<MigrationCandidate> candidates() {
        return candidates;
    }

    public List<MigrationCandidate> pluginCandidates() {
        return pluginCandidates;
    }

    public List<MigrationCandidate> byAction(MigrationAction action) {
        return candidates.stream()
                .filter(c -> c.action() == action)
                .toList();
    }

    public List<MigrationCandidate> pluginsByAction(MigrationAction action) {
        return pluginCandidates.stream()
                .filter(c -> c.action() == action)
                .toList();
    }

    public int stripCount() {
        return byAction(MigrationAction.STRIP).size() + pluginsByAction(MigrationAction.STRIP).size();
    }

    public int flagCount() {
        return byAction(MigrationAction.FLAG).size() + pluginsByAction(MigrationAction.FLAG).size();
    }

    public int skipCount() {
        return byAction(MigrationAction.SKIP).size() + pluginsByAction(MigrationAction.SKIP).size();
    }

    public boolean hasChanges() {
        return stripCount() > 0;
    }
}
