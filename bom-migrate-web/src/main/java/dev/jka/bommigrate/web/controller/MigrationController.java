package dev.jka.bommigrate.web.controller;

import dev.jka.bommigrate.web.dto.MigrationPreviewResponse;
import dev.jka.bommigrate.web.service.MigrationPreviewService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

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
    public ResponseEntity<MigrationPreviewResponse> getPreview() throws IOException {
        try {
            return ResponseEntity.ok(previewService.buildPreview());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
        }
    }
}
