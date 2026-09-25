package hr.hrg.webview.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write contract, against real files on disk.
 *
 * <p>These are the plan's Phase 3 gate items that do not need an editor: a stale digest is refused with no
 * bytes changed, an applied edit is undone byte-for-byte, and a path outside the project is refused. The
 * remaining two — a change appearing in JetBrains' own undo stack, and Zed's — belong to the hosts and to the
 * maintainer's eyes, and are recorded as observed only when they have been.
 */
class EditServiceTest {

    @TempDir
    Path project;

    private Path write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent() == null ? project : file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private EditService service() {
        return new EditService(project.toString());
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    void aStaleDigestIsRefusedAndNoByteChanges() throws IOException {
        Path file = write("src/A.java", "class A {\n    int x = 1;\n}\n");
        byte[] before = Files.readAllBytes(file);
        EditService service = service();
        String digestOfWhatThePageRead = SourceDigest.of(before);

        // Somebody else edits the file between the read and the write.
        Files.write(file, "class A {\n    int x = 2;\n}\n".getBytes(StandardCharsets.UTF_8));
        byte[] afterSomeoneElse = Files.readAllBytes(file);

        EditService.Outcome outcome = service.apply(new EditRequest("src/A.java", digestOfWhatThePageRead,
                List.of(TextEdit.lines(2, 2, "    int x = 3;")), false));

        assertEquals(EditService.Reason.STALE, outcome.reason());
        assertFalse(outcome.applied());
        assertArrayEquals(afterSomeoneElse, Files.readAllBytes(file),
                "a refusal must not touch the file - that is the whole safety argument");
        assertEquals(SourceDigest.of(afterSomeoneElse), outcome.digest(),
                "the refusal carries the current digest so the page can re-read and propose again");
    }

    @Test
    void anAppliedEditIsUndoneByteForByte() throws IOException {
        Path file = write("src/A.java", "class A {\n    int x = 1;\n}\n");
        byte[] original = Files.readAllBytes(file);
        EditService service = service();

        EditService.Outcome applied = service.apply(new EditRequest("src/A.java", SourceDigest.of(original),
                List.of(TextEdit.lines(2, 2, "    int x = 42;")), false));

        assertEquals(EditService.Reason.OK, applied.reason());
        assertTrue(applied.wrote());
        assertEquals("class A {\n    int x = 42;\n}\n", read(file),
                "a whole-line replacement keeps the line structure: the terminator is not consumed");
        assertNotEquals(SourceDigest.of(original), applied.digest());
        assertEquals(SourceDigest.of(Files.readAllBytes(file)), applied.digest(),
                "the digest returned is the one the file now has");

        EditService.Outcome undone = service.undo("src/A.java");

        assertEquals(EditService.Reason.OK, undone.reason());
        assertArrayEquals(original, Files.readAllBytes(file), "undo restores the exact bytes, not an equivalent");
        assertEquals(SourceDigest.of(original), undone.digest());
    }

    @Test
    void redoReturnsToWhatTheUndoReplaced() throws IOException {
        Path file = write("src/A.java", "one\ntwo\nthree\n");
        EditService service = service();
        service.apply(new EditRequest("src/A.java", SourceDigest.of(Files.readAllBytes(file)),
                List.of(TextEdit.lines(2, 2, "TWO")), false));
        byte[] edited = Files.readAllBytes(file);

        service.undo("src/A.java");
        assertEquals("one\ntwo\nthree\n", read(file));

        EditService.Outcome redone = service.redo("src/A.java");

        assertEquals(EditService.Reason.OK, redone.reason());
        assertArrayEquals(edited, Files.readAllBytes(file));
    }

    @Test
    void aNewWriteInvalidatesAPendingRedoBecauseThatWouldBeAMerge() throws IOException {
        Path file = write("src/A.java", "one\ntwo\n");
        EditService service = service();
        service.apply(new EditRequest("src/A.java", SourceDigest.of(Files.readAllBytes(file)),
                List.of(TextEdit.lines(2, 2, "TWO")), false));
        service.undo("src/A.java");

        service.apply(new EditRequest("src/A.java", SourceDigest.of(Files.readAllBytes(file)),
                List.of(TextEdit.lines(2, 2, "second")), false));

        EditService.Outcome redone = service.redo("src/A.java");
        assertEquals(EditService.Reason.NOTHING_TO_REDO, redone.reason());
        assertEquals("one\nsecond\n", read(file));
    }

    @Test
    void aProposalChangesNothingAndStillShowsTheDiff() throws IOException {
        Path file = write("src/A.java", "class A {\n    int x = 1;\n}\n");
        byte[] before = Files.readAllBytes(file);

        EditService.Outcome outcome = service().propose(EditRequest.proposal("src/A.java",
                SourceDigest.of(before), List.of(TextEdit.lines(2, 2, "    int x = 7;"))));

        assertEquals(EditService.Reason.OK, outcome.reason());
        assertFalse(outcome.applied());
        assertArrayEquals(before, Files.readAllBytes(file), "a proposal is a promise, not a write");
        assertTrue(outcome.unifiedDiff().contains("-    int x = 1;"), outcome.unifiedDiff());
        assertTrue(outcome.unifiedDiff().contains("+    int x = 7;"), outcome.unifiedDiff());
        assertNotEquals(SourceDigest.of(before), outcome.digest(),
                "the proposal reports the digest the file would have, so a page can chain or accept");
    }

    @Test
    void aPathOutsideTheProjectIsRefusedAndNothingIsWritten() throws IOException {
        Path outside = Files.createTempFile("webview-outside", ".txt");
        Files.write(outside, "not yours\n".getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(outside);

        EditService.Outcome outcome = service().apply(new EditRequest(outside.toString(),
                SourceDigest.of(before), List.of(TextEdit.lines(1, 1, "mine")), false));

        assertEquals(EditService.Reason.OUTSIDE_PROJECT, outcome.reason());
        assertArrayEquals(before, Files.readAllBytes(outside));
        Files.deleteIfExists(outside);
    }

    @Test
    void aRelativePathThatClimbsOutIsRefusedToo() throws IOException {
        EditService.Outcome outcome = service().apply(new EditRequest("../../etc/passwd", "sha256:" + "0".repeat(64),
                List.of(TextEdit.lines(1, 1, "x")), false));

        assertTrue(outcome.reason() == EditService.Reason.OUTSIDE_PROJECT
                        || outcome.reason() == EditService.Reason.NOT_FOUND,
                "either the jail refused it or nothing is there, but never a write: " + outcome.reason());
    }

    @Test
    void malformedAndMissingPositionsAreInvalidEditsRatherThanWrites() throws IOException {
        Path file = write("A.java", "one\ntwo\n");
        EditService service = service();
        String digest = SourceDigest.of(Files.readAllBytes(file));

        assertEquals(EditService.Reason.INVALID_EDIT,
                service.apply(new EditRequest("A.java", "not-a-digest", List.of(TextEdit.lines(1, 1, "x")), false))
                        .reason());
        assertEquals(EditService.Reason.INVALID_EDIT,
                service.apply(new EditRequest("A.java", digest, List.of(TextEdit.lines(9, 9, "x")), false)).reason());
        assertEquals(EditService.Reason.INVALID_EDIT,
                service.apply(new EditRequest("A.java", digest, List.of(), false)).reason());
        assertEquals("one\ntwo\n", read(file));
    }

    @Test
    void lineEndingsAndTheTrailingNewlineSurviveAnEdit() throws IOException {
        Path crlf = write("crlf.txt", "one\r\ntwo\r\nthree\r\n");
        Path unlucky = write("no-newline.txt", "one\ntwo");

        EditService service = service();
        service.apply(new EditRequest("crlf.txt", SourceDigest.of(Files.readAllBytes(crlf)),
                List.of(TextEdit.lines(2, 2, "TWO")), false));
        service.apply(new EditRequest("no-newline.txt", SourceDigest.of(Files.readAllBytes(unlucky)),
                List.of(TextEdit.lines(1, 1, "ONE")), false));

        assertEquals("one\r\nTWO\r\nthree\r\n", read(crlf), "a CRLF file stays a CRLF file");
        assertEquals("ONE\ntwo", read(unlucky), "and a file that ended without a newline still does");
    }

    @Test
    void anEditThatChangesNothingIsReportedRatherThanWritten() throws IOException {
        Path file = write("A.java", "one\ntwo\n");
        byte[] before = Files.readAllBytes(file);

        EditService.Outcome outcome = service().apply(new EditRequest("A.java", SourceDigest.of(before),
                List.of(TextEdit.lines(2, 2, "two")), false));

        assertEquals(EditService.Reason.NO_CHANGE, outcome.reason());
        assertFalse(outcome.wrote());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(outcome.succeeded(), "no change is not a failure; the caller asked for a state already true");
    }

    @Test
    void anInsertionAndAMultiRangeEditBothApplyInOneWrite() throws IOException {
        Path file = write("A.java", "one\ntwo\nthree\nfour\n");

        EditService.Outcome outcome = service().apply(new EditRequest("A.java",
                SourceDigest.of(Files.readAllBytes(file)),
                List.of(TextEdit.insert(1, 1, "zero\n"), TextEdit.delete(4, 1, 4, 5)), false));

        assertEquals(EditService.Reason.OK, outcome.reason());
        assertEquals("zero\none\ntwo\nthree\n\n", read(file),
                "edits are addressed against the original text and applied together");
    }

    @Test
    void overlappingEditsAreRefusedInsteadOfAppliedInSomeOrder() throws IOException {
        Path file = write("A.java", "one\ntwo\n");

        EditService.Outcome outcome = service().apply(new EditRequest("A.java",
                SourceDigest.of(Files.readAllBytes(file)),
                List.of(TextEdit.lines(1, 2, "x"), TextEdit.lines(2, 2, "y")), false));

        assertEquals(EditService.Reason.INVALID_EDIT, outcome.reason());
        assertTrue(outcome.detail().contains("overlap"), outcome.detail());
        assertEquals("one\ntwo\n", read(file));
    }

    @Test
    void anUndoRefusesToDiscardSomebodyElsesChange() throws IOException {
        Path file = write("src/A.java", "one\ntwo\n");
        EditService service = service();
        service.apply(new EditRequest("src/A.java", SourceDigest.of(Files.readAllBytes(file)),
                List.of(TextEdit.lines(2, 2, "TWO")), false));

        // The reader edits the file in their own editor after our write. Undoing now would restore bytes from
        // before both changes and throw their edit away - the one thing this contract never does.
        Files.writeString(file, "one\nTWO\n// the reader's own line\n", StandardCharsets.UTF_8);
        byte[] byTheReader = Files.readAllBytes(file);

        EditService.Outcome outcome = service.undo("src/A.java");

        assertEquals(EditService.Reason.STALE, outcome.reason());
        assertFalse(outcome.applied());
        assertTrue(outcome.detail().contains("would discard"), outcome.detail());
        assertArrayEquals(byTheReader, Files.readAllBytes(file));
        assertTrue(service.checkpoints().undoDepth(file.toAbsolutePath().normalize()) == 1,
                "the checkpoint is kept, so the caller may undo once it has decided what to do");
    }

    @Test
    void undoWithoutAHistorySaysSo() throws IOException {
        write("A.java", "one\n");

        EditService.Outcome outcome = service().undo("A.java");

        assertEquals(EditService.Reason.NOTHING_TO_UNDO, outcome.reason());
        assertFalse(outcome.succeeded());
    }

    @Test
    void theCheckpointHistoryIsBoundedPerFile() throws IOException {
        Path file = write("A.java", "0\n");
        CheckpointStore store = new CheckpointStore(3);
        EditService service = new EditService(project.toString(), store,
                new RateLimiter(100, Navigator.RATE_LIMIT_WINDOW_MS, Clock.SYSTEM));

        for (int i = 1; i <= 5; i++) {
            service.apply(new EditRequest("A.java", SourceDigest.of(Files.readAllBytes(file)),
                    List.of(TextEdit.lines(1, 1, String.valueOf(i))), false));
        }

        assertEquals(3, store.undoDepth(file.toAbsolutePath().normalize()),
                "an unbounded history in a long-running host is a leak");
    }

    @Test
    void writesAreRateLimitedByTheSamePolicyAsNavigation() throws IOException {
        Path file = write("A.java", "one\n");
        EditService service = new EditService(project.toString(), new CheckpointStore(),
                new RateLimiter(2, Navigator.RATE_LIMIT_WINDOW_MS, Clock.SYSTEM));

        assertTrue(service.propose(EditRequest.proposal("A.java", SourceDigest.of(Files.readAllBytes(file)),
                List.of(TextEdit.lines(1, 1, "x")))).succeeded());
        assertTrue(service.propose(EditRequest.proposal("A.java", SourceDigest.of(Files.readAllBytes(file)),
                List.of(TextEdit.lines(1, 1, "y")))).succeeded());

        EditService.Outcome third = service.propose(EditRequest.proposal("A.java",
                SourceDigest.of(Files.readAllBytes(file)), List.of(TextEdit.lines(1, 1, "z"))));
        assertEquals(EditService.Reason.RATE_LIMITED, third.reason());
    }
}
