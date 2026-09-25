package hr.hrg.webview.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The undo history across a restart — which is the one thing the in-memory store could not do, and the reason a
 * reader could be told "nothing to undo" about a change they can see in their editor.
 *
 * <p>Tested with a *second* store over the same directory rather than by restarting a process: that is the same
 * code path (a store that finds existing states) without a subprocess in a unit test.
 */
class CheckpointStoreTest {

    @TempDir
    Path project;

    @TempDir
    Path journal;

    private Path file() throws IOException {
        Path file = project.resolve("src/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "one\ntwo\n", StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void aSecondStoreOverTheSameDirectoryFindsTheHistoryAndUndoesIt() throws IOException {
        Path file = file();
        CheckpointStore first = CheckpointStore.persistent(journal, CheckpointStore.DEFAULT_LIMIT);
        first.recordBefore(file, "one\ntwo\n".getBytes(StandardCharsets.UTF_8),
                SourceDigest.ofText("one\nTWO\n"));
        Files.writeString(file, "one\nTWO\n", StandardCharsets.UTF_8);

        // The host restarted: a new store, the same directory.
        CheckpointStore second = CheckpointStore.persistent(journal, CheckpointStore.DEFAULT_LIMIT);

        assertEquals(1, second.undoDepth(file), "the history was found on disk");
        assertEquals(SourceDigest.ofText("one\nTWO\n"), second.pendingUndoDigest(file),
                "and so was the digest the file must still have for the undo to be safe");
        byte[] restored = second.undo(file, Files.readAllBytes(file));
        assertNotNull(restored);
        assertArrayEquals("one\ntwo\n".getBytes(StandardCharsets.UTF_8), restored);
        Files.write(file, restored);
        assertArrayEquals("one\ntwo\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
    }

    @Test
    void aRedoSurvivesTheRestartTooAndThePairStaysConsistent() throws IOException {
        Path file = file();
        CheckpointStore first = CheckpointStore.persistent(journal, CheckpointStore.DEFAULT_LIMIT);
        first.recordBefore(file, "one\ntwo\n".getBytes(StandardCharsets.UTF_8), SourceDigest.ofText("one\nTWO\n"));
        Files.writeString(file, "one\nTWO\n", StandardCharsets.UTF_8);
        first.undo(file, "one\nTWO\n".getBytes(StandardCharsets.UTF_8));
        Files.writeString(file, "one\ntwo\n", StandardCharsets.UTF_8);

        CheckpointStore second = CheckpointStore.persistent(journal, CheckpointStore.DEFAULT_LIMIT);

        assertEquals(1, second.redoDepth(file), "the redo survived as well");
        byte[] redone = second.redo(file, Files.readAllBytes(file));
        assertArrayEquals("one\nTWO\n".getBytes(StandardCharsets.UTF_8), redone);
        assertEquals(1, second.undoDepth(file), "and the redo put the previous state back on the undo stack");
    }

    @Test
    void aNewWriteStillInvalidatesAPendingRedoOnDisk() throws IOException {
        Path file = file();
        CheckpointStore first = CheckpointStore.persistent(journal, CheckpointStore.DEFAULT_LIMIT);
        first.recordBefore(file, "one\ntwo\n".getBytes(StandardCharsets.UTF_8), SourceDigest.ofText("one\nTWO\n"));
        first.undo(file, "one\nTWO\n".getBytes(StandardCharsets.UTF_8));

        // Somebody writes again: a redo of the old change would now be a merge, so the journal must drop it.
        first.recordBefore(file, "one\ntwo\n".getBytes(StandardCharsets.UTF_8), SourceDigest.ofText("one\nsecond\n"));

        CheckpointStore second = CheckpointStore.persistent(journal, CheckpointStore.DEFAULT_LIMIT);
        assertEquals(0, second.redoDepth(file), "the pending redo was invalidated in the journal, not just in memory");
    }

    @Test
    void theBoundAppliesToTheJournalToo() throws IOException {
        Path file = file();
        CheckpointStore store = CheckpointStore.persistent(journal, 3);
        for (int i = 1; i <= 5; i++) {
            store.recordBefore(file, ("state " + i + "\n").getBytes(StandardCharsets.UTF_8),
                    SourceDigest.ofText("state " + (i + 1) + "\n"));
        }

        CheckpointStore afterRestart = CheckpointStore.persistent(journal, 3);

        assertEquals(3, afterRestart.undoDepth(file), "a journal that grew without bound would be a disk leak");
    }

    @Test
    void aFileThisStoreHasNeverSeenHasNothingToUndo() throws IOException {
        Path file = file();
        CheckpointStore store = CheckpointStore.persistent(journal, CheckpointStore.DEFAULT_LIMIT);

        assertEquals(0, store.undoDepth(file));
        assertNull(store.pendingUndoDigest(file));
        assertNull(store.undo(file, Files.readAllBytes(file)));
    }

    @Test
    void theJournalIsPerFileSoOneBusyFileCannotEvictAnother() throws IOException {
        Path one = file();
        Path two = project.resolve("src/B.java");
        Files.writeString(two, "b\n", StandardCharsets.UTF_8);
        CheckpointStore store = CheckpointStore.persistent(journal, 1);
        store.recordBefore(one, "a1\n".getBytes(StandardCharsets.UTF_8), "sha256:1");
        store.recordBefore(two, "b1\n".getBytes(StandardCharsets.UTF_8), "sha256:2");
        store.recordBefore(one, "a2\n".getBytes(StandardCharsets.UTF_8), "sha256:3");

        CheckpointStore afterRestart = CheckpointStore.persistent(journal, 1);

        assertEquals(1, afterRestart.undoDepth(one));
        assertEquals(1, afterRestart.undoDepth(two), "the other file's single state is still there");
    }

    @Test
    void anInMemoryStoreIsStillTheDefaultAndWritesNothing() throws IOException {
        Path file = file();
        CheckpointStore store = new CheckpointStore(2);

        assertNull(store.directory(), "a caller with nowhere to write is not forced to have a journal");
        store.recordBefore(file, "x\n".getBytes(StandardCharsets.UTF_8), "sha256:x");
        assertEquals(1, store.undoDepth(file));
        assertTrue(Files.isDirectory(journal), "the temp directory exists but the store never touched it");
        try (var entries = Files.list(journal)) {
            assertEquals(0, entries.count(), "no journal, no files");
        }
    }
}
