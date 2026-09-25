package hr.hrg.webview.webviewd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

/**
 * The per-project record of a running host: {@code .jcodebuddy/webview/host.json}.
 *
 * <p>Two jobs, and the second is the reason it is a class rather than a settings blob:
 *
 * <ol>
 *   <li>a page has to be able to find the port without the user copying it out of a terminal — which is
 *       Phase 2's D7 ("an ephemeral port by default, published in a per-workspace descriptor");</li>
 *   <li>a second {@code webviewd} for the same project must refuse to start rather than race for the same
 *       descriptor, the same token and the same port. {@link #isLive()} is that check: it asks the operating
 *       system whether the recorded process still exists, because "the file is there" is not the same as
 *       "a host is there" — the process may have been killed without running its shutdown hook.</li>
 * </ol>
 *
 * <p>The token itself is never in this file: it lives beside it in {@code token}, and the descriptor only
 * names that path, so a descriptor can be shared, logged or printed without leaking the secret.
 */
public record HostDescriptor(
        String plugin,
        long pid,
        int port,
        String project,
        @SerializedName("tokenPath") String tokenPath,
        List<String> capabilities,
        String startedAt,
        HostDetail host) {

    /** The file name under {@code .jcodebuddy/webview/}. */
    public static final String FILE_NAME = "host.json";
    /** The directory, relative to the project, that holds the descriptor and the token. */
    public static final String STATE_DIR = ".jcodebuddy/webview";

    /** What the attached editor adapter is, and what it can honestly do about a line number. */
    public record HostDetail(String name, boolean available, String lineNavigation, String note) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path directoryOf(Path project) {
        return project.resolve(STATE_DIR);
    }

    public static Path fileOf(Path project) {
        return directoryOf(project).resolve(FILE_NAME);
    }

    /**
     * The descriptor a previous run left behind, or null when there is none or it cannot be read. Unreadable
     * counts as absent on purpose: a truncated descriptor (the process was killed mid-write) must not make
     * the next start impossible.
     */
    public static HostDescriptor read(Path project) {
        Path file = fileOf(project);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            HostDescriptor descriptor = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    HostDescriptor.class);
            return descriptor != null && descriptor.port() > 0 ? descriptor : null;
        } catch (IOException | JsonSyntaxException e) {
            return null;
        }
    }

    /** True when the process this descriptor names is still running. */
    public boolean isLive() {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    public Path tokenPathAsPath(Path project) {
        return tokenPath == null ? directoryOf(project).resolve("token") : Path.of(tokenPath);
    }

    /** Writes the descriptor, creating {@code .jcodebuddy/webview/} when it is missing. */
    public HostDescriptor write(Path project) throws IOException {
        Path directory = directoryOf(project);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(FILE_NAME), toJson() + System.lineSeparator(), StandardCharsets.UTF_8);
        return this;
    }

    /** Deletes the descriptor, and reports whether anything was there. */
    public static boolean delete(Path project) throws IOException {
        return Files.deleteIfExists(fileOf(project));
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static HostDescriptor of(Path project, int port, String tokenPath, List<String> capabilities,
                                    HostDetail host) {
        return new HostDescriptor("hr.hrg.webview.webviewd", ProcessHandle.current().pid(), port,
                project.toString().replace('\\', '/'), tokenPath.replace('\\', '/'),
                List.copyOf(capabilities), Instant.now().toString(), host);
    }

    /**
     * The sentence a refusal prints, so two starts of the same project say the same thing whether the user
     * runs them by hand or a script does.
     */
    public String conflictMessage() {
        return "webviewd is already running for this project: pid " + pid + " on port " + port
                + " (descriptor: " + project + "/" + STATE_DIR + "/" + FILE_NAME + "). "
                + "Stop that process, or pass a different --project.";
    }

    /** True when a start must be refused because another live host already owns this project. */
    public static boolean blocksStart(HostDescriptor existing) {
        return existing != null && existing.isLive();
    }

    static String normalizeLineNavigation(String value) {
        return value == null ? "none" : value.trim().toLowerCase(Locale.ROOT);
    }
}
