package dev.jka.bommigrate.web.dto;

import java.util.List;

/**
 * Per-service migration preview for the web UI. Contains both the full modified
 * POM content (with strips applied and the BOM import block inserted) and a
 * structured diff so the UI can render it GitHub-style.
 *
 * @param displayName       label shown in the UI (original filename from upload or scan)
 * @param stripCount        number of version tags that would be removed
 * @param flagCount         number of version mismatches flagged for review
 * @param skipCount         number of dependencies skipped
 * @param modifiedContent   the full POM XML after strips + BOM import, for copy-paste
 * @param diffLines         structured diff entries (status + line content) for rendering
 * @param flagged           per-dependency details for the flagged entries
 */
public record ServicePreview(
        String displayName,
        int stripCount,
        int flagCount,
        int skipCount,
        String modifiedContent,
        List<DiffLine> diffLines,
        List<FlaggedDependency> flagged
) {
}
