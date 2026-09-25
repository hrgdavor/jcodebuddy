package hr.hrg.webview.webviewd;

import hr.hrg.webview.core.EditorHost;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Zed adapter: {@code Zed.exe <absolute path>} opens the file in a running editor.
 *
 * <p><b>It deliberately cannot place a caret, and says so.</b> Phase 0 measured what the plan assumed: the
 * documented {@code zed <file>:<line>:<column>} form fails on Windows in 1.21.0 — Zed logs
 * {@code error parsing path argument … (os error 123)} for the absolute, relative, line-only and
 * line+column spellings, while a plain path parses (see {@code webview/PHASE0-ZED-FINDINGS.md} § B). So
 * this host reports {@code open} and nothing else, and {@link #lineNavigation()} answers
 * {@code "file-only"} so a page can tell the difference between "the caret is there" and "the file is
 * open" instead of being lied to. The caret path on Zed is the LSP channel, not this adapter.
 *
 * <p>{@link CommandRunner} is injectable so its tests never start an editor.
 */
public final class ZedCliHost implements EditorHost {

    /** The name a page sees in {@code /health}'s host block and in the manifest. */
    public static final String NAME = "zed-cli";

    /** Runs one command, returning true when the process could be started. */
    public interface CommandRunner {
        boolean run(List<String> command);
    }

    private final String binary;
    private final CommandRunner runner;

    private ZedCliHost(String binary, CommandRunner runner) {
        this.binary = binary;
        this.runner = runner;
    }

    /** An adapter over an already-resolved binary, for tests and for a caller that knows the path. */
    public static ZedCliHost using(String binary, CommandRunner runner) {
        return new ZedCliHost(binary, runner);
    }

    /** The adapter for this machine, or an unavailable one when the CLI is not on the PATH. */
    public static ZedCliHost detect() {
        return new ZedCliHost(findOnPath(), ZedCliHost::start);
    }

    /**
     * Looks for the Zed CLI under both platform spellings. The GUI binary is what the installed handler
     * runs, so it is accepted too: it takes the same arguments and does not need the wrapper.
     */
    public static String findOnPath() {
        return findOnPath(System.getenv("PATH"), System.getProperty("os.name", ""));
    }

    static String findOnPath(String pathValue, String osName) {
        if (pathValue == null || pathValue.isBlank()) {
            return null;
        }
        boolean windows = osName.toLowerCase(Locale.ROOT).contains("win");
        List<String> names = windows ? List.of("zed.exe", "zed.cmd", "zed.bat") : List.of("zed");
        for (String entry : pathValue.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            for (String name : names) {
                Path candidate = Path.of(entry, name);
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    private static boolean start(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return binary != null;
    }

    @Override
    public Set<String> capabilities() {
        return isAvailable() ? Set.of(EditorHost.CAP_OPEN) : Set.of();
    }

    /** The binary this adapter would run, or null when none was found. */
    public String binary() {
        return binary;
    }

    /**
     * Whether a line and column actually reach the editor. It is {@code false} on every platform this
     * adapter has been measured on, which is the whole point of the field.
     */
    public boolean appliesPosition() {
        return false;
    }

    /**
     * The manifest's spelling of {@link #appliesPosition()}. Overrides {@link EditorHost#lineNavigation()}
     * because this adapter is available *and* imprecise: the default would call it {@code exact}.
     */
    @Override
    public String lineNavigation() {
        return isAvailable() ? (appliesPosition() ? "exact" : "file-only") : "none";
    }

    @Override
    public String lineNavigationNote() {
        return isAvailable()
                ? "the Zed CLI opens the file but cannot place a caret: Zed 1.21.0 on Windows refuses the "
                        + "documented path:line:column form (webview/PHASE0-ZED-FINDINGS.md, section B), so "
                        + "the caret path on Zed is the LSP channel"
                : "no Zed CLI was found on the PATH, so navigation is refused";
    }

    @Override
    public boolean openFileAt(String absolutePath, int line, int column) {
        if (!isAvailable()) {
            return false;
        }
        // No "path:line:column" suffix: Phase 0 measured that Zed 1.21.0 on Windows refuses to parse it, and
        // a refused argument would open nothing at all rather than opening the file without the caret.
        List<String> command = new ArrayList<>(2);
        command.add(binary);
        command.add(absolutePath);
        return runner.run(List.copyOf(command));
    }
}
