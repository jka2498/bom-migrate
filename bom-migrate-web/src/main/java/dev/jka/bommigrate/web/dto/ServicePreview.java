package dev.jka.bommigrate.web.dto;

import java.util.List;

/**
 * Per-service migration preview for the web UI. Contains the full modified
 * POM content after applying strip changes — users copy-paste this into
 * their own files. No write-back happens through the web flow.
 *
 * @param displayName       label shown in the UI (original filename from upload or scan)
 * @param stripCount        number of version tags that would be removed
 * @param flagCount         number of version mismatches flagged for review
 * @param skipCount         number of dependencies skipped (not managed by BOM etc.)
 * @param modifiedContent   the full POM XML after strips applied, for copy-paste
 * @param flagged           per-dependency details for the flagged entries
 */
public record ServicePreview(
        String displayName,
        int stripCount,
        int flagCount,
        int skipCount,
        String modifiedContent,
        List<FlaggedDependency> flagged
) {
}
