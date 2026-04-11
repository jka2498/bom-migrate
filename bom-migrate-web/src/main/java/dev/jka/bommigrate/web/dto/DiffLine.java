package dev.jka.bommigrate.web.dto;

/**
 * A single line in a rendered diff. {@code status} is one of {@code UNCHANGED},
 * {@code REMOVED}, or {@code ADDED} — the web UI uses it to colour the line
 * (green for added, red for removed, neutral for unchanged).
 *
 * <p>{@code oldNumber}/{@code newNumber} are 1-based line numbers in the
 * original/modified POM respectively, or {@code -1} when the line doesn't
 * exist in that side.
 */
public record DiffLine(String status, String content, int oldNumber, int newNumber) {
}
