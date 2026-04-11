package dev.jka.bommigrate.web.controller;

import dev.jka.bommigrate.web.dto.MigrationPreviewResponse;
import dev.jka.bommigrate.web.service.MigrationPreviewService;
import dev.jka.bommigrate.web.service.StalePreviewException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * Exposes the migration preview for the web UI. Read-only — no apply action,
 * no write-back. The response contains per-service modified POM content
 * that users copy-paste into their real projects.
 */
@RestController
@RequestMapping("/api/migration")
public class MigrationController {

    private final MigrationPreviewService previewService;

    public MigrationController(MigrationPreviewService previewService) {
        this.previewService = previewService;
    }

    @GetMapping("/preview")
    public ResponseEntity<?> getPreview() throws IOException {
        try {
            return ResponseEntity.ok(previewService.buildPreview());
        } catch (StalePreviewException e) {
            // 409 Conflict — the session state no longer matches the generated BOM.
            // Frontend listens for this specifically and prompts the user to regenerate.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("reason", "stale", "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
        }
    }
}
