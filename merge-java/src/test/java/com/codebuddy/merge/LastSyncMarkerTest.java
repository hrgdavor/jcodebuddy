// {@link com.codebuddy.merge.LastSyncMarkerTest} Tests for the recorded last-synced upstream point.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The base this module resolves against is the <b>last-synced upstream state</b>, not
 * Git's merge base. These tests pin the parts of that definition the code depends on,
 * because getting it wrong produces conflicts that do not exist - see
 * {@code docs/WHAT_IS_BASE.md}.
 */
class LastSyncMarkerTest {

    @TempDir
    Path historyRoot;

    @Test
    @DisplayName("records and reads back the upstream commit that was synced")
    void recordsAndReadsBack() {
        LastSyncMarker.of("abc123def4567890", "origin/main", "sync #3").write(historyRoot);

        LastSyncMarker read = LastSyncMarker.read(historyRoot).orElseThrow();

        assertEquals("abc123def4567890", read.upstreamCommit());
        assertEquals("origin/main", read.upstreamRef());
        assertEquals("sync #3", read.note());
        assertFalse(read.recordedAt().isBlank(), "the marker must be stamped");
    }

    @Test
    @DisplayName("a branch that has never synced has no marker")
    void noMarkerWhenNeverSynced() {
        assertTrue(LastSyncMarker.read(historyRoot).isEmpty(),
            "absence means first sync, which is a case the caller handles");
    }

    @Test
    @DisplayName("lives beside the decision history, under the branch")
    void livesUnderTheBranchHistory() {
        Path branchHistory = historyRoot.resolve("feature-payments");
        LastSyncMarker.of("aaa111", "origin/main").write(branchHistory);

        assertTrue(Files.isRegularFile(branchHistory.resolve(LastSyncMarker.FILE_NAME)));
        assertTrue(LastSyncMarker.read(branchHistory).isPresent());
        // The decisions and the marker tell one story together.
        assertTrue(branchHistory.toString().contains("feature-payments"));
    }

    @Test
    @DisplayName("survives a rewrite of the commit graph")
    void survivesARebase() {
        // A marker is a recorded fact, so nothing about it depends on the graph. This is
        // the practical reason it exists: a merge base is derived and a rebase changes it.
        LastSyncMarker original = LastSyncMarker.of("deadbeef", "origin/main", "before rebase");
        original.write(historyRoot);

        LastSyncMarker afterRebase = LastSyncMarker.read(historyRoot).orElseThrow();

        assertEquals(original.upstreamCommit(), afterRebase.upstreamCommit());
        assertEquals(original.note(), afterRebase.note());
    }

    @Test
    @DisplayName("detects that the upstream is no longer where it was left")
    void detectsUpstreamDifference() {
        LastSyncMarker marker = LastSyncMarker.of("aaa111", "origin/main");

        assertFalse(marker.differsFrom("aaa111"), "the same commit is not a change");
        assertTrue(marker.differsFrom("bbb222"), "a different upstream commit means the branch is out of date");
        assertFalse(marker.differsFrom(null), "an unknown upstream is not evidence of drift");
    }

    @Test
    @DisplayName("a malformed marker is treated as absent, not guessed at")
    void malformedMarkerIsAbsent() {
        assertTrue(LastSyncMarker.parse(null).isEmpty());
        assertTrue(LastSyncMarker.parse("").isEmpty());
        assertTrue(LastSyncMarker.parse("garbage without a separator").isEmpty());
        assertTrue(LastSyncMarker.parse("upstreamRef = origin/main\n").isEmpty(),
            "a marker with no commit id tells us nothing usable");
        assertTrue(LastSyncMarker.parse("upstreamCommit = null\n").isEmpty());
    }

    @Test
    @DisplayName("a commit id is recognised, and anything that can move is not")
    void recognisesACommitId() {
        String sha1 = "eecaadfe6259845cea5d182e4f0f9b7d7f9ab471";
        assertTrue(LastSyncMarker.isCommitId(sha1));
        assertTrue(LastSyncMarker.isCommitId(sha1.toUpperCase()),
            "git prints lowercase, but a person pasting an id should not be punished "
                + "for the case");
        assertTrue(LastSyncMarker.isCommitId("a".repeat(64)),
            "a SHA-256 repository has 64-character ids");

        assertFalse(LastSyncMarker.isCommitId(null));
        assertFalse(LastSyncMarker.isCommitId(""));
        assertFalse(LastSyncMarker.isCommitId("upstream"),
            "a branch name means whatever it points at when it is read");
        assertFalse(LastSyncMarker.isCommitId("origin/main"));
        assertFalse(LastSyncMarker.isCommitId("v1.2.0"), "a tag is not a fixed point either");
        assertFalse(LastSyncMarker.isCommitId("HEAD~3"),
            "a revision expression is a question, not a record");
        assertFalse(LastSyncMarker.isCommitId(sha1.substring(0, 7)),
            "an abbreviation is where the slope starts");
        assertFalse(LastSyncMarker.isCommitId("z".repeat(40)),
            "40 characters is not the same as 40 hex characters");
    }

    @Test
    @DisplayName("turning a marker into a base refuses a name that can move")
    void requireCommitIdRefusesMovableNames() {
        LastSyncMarker marker = new LastSyncMarker("origin/main", "origin/main", "", "");

        SyncMarkerException failure = assertThrows(SyncMarkerException.class,
            () -> marker.requireCommitId(historyRoot));

        assertTrue(failure.getMessage().contains("origin/main"), failure.getMessage());
        assertTrue(failure.getMessage().contains(LastSyncMarker.FILE_NAME),
            "the message must name the file to fix: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("clean"),
            "and why believing it would be worse than failing: " + failure.getMessage());
    }

    @Test
    @DisplayName("turning a marker into a base accepts a commit id")
    void requireCommitIdAcceptsACommitId() {
        String sha1 = "eecaadfe6259845cea5d182e4f0f9b7d7f9ab471";
        LastSyncMarker marker = new LastSyncMarker(sha1, "origin/main", "", "");

        assertEquals(sha1, marker.requireCommitId(historyRoot).getName());
    }

    @Test
    @DisplayName("parsing stays permissive: it reads a file and decides nothing")
    void parsingDoesNotValidate() {
        // Deliberate, and worth pinning: this type is a record of a file's contents, and
        // one is legitimately built and compared with no repository in sight. The refusal
        // belongs where the value would acquire the power to choose a base.
        Optional<LastSyncMarker> parsed = LastSyncMarker.parse(
            "upstreamCommit = origin/main\nupstreamRef = origin/main\n");

        assertTrue(parsed.isPresent(), "a well-formed file parses");
        assertEquals("origin/main", parsed.get().upstreamCommit(),
            "and the value is carried through unchanged for requireCommitId to refuse");
    }

    @Test
    @DisplayName("tolerates keys written by a newer version")
    void toleratesUnknownKeys() {
        Optional<LastSyncMarker> parsed = LastSyncMarker.parse(
            "upstreamCommit = abc123\n"
                + "upstreamRef = origin/main\n"
                + "futureField = something\n"
                + "recordedAt = 2026-01-01T00:00:00Z\n");

        assertTrue(parsed.isPresent());
        assertEquals("abc123", parsed.get().upstreamCommit());
    }

    @Test
    @DisplayName("an unreadable marker file does not fail a merge")
    void unreadableMarkerDoesNotThrow() throws IOException {
        Path branchHistory = historyRoot.resolve("feature");
        Files.createDirectories(branchHistory);
        Files.writeString(branchHistory.resolve(LastSyncMarker.FILE_NAME),
            "upstreamCommit = \n", StandardCharsets.UTF_8);

        assertTrue(LastSyncMarker.read(branchHistory).isEmpty(),
            "a blank commit is absence, and the caller falls back to a merge base");
    }

    @Test
    @DisplayName("the marker is human-readable, because a person may edit it mid-merge")
    void rendersReadably() {
        String rendered = LastSyncMarker.of("abc123def456", "origin/main", "sync #2").render();

        assertTrue(rendered.contains("upstreamCommit = abc123def456"), rendered);
        assertTrue(rendered.contains("upstreamRef = origin/main"), rendered);
        assertTrue(rendered.contains("note = sync #2"), rendered);
        assertFalse(rendered.contains("{"), "not JSON: two fields should be obvious at a glance");
    }

    @Test
    @DisplayName("describes itself for a merge log")
    void describesItself() {
        String description = LastSyncMarker.of(
            "abc123def4567890", "origin/main", "sync #3").describe();

        assertTrue(description.contains("abc123def456"), description);
        assertTrue(description.contains("origin/main"), description);
        assertTrue(description.contains("sync #3"), description);
    }

    @Test
    @DisplayName("a marker's identity is its commit, not the moment it was written")
    void identityIsTheCommit() {
        LastSyncMarker first = LastSyncMarker.of("abc123", "origin/main");
        LastSyncMarker second = new LastSyncMarker("abc123", "origin/main",
            "1999-01-01T00:00:00Z", "written much later");

        // Recorded at different times, and neither is behind the other: what decides
        // whether a base is current is the commit, never the timestamp.
        assertEquals(first.upstreamCommit(), second.upstreamCommit());
        assertFalse(first.differsFrom(second.upstreamCommit()));
        assertFalse(second.differsFrom(first.upstreamCommit()));
    }

    @Test
    @DisplayName("a marker reports difference, not order, because order is not knowable")
    void reportsDifferenceNotOrder() {
        LastSyncMarker older = LastSyncMarker.of("aaa111", "origin/main");
        LastSyncMarker newer = LastSyncMarker.of("bbb222", "origin/main");

        assertTrue(older.differsFrom(newer.upstreamCommit()),
            "a different commit means the branch is out of date");
        assertTrue(newer.differsFrom(older.upstreamCommit()),
            "and the relation is symmetric: knowing which commit is newer needs the "
                + "graph, which this marker deliberately does not carry");
        assertFalse(older.differsFrom(older.upstreamCommit()),
            "the same commit is not a difference");
    }
}
