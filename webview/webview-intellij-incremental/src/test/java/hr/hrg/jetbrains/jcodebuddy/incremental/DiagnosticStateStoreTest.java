package hr.hrg.jetbrains.jcodebuddy.incremental;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagnosticStateStoreTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void roundTripsPathsLinesAndMessagesWithTabs() throws Exception {
        Path project = temporary.getRoot().toPath();
        DiagnosticStateStore store = new DiagnosticStateStore(project);
        Diagnostic first = new Diagnostic(
                project.resolve("src/A.java").toAbsolutePath(), 12, 4, "message with\ttab and\nnewline");
        Diagnostic second = new Diagnostic(
                project.resolve("src/B.java").toAbsolutePath(), 7, 0, "second");

        store.save(List.of(first, second));

        List<Diagnostic> loaded = store.load();
        assertEquals(List.of(first, second), loaded);
        assertTrue(Files.isRegularFile(store.file()));
    }

    @Test
    public void clearRemovesThePersistedSet() throws Exception {
        Path project = temporary.getRoot().toPath();
        DiagnosticStateStore store = new DiagnosticStateStore(project);
        store.save(List.of(new Diagnostic(project.resolve("A.java"), 1, 0, "error")));
        store.clear();
        assertFalse(Files.exists(store.file()));
        assertTrue(store.load().isEmpty());
    }
}
