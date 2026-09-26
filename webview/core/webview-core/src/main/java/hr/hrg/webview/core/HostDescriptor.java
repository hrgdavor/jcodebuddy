package hr.hrg.webview.core;

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
 * The project's port record: {@code <project>/.jcodebuddy/webview/host.json}.
 *
 * <p>It answers two questions, and the second is why it is a class rather than a settings blob:
 *
 * <ol>
 *   <li>a page — or a script, or a second editor — has to be able to find the port without the user copying
 *       it out of a terminal, which is Phase 2's D7 ("an ephemeral port by default, published in a
 *       per-workspace descriptor"). The port recorded is the one this <b>checkout</b> is currently on: it is
 *       what a later start asks for, so a checkout that had to take the next free port keeps it instead of
 *       drifting back and colliding again ({@link HostConfig#requestedPort}).</li>
 *   <li>a second host for the same project must be able to see that one is already there, instead of racing
 *       it for the same port. {@link #isLive()} is half of that check — it asks the operating system whether
 *       the recorded process still exists — and {@link HostPortClaim} is the other half: a live process is
 *       only authoritative for <em>its own</em> port, so the port probe, not this file, decides who owns a
 *       socket.</li>
 * </ol>
 *
 * <p><b>This record outlives the host that wrote it.</b> A host that stops — cleanly or otherwise — leaves it
 * in place, because it is the project's port, not the process's: deleting it on shutdown would make an
 * ephemeral port change on every restart and would silently throw away a {@code sticky} pin the user set. So
 * "is a host there?" is never answered by this file; it is answered by asking the port
 * ({@link HostPortClaim#occupantOf}). The file is only removed when a user deletes it (or when a project is
 * reset), and {@link #delete} exists for that.
 *
 * <p>The token itself is never in this file: it lives beside it in {@code token}, and the descriptor only
 * names that path, so a descriptor can be shared, logged or printed without leaking the secret.
 *
 * <p><b>{@code sticky} is a local property of this port.</b> When it is true the port is <em>pinned</em> for
 * this project: a host must serve on it or not at all, and a taken sticky port is an error rather than a
 * reason to take the next free one (see {@link HostPortClaim}). It is deliberately <b>not</b> configuration
 * and deliberately not in {@code .jcodebuddy/conf/}: it describes the port one worktree is currently using,
 * so it is per checkout and machine-local, and it is never committed. The case it exists for is a project
 * with several git worktrees — one repository, several directories, several ports — where one worktree's port
 * must stay put (a bookmark, a firewall rule, a second screen) while the others are free to move. A user sets
 * it by editing this file or with {@code webviewd --sticky}; it defaults to {@code false}, and an older
 * descriptor without the field reads as {@code false}.
 *
 * <p>This record lives in {@code webview-core} rather than in one host because every host publishes it: the
 * standalone {@code webviewd} writes it for an ephemeral port, and the two IDE hosts write it so that a
 * page — or a second IDE — can find a port the user never configured. The file is <b>per project</b>
 * (DEC-026's per-module {@code .jcodebuddy/} rule, applied to the project root the host serves); there is
 * deliberately no user-home equivalent, because a port only means something to the project that published
 * it.
 */
public record HostDescriptor(
        String plugin,
        String ide,
        long pid,
        int port,
        boolean sticky,
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

    /** True when this record was written by the process asking, i.e. it is safe for that process to remove it. */
    public boolean isOurs() {
        return pid > 0 && pid == ProcessHandle.current().pid();
    }

    public Path tokenPathAsPath(Path project) {
        return tokenPath == null ? directoryOf(project).resolve("token") : Path.of(tokenPath);
    }

    /** Writes the descriptor, creating {@code .jcodebuddy/webview/} when it is missing. */
    public HostDescriptor write(Path project) throws IOException {
        Path directory = directoryOf(project);
        Files.createDirectories(directory);
        ensureIgnored(directory);
        Files.writeString(directory.resolve(FILE_NAME), toJson() + System.lineSeparator(), StandardCharsets.UTF_8);
        return this;
    }

    /**
     * Makes sure the published state cannot appear in {@code git status}.
     *
     * <p>The state directory is derived and machine-local — a port, a token, a page's undo journal — so it
     * must never be committed, and it must not be left to each project to notice: a host is pointed at
     * arbitrary directories, most of which have no JCodeBuddy ignore rules at all, and a project that
     * suddenly grows untracked files because a browser was pointed at it is a host that will be turned off.
     *
     * <p>The mechanism is the ordinary one: a {@code .gitignore} inside the state directory containing
     * {@code *}, which ignores the directory's whole content including itself. It is written only when it is
     * absent, so a project that wants a narrower rule of its own can write one. A project that is a
     * converted module has DEC-026's {@code .jcodebuddy/.gitignore} as the authority for the tracked/ignored
     * split; this file is what covers a project that has no such policy.
     */
    private static void ensureIgnored(Path directory) {
        Path ignore = directory.resolve(".gitignore");
        if (Files.exists(ignore)) {
            return;
        }
        try {
            Files.writeString(ignore, """
                    # A running page host's state: the port it bound, the token a caller must present, and a
                    # page's undo journal. Derived and machine-local, so nothing in this directory is ever
                    # committed (DEC-032). Delete the directory, not a line here, if you want a clean start.
                    *
                    """, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Not fatal: a read-only project still serves pages, and the state is written either way.
        }
    }

    /**
     * Deletes the descriptor, and reports whether anything was there.
     *
     * <p>Nothing calls this automatically, and that is deliberate: the file is the project's record of the
     * port this checkout is on, and a host that stopped cleanly must not erase it — that record is what the
     * next start asks for (and what a {@code sticky} pin is stored in). A user who wants to forget the port
     * deletes the file, or runs a host with {@code --no-sticky} to clear just the pin.
     */
    public static boolean delete(Path project) throws IOException {
        return Files.deleteIfExists(fileOf(project));
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * The descriptor for this process.
     *
     * @param ide the human name of the editor, matching what {@code /health} answers with, so a reader that
     *            found this file can say "IntelliJ IDEA is serving this project on port 18881" without
     *            probing the port
     * @param sticky whether this port is pinned for this project, and therefore must not be moved
     */
    public static HostDescriptor of(Path project, String plugin, String ide, int port, boolean sticky,
                                    String tokenPath, List<String> capabilities, HostDetail host) {
        return new HostDescriptor(plugin, ide, ProcessHandle.current().pid(), port, sticky,
                project.toString().replace('\\', '/'), tokenPath.replace('\\', '/'),
                List.copyOf(capabilities), Instant.now().toString(), host);
    }

    /** The same, for a host that has not been asked to pin anything: {@code sticky} is false. */
    public static HostDescriptor of(Path project, String plugin, String ide, int port, String tokenPath,
                                    List<String> capabilities, HostDetail host) {
        return of(project, plugin, ide, port, false, tokenPath, capabilities, host);
    }

    /**
     * The same record with the pin flipped, and everything else — including the {@code pid} and the
     * {@code startedAt} instant of the host that published it — left alone.
     *
     * <p>This is how a command changes a decision that belongs to a port rather than to a process:
     * {@code webviewd --sticky} on a project that is already being served must be able to pin the port
     * without pretending to be the host that holds it.
     */
    public HostDescriptor withSticky(boolean pinned) {
        return new HostDescriptor(plugin, ide, pid, port, pinned, project, tokenPath, capabilities, startedAt,
                host);
    }

    /**
     * The same, for the host whose plugin id is this product's own name.
     *
     * @deprecated a host that serves a project should pass its own plugin id and IDE name; kept because the
     *             standalone host and its tests were written against it.
     */
    @Deprecated
    public static HostDescriptor of(Path project, int port, String tokenPath, List<String> capabilities,
                                    HostDetail host) {
        return of(project, HostHealth.PLUGIN_WEBVIEWD, HostHealth.IDE_WEBVIEWD, port, false, tokenPath,
                capabilities, host);
    }

    /**
     * The sentence a refusal prints, so two starts of the same project say the same thing whether the user
     * runs them by hand or a script does.
     */
    public String conflictMessage() {
        return "a webview host is already running for this project: " + description()
                + " (descriptor: " + project + "/" + STATE_DIR + "/" + FILE_NAME + "). "
                + "Stop that process, or pass a different --project.";
    }

    /** One line naming what this descriptor records, for a log or a conflict message. */
    public String description() {
        String ideName = ide == null || ide.isBlank() ? plugin : ide;
        return ideName + " (pid " + pid + ") on port " + port + (sticky ? ", sticky" : "");
    }

    /** True when a start must be refused because another live host already owns this project. */
    public static boolean blocksStart(HostDescriptor existing) {
        return existing != null && existing.isLive();
    }

    static String normalizeLineNavigation(String value) {
        return value == null ? "none" : value.trim().toLowerCase(Locale.ROOT);
    }
}
