package hr.hrg.webview.webviewd;

import hr.hrg.webview.core.EditorHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Zed adapter, and the one thing about it that Phase 0 changed: it opens a file and cannot place a caret,
 * and it must not pretend otherwise by sending an argument Zed refuses to parse.
 */
class ZedCliHostTest {

    /** Records commands instead of starting an editor. */
    private static final class RecordingRunner implements ZedCliHost.CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        boolean answer = true;

        @Override
        public boolean run(List<String> command) {
            commands.add(command);
            return answer;
        }
    }

    @Test
    void openSendsThePathAndNothingElseBecauseAPositionSuffixIsRejected(@TempDir Path project) {
        RecordingRunner runner = new RecordingRunner();
        ZedCliHost host = ZedCliHost.using("C:/zed/Zed.exe", runner);

        assertTrue(host.openFileAt("D:/wrk/project/src/A.java", 12, 3));
        assertEquals(List.of(List.of("C:/zed/Zed.exe", "D:/wrk/project/src/A.java")), runner.commands,
                "no ':12:3' suffix: Zed 1.21.0 on Windows answers os error 123 for that form");
        assertFalse(host.appliesPosition());
        assertEquals("file-only", host.lineNavigation());
    }

    @Test
    void capabilitiesAdvertiseOpenOnlyAndAnAbsentCliAdvertisesNothing() {
        ZedCliHost present = ZedCliHost.using("C:/zed/Zed.exe", new RecordingRunner());
        assertTrue(present.isAvailable());
        assertEquals(Set.of(EditorHost.CAP_OPEN), present.capabilities());
        assertFalse(present.capabilities().contains(EditorHost.CAP_SELECT));

        ZedCliHost absent = ZedCliHost.using(null, new RecordingRunner());
        assertFalse(absent.isAvailable());
        assertTrue(absent.capabilities().isEmpty(), "a missing CLI must not advertise a verb it cannot run");
        assertFalse(absent.openFileAt("D:/wrk/A.java", 1, 1));
    }

    @Test
    void aRefusedProcessIsReportedAsNotOpened() {
        RecordingRunner runner = new RecordingRunner();
        runner.answer = false;
        assertFalse(ZedCliHost.using("C:/zed/Zed.exe", runner).openFileAt("D:/wrk/A.java", 1, 1));
    }

    @Test
    void findsTheCliByNameOnThePathAndIgnoresEverythingElse(@TempDir Path bin) throws IOException {
        Files.writeString(bin.resolve("zed.exe"), "stub");
        Files.writeString(bin.resolve("other.exe"), "stub");
        Files.createDirectories(bin.resolve("sub"));

        assertEquals(bin.resolve("zed.exe").toString(),
                ZedCliHost.findOnPath(bin.toString() + java.io.File.pathSeparator + bin.resolve("sub"), "Windows 11"));
        assertNull(ZedCliHost.findOnPath(bin.resolve("sub").toString(), "Windows 11"),
                "a directory named zed.exe is not an executable");
        assertNull(ZedCliHost.findOnPath("", "Windows 11"));
        assertNull(ZedCliHost.findOnPath(null, "Linux"));
    }
}
