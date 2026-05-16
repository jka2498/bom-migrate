package dev.jka.bommigrate.web.dto;

import java.util.List;

/**
 * Per-service migration preview for the web UI. Contains both the full modified
 * POM content (with strips applied and the BOM import block inserted) and a
 * structured diff so the UI can render it GitHub-style.
 *
 * <p>When the BOM has plugins, both an "import" diff and a "parent" diff are
 * generated so the user can compare the two approaches side by side.
 *
 * @param displayName          label shown in the UI (original filename from upload or scan)
 * @param stripCount           number of version tags that would be removed (import mode)
 * @param flagCount            number of version mismatches flagged for review (import mode)
 * @param skipCount            number of dependencies skipped
 * @param modifiedContent      the full POM XML after strips + BOM import, for copy-paste
 * @param diffLines            structured diff entries for the "import" approach
 * @param flagged              per-dependency details for the flagged entries (import mode)
 * @param versionChanges       deps whose version was bumped by the BOM
 * @param parentDiffLines      structured diff for "use as parent" (null if no plugins)
 * @param parentModifiedContent full POM XML for parent mode (null if no plugins)
 */
public record ServicePreview(
        String displayName,
        int stripCount,
        int flagCount,
        int skipCount,
        String modifiedContent,
        List<DiffLine> diffLines,
        List<FlaggedDependency> flagged,
        List<VersionChange> versionChanges,
        List<DiffLine> parentDiffLines,
        String parentModifiedContent
) {
}
