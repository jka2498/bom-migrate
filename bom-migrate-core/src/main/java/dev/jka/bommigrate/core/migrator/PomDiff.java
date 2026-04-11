package dev.jka.bommigrate.core.migrator;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured line-by-line diff between two versions of a POM file.
 * Unlike a unified diff string, the caller gets a list of {@link Line}s that
 * each carry a {@link Status}, so a UI can render red/green like GitHub.
 *
 * <p>This is an intentionally simple line-based diff using the classic LCS
 * algorithm. It does not detect moves or intra-line changes — one line is
 * either {@link Status#UNCHANGED}, {@link Status#REMOVED}, or
 * {@link Status#ADDED}.
 */
public record PomDiff(List<Line> lines) {

    public enum Status { UNCHANGED, REMOVED, ADDED }

    /**
     * A single diff entry.
     *
     * @param status    whether the line was kept, removed from the original,
     *                  or added in the modified version
     * @param content   the raw line content (no leading +/- marker)
     * @param oldNumber 1-based line number in the original (or -1 if added)
     * @param newNumber 1-based line number in the modified (or -1 if removed)
     */
    public record Line(Status status, String content, int oldNumber, int newNumber) {}

    /**
     * Diffs two POM contents line by line.
     */
    public static PomDiff between(String original, String modified) {
        String[] a = original.split("\\R", -1);
        String[] b = modified.split("\\R", -1);

        // Classic LCS table
        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                if (a[i].equals(b[j])) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        List<Line> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        int oldNum = 1;
        int newNum = 1;
        while (i < a.length && j < b.length) {
            if (a[i].equals(b[j])) {
                out.add(new Line(Status.UNCHANGED, a[i], oldNum++, newNum++));
                i++; j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Line(Status.REMOVED, a[i], oldNum++, -1));
                i++;
            } else {
                out.add(new Line(Status.ADDED, b[j], -1, newNum++));
                j++;
            }
        }
        while (i < a.length) {
            out.add(new Line(Status.REMOVED, a[i], oldNum++, -1));
            i++;
        }
        while (j < b.length) {
            out.add(new Line(Status.ADDED, b[j], -1, newNum++));
            j++;
        }
        return new PomDiff(out);
    }

    /** True if any line was added or removed. */
    public boolean hasChanges() {
        return lines.stream().anyMatch(l -> l.status() != Status.UNCHANGED);
    }
}
