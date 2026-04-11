package dev.jka.bommigrate.web.dto;

import java.util.List;

/**
 * Full migration preview response: the "import this BOM" snippet users paste
 * into their own POMs, plus one {@link ServicePreview} per scanned service
 * showing the modified-after-strip content and any flagged dependencies.
 */
public record MigrationPreviewResponse(
        String bomImportSnippet,
        List<ServicePreview> services
) {
}
