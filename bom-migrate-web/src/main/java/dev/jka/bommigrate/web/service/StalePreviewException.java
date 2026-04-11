package dev.jka.bommigrate.web.service;

/**
 * Thrown by {@link MigrationPreviewService} when the on-disk generated BOM no
 * longer matches the current session state — typically because the user
 * edited coordinates, modules, assignments or version format after the
 * previous generation. {@link dev.jka.bommigrate.web.controller.MigrationController}
 * maps this to HTTP 409 so the frontend can prompt the user to regenerate.
 */
public class StalePreviewException extends RuntimeException {
    public StalePreviewException(String message) {
        super(message);
    }
}
