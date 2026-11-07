package hr.hrg.hipster.entity.tooling;

import hr.hrg.fsmeta.FsMetaFormat;
import hr.hrg.jcodebuddy.engine.JcodebuddyDirectory;
import hr.hrg.jcodebuddy.watch.FsMetaClient;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The incremental state behind {@code --incremental} (plan {@code hipster-entity-mvp.md} E2), as a
 * <strong>daemon-generation marker</strong>.
 *
 * <p>The state used to be a Java-written {@code fs-meta} trio. That was always a transitional shape:
 * {@code fs-meta} names the Zig daemon as its only writer, and W7b moved every other consumer onto the
 * daemon. This class is the entity generator's half of that cutover. It keeps one small JSON document —
 * {@code <module>/.jcodebuddy/cache/entity-build/run.json} — and nothing else:</p>
 *
 * <pre>{@code
 * {
 *   "format": "jcodebuddy-entity-build/2",
 *   "generator": "...",
 *   "sourceRoot": "/project/module/src/main/java",
 *   "projectRoot": "/project",
 *   "daemon": { "id": "a1b2c3d4", "root": "/project", "generation": 12 },
 *   "validation": "clean",
 *   "selection": { "mode": "full", "changed": [], "affected": [] },
 *   "fingerprint": { "generator": "...", "jdk": "25", ... }
 * }
 * }</pre>
 *
 * <p>There is no {@code files.meta}/{@code files.txt}/{@code files.json} and no {@code FsMetaWriter} call.
 * The tree's file fingerprints live in the daemon's trio for the daemon root; this document only records
 * <em>which daemon generation the last successful pass left behind</em>. The daemon is the tracker, and a
 * second tracker is exactly what this repository does not allow.</p>
 *
 * <h3>fs-meta is a hard requirement, and the root is the project root</h3>
 * <p>{@link #decide} discovers the deepest served ancestor root of the module source root
 * ({@link FsMetaDaemonDiscovery#discoverAncestor(Path)}) and records that root. The daemon serves the whole
 * project; a module's cache is <strong>not</strong> a second root — it filters the daemon stream to its own
 * source-root subfolder with the protocol's own {@code SUBSCRIBE} include patterns
 * ({@link FsMetaDaemonState#includePatterns(Path, Path)}). Two modules of one project share one daemon, one
 * event stream and one generation sequence, and each answers only for its own subfolder.</p>
 *
 * <h3>The decision, and why a missing daemon can never be a wrong answer</h3>
 * <ul>
 *   <li><strong>No daemon</strong> — there is no baseline and no event stream, so the answer is
 *       {@link Mode#FULL} with an actionable reason. A full pass is the safe fallback, and
 *       {@code --incremental} never claims a no-op it cannot prove. After the pass nothing is recorded: a
 *       {@code run.json} written without a daemon would be a baseline that hides the next run.</li>
 *   <li><strong>No {@code run.json}, an unreadable/foreign one, or a fingerprint mismatch</strong> — the
 *       reference pass, with the reason naming the state or the key that changed.</li>
 *   <li><strong>The daemon is at the recorded generation</strong> — {@link Mode#NOOP}. The tree the pass
 *       read is the tree the daemon's head describes, and nothing has happened since.</li>
 *   <li><strong>The daemon is later</strong> — connect from the recorded generation with the subfolder
 *       filter and replay up to the server's own generation. A changed {@code .java} under the source root
 *       is {@link Mode#INCREMENTAL}; an added or removed one is {@link Mode#FULL} (the old E6 row: a new or
 *       removed type can change more than its own file); a batch that touches nothing the pass reads is
 *       {@link Mode#NOOP}.</li>
 *   <li><strong>{@code batch-too-old}/{@code batch-gap}, a generation that moved backwards, or any
 *       transport failure</strong> — {@link Mode#FULL}. The daemon's own two refusals mean the replay is
 *       incomplete, and an incomplete change set is never silently skipped.</li>
 * </ul>
 *
 * <p>A <em>no-op writes nothing</em>: the recorded generation stays where it was, so the same small replay
 * happens next time and reaches the same answer. That is the conservative direction — the state can cost a
 * pass, never hide one.</p>
 *
 * <h3>The post-pass generation</h3>
 * <p>{@link #publish} records the generation that includes the pass's own writes. It reads the daemon's head,
 * waits for it to advance (the pass changed content, so the daemon must publish a batch for it) and then for
 * the burst to settle, and records that generation. If nothing advances within the bound, the head it read is
 * already the truthful baseline of an unchanged tree (a rewrite of identical bytes is {@code TOUCHED} and
 * produces no batch), so it is recorded. A generation read before an unpublished write is the safe direction:
 * the next run sees the write and does one conservative pass, rather than the write disappearing. When the
 * daemon cannot be read at all, nothing is written and the previous {@code run.json} (or no state) is left
 * exactly as it was.</p>
 *
 * <h3>Where it lives</h3>
 * <p>The directory is {@code <module>/.jcodebuddy/cache/entity-build/}, where {@code <module>} is the nearest
 * {@link JcodebuddyDirectory#nearestMarker(Path) marker} at or above the report directory and then the source
 * root, with {@code <sourceRoot>/.jcodebuddy/} as the documented fallback for a tree with no marker (DEC-026).
 * A project with one daemon and several modules therefore gets one {@code run.json} per module, not a second
 * daemon root.</p>
 */
final class EntityBuildState {

    /** The derived subtree below the nearest {@code .jcodebuddy/} marker (DEC-026's {@code cache/} rule). */
    static final String CACHE_DIRECTORY = "cache";

    /** The state directory's own name. */
    static final String STATE_DIRECTORY = "entity-build";

    /** The one state file: a daemon-generation marker, not a trio. */
    static final String RUN_FILE = "run.json";

    /** The document's format line; an older or foreign value is a full pass. */
    static final String FORMAT = "jcodebuddy-entity-build/2";

    /** The fingerprint sidecar the trio era wrote; removed on the first successful pass. */
    static final String LEGACY_FINGERPRINT_FILE = "fingerprint.txt";

    /** The trio the state used to publish; removed on the first successful pass, never read. */
    private static final List<String> LEGACY_TRIO_FILES = List.of(
            FsMetaFormat.META_FILE, FsMetaFormat.TXT_FILE, FsMetaFormat.JSON_FILE);

    private static final ObjectMapper MAPPER = EntityMetadataGenerator.OBJECT_MAPPER;

    /** What the caller should do with this tree. */
    enum Mode {
        /** No pass: the daemon is at the recorded generation and the recorded validation licenses a no-op. */
        NOOP,
        /** Existing {@code .java} files changed in place; the pass still emits everything (E2 has no selective emission). */
        INCREMENTAL,
        /** No usable baseline or an unattributable change; run the reference pass. */
        FULL
    }

    /**
     * The decision, with the reason a caller prints and the changed names it reports.
     *
     * @param mode    what to do
     * @param reason  one line a person can read; never null
     * @param changed the names whose <em>content</em> changed, source-root-relative and {@code /}-separated
     */
    record Decision(Mode mode, String reason, List<String> changed) {

        Decision {
            if (mode == null || reason == null) {
                throw new IllegalArgumentException("a decision needs a mode and a reason");
            }
            changed = List.copyOf(changed);
        }

        /** A full-pass decision; {@code changed} is empty when the reason is the state or the daemon. */
        static Decision full(String reason, List<String> changed) {
            return new Decision(Mode.FULL, reason, changed);
        }
    }

    /** What the validation policy actually did on the pass that published the state. */
    enum ValidationOutcome {
        /** The policy ran and found no issues (or the policy was {@code REPORT} and the tree is clean). */
        CLEAN("clean"),
        /** The policy ran and reported issues, even though the pass itself may have continued. */
        REPORTED("reported"),
        /** The policy was {@code OFF}, so validation did not run; a no-op is still allowed. */
        NOT_RUN("not-run");

        private final String wire;

        ValidationOutcome(String wire) {
            this.wire = wire;
        }

        /** The value {@code run.json} carries. */
        String wire() {
            return wire;
        }
    }

    /**
     * Everything about an invocation that can change emitted source without changing a byte of it.
     *
     * <p>Paths are normalized only when the fingerprint is rendered, so a caller may pass the CLI's relative
     * arguments unchanged. Lists keep the caller's order: the CLI's order is the configuration, and treating a
     * reordered list as a different configuration can only cost a full pass.</p>
     */
    record Config(Path metadataOutput, Path javaOutput, List<String> packages,
                  boolean adapters, boolean dtoProjections, List<String> mappers, List<String> merges,
                  boolean patchAppliers, boolean force,
                  EntityMetadataGenerator.Policy validationPolicy) {

        Config {
            packages = List.copyOf(packages == null ? List.<String>of() : packages);
            mappers = List.copyOf(mappers == null ? List.<String>of() : mappers);
            merges = List.copyOf(merges == null ? List.<String>of() : merges);
            validationPolicy = validationPolicy == null ? EntityMetadataGenerator.Policy.OFF : validationPolicy;
        }
    }

    /** What the pass selected, recorded for a reader of {@code run.json}; never an input to a decision. */
    record Selection(String mode, List<String> changed, List<String> affected) {

        Selection {
            mode = mode == null ? "full" : mode;
            changed = List.copyOf(changed == null ? List.<String>of() : changed);
            affected = List.copyOf(affected == null ? List.<String>of() : affected);
        }

        /** The selection of a pass that ran the reference pass. */
        static Selection full() {
            return new Selection("full", List.of(), List.of());
        }
    }

    /**
     * The outcome of {@link #publish}: either a recorded generation, or why no baseline was written.
     *
     * <p>A not-recorded result is a warning, never a failure: the generation already succeeded, and a cache
     * that could not be refreshed must not turn a successful pass into a failed build. The caller decides
     * whether its invocation wanted the baseline ({@code --incremental} does).</p>
     */
    record Publish(boolean recorded, long generation, String problem) {

        Publish {
            problem = problem == null ? "" : problem;
        }

        static Publish recorded(long generation) {
            return new Publish(true, generation, "");
        }

        static Publish not(String problem) {
            return new Publish(false, 0, problem);
        }
    }

    /**
     * The daemon operations the decision and the post-pass record need.
     *
     * <p>A seam, not a second implementation of the protocol: {@link FsMetaDaemonState} is the real one
     * (discovery, the server-side subfolder filter and the replay through {@link FsMetaClient}), and a test can
     * substitute an in-process timeline without a native binary. It deliberately exposes no watcher, no
     * scheduler and no writer — the daemon owns all three.</p>
     */
    interface Daemon {

        /** The daemon serving the deepest ancestor root of the source root, or a problem. */
        FsMetaDaemonDiscovery.Attach discover(Path sourceRoot);

        /** The newest generation the daemon had published when this subscription was made. */
        long head(FsMetaDaemonDiscovery.Location location) throws IOException;

        /**
         * Replays every published generation after {@code fromBatch}, up to the generation the daemon reports
         * when the subscription is made, filtered to the source root.
         *
         * <p>The returned names are <strong>source-root-relative</strong>, so they are the same paths the
         * generator records. A refusal ({@code ProtocolException}) is thrown, never turned into an empty
         * replay: an empty replay would read as "nothing changed", the one wrong answer.</p>
         */
        Replay replay(FsMetaDaemonDiscovery.Location location, long fromBatch) throws IOException;

        /**
         * Waits until the daemon has published the pass's writes and returns the generation to record, or the
         * head it read when the tree did not change. Throws when the daemon cannot be read at all.
         */
        long acknowledge(FsMetaDaemonDiscovery.Location location, long base) throws IOException;

        /** One replay: the newest published generation and the relevant changes up to it. */
        record Replay(long head, List<FsMetaClient.Change> changes) {

            public Replay {
                changes = List.copyOf(changes);
            }
        }
    }

    /** The daemon a CLI invocation talks to: discovery from the process environment, replay over its socket. */
    static Daemon processDaemon(Path sourceRoot) {
        return new FsMetaDaemonState(sourceRoot);
    }

    /**
     * How {@link #forPass(Path, Path)} obtains its daemon.
     *
     * <p>A package-private seam, not a configuration knob: the CLI's decision/publish wiring is the part a test
     * most wants to drive, and a native daemon is not something a unit suite can require. A test sets a fake here
     * and resets it to {@code null} afterwards; production always uses {@link #processDaemon(Path)}.</p>
     */
    private static java.util.function.Function<Path, Daemon> daemonFactory = EntityBuildState::processDaemon;

    /** Replaces the CLI's daemon source for a test; {@code null} restores the process daemon. */
    static void setDaemonFactory(java.util.function.Function<Path, Daemon> factory) {
        daemonFactory = factory == null ? EntityBuildState::processDaemon : factory;
    }

    private final Path sourceRoot;
    private final Path directory;
    private final Daemon daemon;

    /**
     * The state for one invocation.
     *
     * @param sourceRoot the tree the pass reads and writes; normalized here
     * @param directory  the state directory, normally from {@link #stateDirectory(Path, Path)}
     * @param daemon     the daemon seam; {@code null} is refused, because there is no daemonless state any more
     */
    EntityBuildState(Path sourceRoot, Path directory, Daemon daemon) {
        if (sourceRoot == null) {
            throw new IllegalArgumentException("a source root is required");
        }
        if (directory == null) {
            throw new IllegalArgumentException("a state directory is required");
        }
        if (daemon == null) {
            throw new IllegalArgumentException("a daemon seam is required: fs-meta is the tracker, not this class");
        }
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        this.directory = directory.toAbsolutePath().normalize();
        this.daemon = daemon;
    }

    /** The state for a pass over {@code sourceRoot}, talking to the fs-meta daemon of this process's environment. */
    static EntityBuildState forPass(Path sourceRoot, Path metadataOutput) {
        return forPass(sourceRoot, metadataOutput, daemonFactory.apply(sourceRoot));
    }

    /** The injectable form: a test supplies its own {@link Daemon} (or a real one with a test environment). */
    static EntityBuildState forPass(Path sourceRoot, Path metadataOutput, Daemon daemon) {
        return new EntityBuildState(sourceRoot, stateDirectory(sourceRoot, metadataOutput), daemon);
    }

    /**
     * The state directory for a pass: the `.jcodebuddy` component of the report path, then the nearest
     * marker at or above the source root, then the documented {@code <sourceRoot>/.jcodebuddy} fallback.
     *
     * <p>The report path is asked first for the reason {@code EntityMetadataGenerator.resolveModuleRoot}
     * gives: the pass already writes its metadata there, so the module that owns the report owns the state.
     * It is matched as a <em>path component</em>, not as an existing directory, because the first pass
     * creates it: an existing-directory test would put the first state under the source root and the second
     * under the module. A report directory outside the module (a temp directory, a test) still resolves to
     * the module's marker, and only a tree with no marker anywhere falls back to the source root.</p>
     */
    static Path stateDirectory(Path sourceRoot, Path metadataOutput) {
        // The report directory's path names the module even before the first pass creates the
        // `.jcodebuddy` directory. Asking only for an existing marker made the first pass fall back to
        // `<sourceRoot>/.jcodebuddy`, and the second pass — now that the module marker existed — look for
        // the state somewhere else. The path is stable across that creation, so ask it first.
        Path marker = markerFromPath(metadataOutput);
        if (marker == null) {
            marker = JcodebuddyDirectory.nearestMarker(sourceRoot);
        }
        if (marker == null) {
            marker = JcodebuddyDirectory.nearestMarker(metadataOutput);
        }
        if (marker != null) {
            return marker.resolve(CACHE_DIRECTORY).resolve(STATE_DIRECTORY);
        }
        if (sourceRoot == null) {
            throw new IllegalArgumentException("a source root is required when no marker exists");
        }
        return sourceRoot.toAbsolutePath().normalize()
                .resolve(JcodebuddyDirectory.DIR)
                .resolve(CACHE_DIRECTORY)
                .resolve(STATE_DIRECTORY);
    }

    /** The `.jcodebuddy` component of a path, whether or not that directory exists yet. */
    private static Path markerFromPath(Path path) {
        if (path == null) {
            return null;
        }
        for (Path at = path.toAbsolutePath().normalize(); at != null; at = at.getParent()) {
            if (at.getFileName() != null && JcodebuddyDirectory.DIR.equals(at.getFileName().toString())) {
                return at;
            }
        }
        return null;
    }

    /** The state directory this instance reads and writes. */
    Path directory() {
        return directory;
    }

    /** The state file this instance reads and writes. */
    Path stateFile() {
        return directory.resolve(RUN_FILE);
    }

    /**
     * Decides what this invocation should do, before validation or parsing.
     *
     * <p>No branch throws: a daemon, a state or a replay that cannot be read is a full pass with a reason,
     * which is the whole point of the state being a cache. The one exception is a bug in the seam itself,
     * which surfaces as the same full-pass decision rather than taking the pass down.</p>
     */
    Decision decide(Config config) {
        FsMetaDaemonDiscovery.Attach attach;
        try {
            attach = daemon.discover(sourceRoot);
        } catch (RuntimeException failure) {
            return Decision.full("the fs-meta daemon lookup failed (" + message(failure) + ")", List.of());
        }
        if (attach == null || !attach.attached()) {
            return Decision.full("no fs-meta daemon serves " + sourceRoot + ": "
                    + (attach == null ? "discovery produced no result" : attach.problem())
                    + "; a full pass is the safe fallback, and --incremental cannot claim a no-op without a"
                    + " daemon baseline", List.of());
        }

        // `--force` is an explicit request for the canonical text, so it may not be answered by "nothing
        // changed" — plan § 3.5 lists it with `--full` in the full-pass row.
        if (config.force()) {
            return Decision.full("--force requests a full pass", List.of());
        }

        Loaded loaded = loadState();
        if (loaded.state() == null) {
            return Decision.full(loaded.problem(), List.of());
        }
        State previous = loaded.state();

        if (!normalized(sourceRoot).equals(previous.sourceRoot())) {
            return Decision.full("the build state describes the source root '" + previous.sourceRoot()
                    + "', not " + normalized(sourceRoot), List.of());
        }
        String daemonRoot = normalized(attach.location().root());
        if (!daemonRoot.equals(previous.daemonRoot())) {
            return Decision.full("the build state records the daemon root '" + previous.daemonRoot()
                    + "', not " + daemonRoot, List.of());
        }
        Map<String, String> current = fingerprint(config, daemonRoot);
        String mismatch = firstMismatch(previous.fingerprint(), current);
        if (mismatch != null) {
            return Decision.full("the pass fingerprint changed: " + mismatch
                    + " ('" + previous.fingerprint().getOrDefault(mismatch, "<absent>") + "' -> '"
                    + current.getOrDefault(mismatch, "<absent>") + "')", List.of());
        }
        // A no-op may skip validation only when the pass that wrote the state validated the same policy
        // cleanly; with the policy OFF nothing needed validating, which is the one documented exception.
        if (config.validationPolicy() != EntityMetadataGenerator.Policy.OFF
                && !ValidationOutcome.CLEAN.wire().equals(previous.validation())) {
            return Decision.full("no source file changed, but the state records validation '"
                    + previous.validation() + "' and policy " + config.validationPolicy()
                    + " permits a no-op only after a clean pass", List.of());
        }

        try {
            Daemon.Replay replay = daemon.replay(attach.location(), previous.generation());
            if (replay.head() < previous.generation()) {
                return Decision.full("the fs-meta daemon is at generation " + replay.head() + ", before the recorded "
                        + previous.generation() + " (its history was replaced); a full pass rebuilds the baseline",
                        List.of());
            }
            List<String> changed = new ArrayList<>();
            int added = 0;
            int removed = 0;
            int modified = 0;
            for (FsMetaClient.Change change : replay.changes()) {
                String name = change.name();
                // The pass reads only Java; the daemon tracks every file, and a resource's bytes cannot
                // change its output. The server already filtered to the source-root subfolder.
                if (name == null || !name.endsWith(".java")) {
                    continue;
                }
                switch (change.kind()) {
                    case ADDED -> {
                        added++;
                        changed.add(name);
                    }
                    case DELETED -> {
                        removed++;
                        changed.add(name);
                    }
                    case CHANGED -> {
                        modified++;
                        changed.add(name);
                    }
                }
            }
            if (added > 0 || removed > 0) {
                return Decision.full("the source set changed: " + changes(added, removed)
                        + " (a new or removed type can change more than its own file)", List.copyOf(changed));
            }
            if (modified > 0) {
                return new Decision(Mode.INCREMENTAL,
                        modified + " existing source file(s) changed since generation " + previous.generation()
                                + " (E2 runs the existing full pass)",
                        List.copyOf(changed));
            }
            if (replay.head() == previous.generation()) {
                return new Decision(Mode.NOOP, "the fs-meta daemon is still at generation " + replay.head()
                        + " and no .java file under " + sourceRoot + " changed", List.of());
            }
            return new Decision(Mode.NOOP, "no .java file under " + sourceRoot + " changed in daemon generations "
                    + (previous.generation() + 1) + ".." + replay.head(), List.of());
        } catch (FsMetaClient.ProtocolException refusal) {
            return Decision.full(refusal.needsFullRead()
                    ? "the fs-meta daemon cannot replay from generation " + previous.generation() + " ("
                            + refusal.getMessage() + "); a full pass rebuilds the baseline"
                    : "the fs-meta daemon refused the subscription from generation " + previous.generation() + " ("
                            + refusal.getMessage() + ")", List.of());
        } catch (IOException | RuntimeException failure) {
            return Decision.full("the fs-meta daemon at " + attach.location().socket() + " could not be read ("
                    + message(failure) + "); a full pass is the safe fallback", List.of());
        }
    }

    /**
     * Records the daemon generation the successful pass left behind, and the selection that produced it.
     *
     * <p>Called only after a successful pass, never from {@link #decide} and never on a failed one. It never
     * throws for an unavailable daemon: the generation already succeeded, and a baseline that could not be
     * refreshed is a warning the caller prints, not a failed build. A not-recorded result leaves whatever
     * {@code run.json} was there untouched, because an older truthful baseline costs one pass while a guessed
     * newer one would hide the next run.</p>
     */
    Publish publish(Config config, ValidationOutcome validation, Selection selection) {
        FsMetaDaemonDiscovery.Attach attach;
        try {
            attach = daemon.discover(sourceRoot);
        } catch (RuntimeException failure) {
            return Publish.not("the fs-meta daemon lookup failed (" + message(failure) + ")");
        }
        if (attach == null || !attach.attached()) {
            return Publish.not("no fs-meta daemon serves " + sourceRoot + ": "
                    + (attach == null ? "discovery produced no result" : attach.problem()));
        }
        FsMetaDaemonDiscovery.Location location = attach.location();
        long generation;
        try {
            long base = daemon.head(location);
            generation = daemon.acknowledge(location, base);
        } catch (IOException | RuntimeException failure) {
            return Publish.not("the fs-meta daemon at " + location.socket()
                    + " could not confirm the pass (" + message(failure) + ")");
        }
        try {
            writeState(config, validation, selection, location, generation);
        } catch (IOException | RuntimeException failure) {
            return Publish.not("the build state at " + stateFile() + " could not be written ("
                    + message(failure) + ")");
        }
        // Cleanup only: a leftover trio-era file is untidy, not a state the next run would read, so failing
        // to delete it must not turn a written, truthful baseline into a "not recorded" result.
        removeLegacyState();
        return Publish.recorded(generation);
    }

    /**
     * The validation outcome of the pass that just ran, for {@link #publish}.
     *
     * <p>{@code OFF} is recorded as not run rather than as clean: the two are different facts, and the
     * no-op rule treats them differently only in that both may skip validation.</p>
     */
    static ValidationOutcome validationOutcome(EntityMetadataGenerator.Policy policy) {
        if (policy == null || policy == EntityMetadataGenerator.Policy.OFF) {
            return ValidationOutcome.NOT_RUN;
        }
        return EntityMetadataGenerator.lastValidationIssues().isEmpty()
                ? ValidationOutcome.CLEAN
                : ValidationOutcome.REPORTED;
    }

    // ── state I/O ─────────────────────────────────────────────────────────────

    /** A loaded state, or the reason there is none; exactly one of the two is null. */
    private record Loaded(State state, String problem) {
    }

    /** The facts a decision reads out of one {@code run.json}. */
    private record State(String sourceRoot, String daemonRoot, long generation,
                         Map<String, String> fingerprint, String validation) {
    }

    /**
     * Reads {@code run.json}, or explains why it cannot be used.
     *
     * <p>A legacy trio is recognised on purpose: it is not silently treated as "no state" when it can name
     * <em>why</em> the next pass is full, because a reader who upgraded the tooling needs to know that the
     * old state was replaced rather than that their project lost its cache.</p>
     */
    private Loaded loadState() {
        Path file = stateFile();
        if (!Files.isRegularFile(file)) {
            if (legacyStatePresent()) {
                return new Loaded(null, "the build state at " + directory + " is a legacy fs-meta trio ("
                        + String.join(", ", LEGACY_TRIO_FILES) + "), which this version no longer reads;"
                        + " a successful pass replaces it with " + RUN_FILE);
            }
            return new Loaded(null, "no build state at " + file);
        }
        JsonNode json;
        try {
            json = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return new Loaded(null, "the build state at " + file + " cannot be read ("
                    + message(unreadable) + ")");
        }
        if (json == null || !json.isObject()) {
            return new Loaded(null, "the build state at " + file + " is not a JSON object");
        }
        String format = text(json, "format");
        if (!FORMAT.equals(format)) {
            return new Loaded(null, "the build state at " + file + " has format '" + format + "', not " + FORMAT
                    + " (a full pass replaces it)");
        }
        String sourceRootText = text(json, "sourceRoot");
        String projectRoot = text(json, "projectRoot");
        JsonNode daemonNode = json.path("daemon");
        String daemonRoot = text(daemonNode, "root");
        long generation = daemonNode.path("generation").asLong(-1);
        if (sourceRootText == null || projectRoot == null || daemonRoot == null || generation < 0) {
            return new Loaded(null, "the build state at " + file
                    + " is missing its source root, project root, daemon root or generation");
        }
        Map<String, String> fingerprint = fingerprintOf(json.path("fingerprint"));
        if (fingerprint == null) {
            return new Loaded(null, "the build state at " + file + " has no usable fingerprint");
        }
        String validation = text(json, "validation");
        if (validation == null) {
            return new Loaded(null, "the build state at " + file + " records no validation outcome");
        }
        return new Loaded(new State(sourceRootText, daemonRoot, generation, fingerprint, validation), "");
    }

    /**
     * The fingerprint object as a string map, or {@code null} when it is absent or a value is not a string.
     * {@code null} is deliberately not an empty map: "no usable fingerprint" and "a fingerprint that matches"
     * must not be one step apart.
     */
    private static Map<String, String> fingerprintOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (entry.getValue() == null || !entry.getValue().isString()) {
                return null;
            }
            values.put(entry.getKey(), entry.getValue().asString());
        }
        return values.isEmpty() ? null : values;
    }

    /** Writes the state atomically: a temporary beside the file, then a rename over it. */
    private void writeState(Config config, ValidationOutcome validation, Selection selection,
                            FsMetaDaemonDiscovery.Location location, long generation) throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", FORMAT);
        document.put("generator", EntityMetadataGenerator.generatorIdentity());
        document.put("sourceRoot", normalized(sourceRoot));
        document.put("projectRoot", normalized(location.root()));
        Map<String, Object> daemonDocument = new LinkedHashMap<>();
        daemonDocument.put("id", location.id());
        daemonDocument.put("root", normalized(location.root()));
        daemonDocument.put("generation", generation);
        document.put("daemon", daemonDocument);
        document.put("validation", validation.wire());
        document.put("selection", selectionDocument(selection));
        document.put("fingerprint", fingerprint(config, normalized(location.root())));

        Files.createDirectories(directory);
        Path temporary = directory.resolve(RUN_FILE + ".tmp");
        try {
            Files.writeString(temporary, MAPPER.writeValueAsString(document) + "\n", StandardCharsets.UTF_8);
            Path file = stateFile();
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // A failed write must not leave a half-written candidate beside a truthful state.
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, Object> selectionDocument(Selection selection) {
        Selection value = selection == null ? Selection.full() : selection;
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("mode", value.mode());
        document.put("changed", value.changed());
        document.put("affected", value.affected());
        return document;
    }

    /** Removes the trio era's files after the first successful pass; the state is a cache, so deleting is safe. */
    private void removeLegacyState() {
        List<String> legacy = new ArrayList<>(LEGACY_TRIO_FILES);
        legacy.add(LEGACY_FINGERPRINT_FILE);
        for (String name : legacy) {
            try {
                Files.deleteIfExists(directory.resolve(name));
            } catch (IOException ignored) {
                // Cleanup only: the next successful pass tries again, and nothing reads these files any more.
            }
        }
    }

    /** Whether the trio era's state is still on disk. */
    private boolean legacyStatePresent() {
        for (String name : LEGACY_TRIO_FILES) {
            if (Files.isRegularFile(directory.resolve(name))) {
                return true;
            }
        }
        return Files.isRegularFile(directory.resolve(LEGACY_FINGERPRINT_FILE));
    }

    /**
     * The invocation's fingerprint, in the order {@code run.json} writes it.
     *
     * <p>{@link EntityMetadataGenerator#generatorIdentity()} rather than the bare version: which artifact the
     * classes came from is part of "the same generator", and the identity line already distinguishes an
     * installed jar from the reactor's {@code target/classes}. {@code projectRoot}/{@code sourceRoot} are in
     * it because a moved module or a different daemon root describes a different tree.</p>
     */
    private Map<String, String> fingerprint(Config config, String projectRoot) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("format", FORMAT);
        values.put("generator", EntityMetadataGenerator.generatorIdentity());
        values.put("jdk", Integer.toString(Runtime.version().feature()));
        values.put("sourceRoot", normalized(sourceRoot));
        values.put("projectRoot", projectRoot);
        values.put("metadataOutput", normalized(config.metadataOutput()));
        values.put("javaOutput", normalized(config.javaOutput()));
        values.put("packages", String.join(",", config.packages()));
        values.put("adapters", Boolean.toString(config.adapters()));
        values.put("dtoProjections", Boolean.toString(config.dtoProjections()));
        values.put("mappers", String.join(",", config.mappers()));
        values.put("merges", String.join(",", config.merges()));
        values.put("patchAppliers", Boolean.toString(config.patchAppliers()));
        values.put("force", Boolean.toString(config.force()));
        values.put("validationPolicy", config.validationPolicy().name());
        return values;
    }

    /**
     * The first key whose value differs, including a key present on only one side.
     *
     * <p>{@code validation} is not part of the fingerprint: it describes the previous pass rather than this
     * invocation, so it is compared by the no-op rule instead.</p>
     */
    private static String firstMismatch(Map<String, String> stored, Map<String, String> current) {
        for (Map.Entry<String, String> entry : current.entrySet()) {
            if (!Objects.equals(entry.getValue(), stored.get(entry.getKey()))) {
                return entry.getKey();
            }
        }
        for (String key : stored.keySet()) {
            if (!"validation".equals(key) && !current.containsKey(key)) {
                return key;
            }
        }
        return null;
    }

    /** A normalized path for the fingerprint: absolute, forward slashes, so two spellings cannot differ. */
    private static String normalized(Path path) {
        if (path == null) {
            return "";
        }
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /** A one-line summary of the change counts for a full-pass reason. */
    private static String changes(int added, int removed) {
        List<String> parts = new ArrayList<>(2);
        if (added > 0) {
            parts.add(added + " added");
        }
        if (removed > 0) {
            parts.add(removed + " removed");
        }
        return String.join(", ", parts);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    /** An exception's message, or its class name when it has none. */
    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
