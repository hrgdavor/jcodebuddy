package hr.hrg.hipster.entity.tooling.index;

import hr.hrg.fsmeta.FsMetaCodec;
import hr.hrg.fsmeta.FsMetaConfig;
import hr.hrg.fsmeta.FsMetaDiff;
import hr.hrg.fsmeta.FsMetaEntry;
import hr.hrg.fsmeta.FsMetaFile;
import hr.hrg.fsmeta.FsMetaFormat;
import hr.hrg.fsmeta.FsMetaHashMode;
import hr.hrg.fsmeta.FsMetaLoad;
import hr.hrg.fsmeta.FsMetaPair;
import hr.hrg.fsmeta.FsMetaScanOptions;
import hr.hrg.fsmeta.FsMetaScanner;
import hr.hrg.fsmeta.FsMetaWriter;
import hr.hrg.jcodebuddy.engine.JcodebuddyDirectory;
import hr.hrg.jcodebuddy.engine.MetadataJson;
import hr.hrg.jcodebuddy.engine.index.ContentHash;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The disposable test-run state behind {@code scripts/test-affected.js} (plan
 * {@code plans/incremental-test-selection.md} § 5.1, step T4).
 *
 * <h3>What it owns</h3>
 * <p>One {@code fs-meta} trio and a small {@code run.json} under
 * {@code <module>/.jcodebuddy/cache/test-affected/}. The trio is the same {@code TEXT}-hashed,
 * {@code writer}-tagged format the entity build state uses ({@code EntityBuildState}), consumed through the same
 * {@code hr.hrg.fsmeta} API rather than a second fingerprinting scheme: {@link FsMetaPair#loadIfPresent} →
 * {@link FsMetaScanner#scan} → {@link FsMetaDiff#between} → {@link FsMetaWriter#publish}.</p>
 *
 * <h3>What it scans, and what it deliberately does not</h3>
 * <p>The inputs are the test module's {@code src/main/java}, {@code src/test/java}, {@code src/main/resources},
 * {@code src/test/resources} and {@code pom.xml}, plus the workspace source roots of every module whose files
 * appear in the test index's {@code files}/{@code production} tables (plan § 5.1). A tooling test that depends
 * on a {@code jcodebuddy-core} class therefore sees a core source edit. A dependency that exists only as a jar
 * has no source root and no local change to see.</p>
 *
 * <p>The scan ignores {@code target/}, {@code .jcodebuddy/} and {@code .git/}; files outside these roots
 * (docs, {@code scripts/}) are not test inputs and must not invalidate a run. {@code .gitignore} is
 * deliberately not honoured, for the same reason the entity build state gives: the state's subject is what the
 * test run reads, not what git tracks.</p>
 *
 * <h3>The decision is a cache, never a correctness input</h3>
 * <p>{@link #decide()} answers with one of three modes and never throws:</p>
 * <ul>
 *   <li>{@link Mode#NOOP} — no test input's content changed and the test-index checksum still matches the one
 *       recorded by the last successful run. The caller prints {@code no-op}, runs no Maven and writes
 *       nothing.</li>
 *   <li>{@link Mode#CHANGED} — content changes exist; the caller passes {@link Decision#changed()} to
 *       {@link TestSelector}.</li>
 *   <li>{@link Mode#FULL} — the state is missing, corrupt or foreign, the index checksum changed, the watched
 *       input set changed, or a {@code pom.xml} changed. The conservative answer is all unit tests (plan
 *       § 5.1).</li>
 * </ul>
 * <p>{@code TOUCHED} entries are ignored: a same-content save is exactly what the state exists to let a no-op
 * answer.</p>
 *
 * <h3>{@code run.json}, and publishing only after a successful run</h3>
 * <p>The sidecar records the test-index checksum, the last successful run's selected tests, the watched input
 * set, and the published trio's generation/count/instant. It is written <em>after</em> the trio, and the trio
 * is published only by {@link #publish}; a failed test run never calls it, so the previous state stays in place
 * and the same tests are selected again (plan T4 gate).</p>
 *
 * <p>This class is test-scoped for the same reason T1's generator and T3's selector are: it is dev-time test
 * selection over a compiled test classpath, not a runtime dependency of the library.
 * {@code scripts/test-affected.js} invokes it from {@code target/test-classes}.</p>
 */
public final class TestRunState {
    /** The state directory's own name below {@code .jcodebuddy/cache}. */
    public static final String STATE_DIRECTORY = "test-affected";

    /** The cache directory below the module marker (DEC-026's derived subtree). */
    private static final String CACHE_DIRECTORY = "cache";

    /** The sidecar file name; it is not part of the {@code fs-meta} trio. */
    public static final String RUN_FILE = "run.json";

    /** The trio's {@code writer} field, which is how a foreign trio is recognised and rejected. */
    public static final String FS_META_WRITER = "jcodebuddy-test-affected/1";

    /** The {@code run.json} format this version understands. */
    public static final int RUN_FORMAT = 1;

    /** The module-relative roots § 5.1 always scans, whether or not the index mentions them. */
    private static final List<String> MODULE_ROOTS = List.of(
            "src/main/java", "src/test/java", "src/main/resources", "src/test/resources");

    /** The roots a dependency module can contribute through an index row. */
    private static final List<String> SOURCE_ROOTS = MODULE_ROOTS;

    /**
     * The scan options: text-aware hashing so a CRLF and an LF checkout agree, every derived or VCS directory
     * skipped, and {@code .gitignore} deliberately not consulted.
     */
    private static final FsMetaScanOptions SCAN_OPTIONS = FsMetaScanOptions.defaults()
            .ignoring(JcodebuddyDirectory.DIR)
            .ignoring("target")
            .ignoring(".git")
            .withoutGitignore();

    /** What the caller should do with this tree. */
    public enum Mode {
        /** No test input changed and the index checksum matches: write nothing, run nothing. */
        NOOP("noop"),
        /** Content changes exist; pass the changed names to the selector. */
        CHANGED("changed"),
        /** No usable state, a changed index or a POM change: run every unit test. */
        FULL("full");

        private final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        /** The value {@code run.json} and the CLI carry. */
        public String wire() {
            return wire;
        }
    }

    /**
     * The watched inputs, repo-relative and {@code /}-separated.
     *
     * @param roots existing source/resource directories to scan
     * @param files individual files to scan (the module POM today)
     */
    public record Inputs(List<String> roots, List<String> files) {

        public Inputs {
            roots = sorted(roots);
            files = sorted(files);
        }

        private static List<String> sorted(List<String> values) {
            Set<String> sorted = new TreeSet<>();
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.isBlank()) {
                        sorted.add(value);
                    }
                }
            }
            return List.copyOf(sorted);
        }
    }

    /**
     * The decision, with the reason the caller prints, the changed names it reports, and the inputs it used.
     *
     * @param mode          what to do
     * @param reason        one line a person can read; never null
     * @param changed       content-changed names, repo-relative and {@code /}-separated; {@code TOUCHED}
     *                      entries are deliberately absent
     * @param indexChecksum the checksum of {@code tests.json}, or {@code null} when it could not be read
     * @param inputs        the watched inputs, or {@code null} when the index could not be read
     * @param index         the index path as a diagnostic, repo-relative when it is under the root
     */
    public record Decision(Mode mode, String reason, List<String> changed, String indexChecksum,
                           Inputs inputs, String index) {

        public Decision {
            if (mode == null || reason == null) {
                throw new IllegalArgumentException("a decision needs a mode and a reason");
            }
            changed = List.copyOf(changed == null ? List.of() : changed);
        }

        /** A no-op decision: no input content changed and the index checksum matches. */
        public static Decision noop(String reason, String indexChecksum, Inputs inputs, String index) {
            return new Decision(Mode.NOOP, reason, List.of(), indexChecksum, inputs, index);
        }

        /** A changed-input decision. */
        public static Decision changed(String reason, List<String> changed, String indexChecksum,
                                       Inputs inputs, String index) {
            return new Decision(Mode.CHANGED, reason, changed, indexChecksum, inputs, index);
        }

        /** The conservative all-unit decision. */
        public static Decision full(String reason, List<String> changed, String indexChecksum,
                                    Inputs inputs, String index) {
            return new Decision(Mode.FULL, reason, changed, indexChecksum, inputs, index);
        }

        /** The machine-readable form the Bun runner reads. */
        public String toJson() {
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.put("format", RUN_FORMAT);
            root.put("mode", mode.wire());
            root.put("reason", reason);
            if (index != null) {
                root.put("index", index);
            }
            if (indexChecksum != null) {
                root.put("indexChecksum", indexChecksum);
            }
            var changedNode = root.putArray("changed");
            changed.forEach(changedNode::add);
            if (inputs != null) {
                ObjectNode inputsNode = root.putObject("inputs");
                var rootsNode = inputsNode.putArray("roots");
                inputs.roots().forEach(rootsNode::add);
                var filesNode = inputsNode.putArray("files");
                inputs.files().forEach(filesNode::add);
            }
            return pretty(root);
        }
    }

    /** The parsed parts of the test index this class needs to decide what to watch. */
    private record IndexTable(String module, Set<String> paths) {
    }

    /** The sidecar's fields this class uses. */
    private record RunRecord(String indexChecksum, List<String> selected, Inputs inputs) {
    }

    private final Path repoRoot;
    private final Path moduleDir;
    private final Path indexFile;
    private final Path directory;

    /**
     * The state for one module.
     *
     * @param repoRoot  the repository root the state's names are relative to
     * @param moduleDir the test module whose tests the fast lane runs
     * @param indexFile the generated {@code .jcodebuddy/index/tests.json}
     */
    public TestRunState(Path repoRoot, Path moduleDir, Path indexFile) {
        this(repoRoot, moduleDir, indexFile, stateDirectory(moduleDir));
    }

    /** The state for one module with an explicit state directory (the tests' seam). */
    public TestRunState(Path repoRoot, Path moduleDir, Path indexFile, Path directory) {
        if (repoRoot == null || moduleDir == null || indexFile == null || directory == null) {
            throw new IllegalArgumentException(
                    "a root, a module directory, an index and a state directory are required");
        }
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.moduleDir = moduleDir.toAbsolutePath().normalize();
        this.indexFile = indexFile.isAbsolute()
                ? indexFile.toAbsolutePath().normalize()
                : this.repoRoot.resolve(indexFile).normalize();
        this.directory = directory.toAbsolutePath().normalize();
    }

    /** {@code <module>/.jcodebuddy/cache/test-affected}, as plan § 5.1 names it. */
    public static Path stateDirectory(Path moduleDir) {
        return moduleDir.toAbsolutePath().normalize()
                .resolve(JcodebuddyDirectory.DIR)
                .resolve(CACHE_DIRECTORY)
                .resolve(STATE_DIRECTORY);
    }

    /** The state directory this instance reads and writes. */
    public Path directory() {
        return directory;
    }

    /**
     * Decides what {@code scripts/test-affected.js} should do, before any Maven goal runs.
     *
     * <p>No branch throws: a state, an index or a scan that cannot be read is the conservative all-unit
     * decision with a reason, which is the whole point of the state being a cache.</p>
     */
    public Decision decide() {
        String indexDisplay = display(indexFile);
        IndexTable index;
        String checksum;
        try {
            index = readIndex(indexFile);
            checksum = ContentHash.of(indexFile);
        } catch (IOException | RuntimeException unreadable) {
            return Decision.full("the test index at " + indexDisplay + " cannot be read ("
                    + message(unreadable) + "); every unit test is selected", List.of(), null, null,
                    indexDisplay);
        }

        Inputs inputs;
        try {
            inputs = inputsFor(index);
        } catch (RuntimeException failure) {
            return Decision.full("the test-affected inputs could not be derived from " + indexDisplay + " ("
                    + message(failure) + "); every unit test is selected", List.of(), checksum, null,
                    indexDisplay);
        }

        FsMetaLoad loaded;
        try {
            loaded = FsMetaPair.loadIfPresent(directory);
        } catch (IOException | RuntimeException unreadable) {
            return Decision.full("the test-run state at " + directory + " cannot be read ("
                    + message(unreadable) + "); every unit test is selected", List.of(), checksum, inputs,
                    indexDisplay);
        }
        if (loaded == null) {
            return Decision.full("no test-run state at " + directory + "; the first run selects every unit test",
                    List.of(), checksum, inputs, indexDisplay);
        }
        if (!loaded.ok() || loaded.pair() == null) {
            return Decision.full("the test-run state at " + directory + " is not usable: " + loaded.describe()
                    + "; every unit test is selected", List.of(), checksum, inputs, indexDisplay);
        }
        FsMetaPair previous = loaded.pair();

        // A trio from another writer, with another hash contract or about another root is not "old": it is a
        // different state, and diffing it would compare hashes that do not mean the same thing.
        if (!FS_META_WRITER.equals(loaded.config().writer())) {
            return Decision.full("the test-run state at " + directory + " was written by '"
                    + loaded.config().writer() + "', not " + FS_META_WRITER + "; every unit test is selected",
                    List.of(), checksum, inputs, indexDisplay);
        }
        if (!FsMetaHashMode.TEXT.wire().equals(loaded.config().hashNormalize())) {
            return Decision.full("the test-run state at " + directory + " was hashed with '"
                    + loaded.config().hashNormalize() + "', not " + FsMetaHashMode.TEXT.wire()
                    + "; every unit test is selected", List.of(), checksum, inputs, indexDisplay);
        }
        if (!repoRoot.toString().equals(loaded.config().root())) {
            return Decision.full("the test-run state describes the root '" + loaded.config().root()
                    + "', not " + repoRoot + "; every unit test is selected", List.of(), checksum, inputs,
                    indexDisplay);
        }

        RunRecord run = readRun();
        if (run == null) {
            return Decision.full("the test-run sidecar at " + directory.resolve(RUN_FILE)
                    + " is missing, unreadable or not understood; every unit test is selected", List.of(),
                    checksum, inputs, indexDisplay);
        }
        if (!run.indexChecksum().equals(checksum)) {
            return Decision.full("the test index changed since the last successful run (" + run.indexChecksum()
                    + " -> " + checksum + "); every unit test is selected", List.of(), checksum, inputs,
                    indexDisplay);
        }
        if (!run.inputs().equals(inputs)) {
            return Decision.full("the watched test-affected input set changed since the last successful run ("
                    + difference(run.inputs(), inputs) + "); every unit test is selected", List.of(), checksum,
                    inputs, indexDisplay);
        }

        FsMetaPair current;
        try {
            current = pairOf(scan(inputs));
        } catch (IOException | RuntimeException unreadable) {
            return Decision.full("the test-affected inputs could not be scanned (" + message(unreadable)
                    + "); every unit test is selected", List.of(), checksum, inputs, indexDisplay);
        }

        List<String> changed = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int modified = 0;
        for (FsMetaDiff.Change change : FsMetaDiff.between(previous, current)) {
            switch (change.kind()) {
                case ADDED -> {
                    added++;
                    changed.add(change.name());
                }
                case REMOVED -> {
                    removed++;
                    changed.add(change.name());
                }
                case MODIFIED -> {
                    modified++;
                    changed.add(change.name());
                }
                case UNKNOWN -> changed.add(change.name());
                // A same-content save is exactly what the state is for: the bytes did not change, so the
                // derived work is still valid.
                case TOUCHED -> {
                }
            }
        }

        if (changed.isEmpty()) {
            return Decision.noop("no test input changed since the last successful run", checksum, inputs,
                    indexDisplay);
        }
        if (changed.stream().anyMatch(TestRunState::isPom)) {
            return Decision.full("a pom.xml change invalidates the module's test inputs; every unit test is "
                    + "selected", List.copyOf(changed), checksum, inputs, indexDisplay);
        }
        return Decision.changed(changeCount(added, removed, modified) + " changed since the last successful run",
                List.copyOf(changed), checksum, inputs, indexDisplay);
    }

    /**
     * Publishes the current tree and the successful run's selection (plan § 5.1).
     *
     * <p>Callers must call this <strong>only after every selected test passed</strong>; a failed run leaves
     * the previous state untouched, so the same tests are selected next time. The trio is written first and
     * {@code run.json} last, and the old sidecar is removed before the trio: a sidecar that described a state
     * this publish did not write would be worse than no sidecar.</p>
     *
     * @param expectedIndexChecksum the index checksum the selection was computed from, or {@code null} to skip
     *                              the guard; a mismatch aborts the publish
     * @param selected              the tests that passed (empty when the selection was empty)
     * @throws IOException when the trio or the sidecar cannot be written, or the index changed during the run
     */
    public void publish(String expectedIndexChecksum, List<String> selected) throws IOException {
        String checksum = ContentHash.of(indexFile);
        if (expectedIndexChecksum != null && !expectedIndexChecksum.equals(checksum)) {
            throw new IOException("the test index changed while the tests ran (" + expectedIndexChecksum
                    + " -> " + checksum + "); refusing to publish a state that does not describe this selection");
        }
        IndexTable index = readIndex(indexFile);
        Inputs inputs = inputsFor(index);
        List<FsMetaFile> files = scan(inputs);

        Path runFile = directory.resolve(RUN_FILE);
        Files.deleteIfExists(runFile);
        FsMetaLoad published = FsMetaWriter.publish(directory, files, FsMetaHashMode.TEXT, FS_META_WRITER,
                repoRoot.toString());
        if (!published.ok() || published.pair() == null) {
            throw new IOException("the test-run state could not be validated after publishing: "
                    + published.describe());
        }

        String text = runJson(checksum, selected, inputs, index, published);
        Files.createDirectories(directory);
        writeAtomically(runFile, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Publishes without the index-changed-during-the-run guard; the tests' convenience. */
    public void publish(List<String> selected) throws IOException {
        publish(null, selected);
    }

    /** The {@code run.json} document: index checksum, selection, watched inputs, and the trio's identity. */
    private static String runJson(String checksum, List<String> selected, Inputs inputs, IndexTable index,
                                  FsMetaLoad published) {
        FsMetaPair pair = Objects.requireNonNull(published.pair(), "a validated trio is required");
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("format", RUN_FORMAT);
        root.put("module", index.module());
        root.put("indexChecksum", checksum);
        root.put("completedAt", Instant.now().toString());

        var selectedNode = root.putArray("selected");
        new TreeSet<>(selected == null ? List.<String>of() : selected).forEach(selectedNode::add);

        ObjectNode inputsNode = root.putObject("inputs");
        var rootsNode = inputsNode.putArray("roots");
        inputs.roots().forEach(rootsNode::add);
        var filesNode = inputsNode.putArray("files");
        inputs.files().forEach(filesNode::add);

        // The last successful run's trio: the identity of the state this run published. run.json is written
        // after the trio, so these values describe the files a reader will find beside it.
        ObjectNode state = root.putObject("state");
        state.put("generation", pair.config().generation());
        state.put("entries", pair.size());
        state.put("created", pair.config().created());
        state.put("updated", pair.config().updated());
        state.put("writer", pair.config().writer());
        return pretty(root);
    }

    /** {@code run.json}, or {@code null} when it is absent, unreadable, a different format or incomplete. */
    private RunRecord readRun() {
        Path file = directory.resolve(RUN_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = MetadataJson.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isObject() || root.path("format").asInt(-1) != RUN_FORMAT) {
                return null;
            }
            String checksum = root.path("indexChecksum").asString("");
            if (checksum.isBlank()) {
                return null;
            }
            Inputs inputs = readInputs(root.path("inputs"));
            List<String> selected = stringList(root.path("selected"));
            if (inputs == null || selected == null) {
                return null;
            }
            return new RunRecord(checksum, selected, inputs);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static Inputs readInputs(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        List<String> roots = stringList(node.path("roots"));
        List<String> files = stringList(node.path("files"));
        if (roots == null || files == null) {
            return null;
        }
        return new Inputs(roots, files);
    }

    /** A JSON array as strings, or {@code null} when it is not an array. */
    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode child : node) {
            values.add(child.asString());
        }
        return values;
    }

    /** A one-line description of how two input sets differ, for the decision reason. */
    private static String difference(Inputs stored, Inputs current) {
        Set<String> storedAll = new TreeSet<>(stored.roots());
        storedAll.addAll(stored.files());
        Set<String> currentAll = new TreeSet<>(current.roots());
        currentAll.addAll(current.files());
        Set<String> added = new TreeSet<>(currentAll);
        added.removeAll(storedAll);
        Set<String> removed = new TreeSet<>(storedAll);
        removed.removeAll(currentAll);
        List<String> parts = new ArrayList<>();
        if (!added.isEmpty()) {
            parts.add("added " + String.join(", ", added));
        }
        if (!removed.isEmpty()) {
            parts.add("removed " + String.join(", ", removed));
        }
        return parts.isEmpty() ? "same names, different state" : String.join("; ", parts);
    }

    /**
     * Parses the parts of {@code tests.json} the state needs: the module name and every file path it names.
     *
     * @throws IllegalArgumentException when the table is not a format-1 test index
     */
    private static IndexTable readIndex(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(file + " does not exist");
        }
        JsonNode root = MetadataJson.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
        if (!root.isObject()) {
            throw new IllegalArgumentException("the index root is not a JSON object");
        }
        JsonNode format = root.path("format");
        if (!format.isIntegralNumber() || format.asInt() != TestSelector.FORMAT) {
            throw new IllegalArgumentException("format is " + format + ", expected " + TestSelector.FORMAT);
        }
        JsonNode files = root.path("files");
        JsonNode production = root.path("production");
        if (!files.isObject() || !production.isObject()) {
            throw new IllegalArgumentException("the files and production tables must be JSON objects");
        }
        Set<String> paths = new TreeSet<>();
        for (Map.Entry<String, JsonNode> entry : files.properties()) {
            paths.add(entry.getKey());
        }
        for (Map.Entry<String, JsonNode> entry : production.properties()) {
            String path = entry.getValue().path("file").asString("");
            if (!path.isBlank()) {
                paths.add(path);
            }
        }
        return new IndexTable(root.path("module").asString(""), paths);
    }

    /**
     * The watched inputs: the module's four roots and POM, plus the source root of every file the index names.
     *
     * <p>Only directories that exist are watched. A new root that appears after a publish changes the input
     * set, which the decision reports as a full run rather than silently not watching it.</p>
     */
    private Inputs inputsFor(IndexTable index) {
        Set<Path> roots = new TreeSet<>();
        for (String candidate : MODULE_ROOTS) {
            Path root = moduleDir.resolve(candidate);
            if (Files.isDirectory(root)) {
                roots.add(root);
            }
        }
        for (String file : index.paths()) {
            Path root = sourceRootOf(file);
            if (root != null && Files.isDirectory(root)) {
                roots.add(root);
            }
        }

        Set<String> rootNames = new TreeSet<>();
        for (Path root : roots) {
            String name = relative(root);
            if (name != null) {
                rootNames.add(name);
            }
        }
        Set<String> fileNames = new TreeSet<>();
        Path pom = moduleDir.resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            String name = relative(pom);
            if (name != null) {
                fileNames.add(name);
            }
        }
        return new Inputs(List.copyOf(rootNames), List.copyOf(fileNames));
    }

    /** The source root of a repo-relative indexed file, or {@code null} when no root marker matches. */
    private Path sourceRootOf(String repoRelativeFile) {
        String value = repoRelativeFile.replace('\\', '/');
        for (String marker : SOURCE_ROOTS) {
            String needle = marker + "/";
            int at = value.indexOf(needle);
            if (at < 0) {
                continue;
            }
            String prefix = at == 0 ? "" : value.substring(0, at);
            if (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            Path root = prefix.isEmpty() ? repoRoot : repoRoot.resolve(prefix);
            return root.resolve(marker).normalize();
        }
        return null;
    }

    /** A path's repo-relative, {@code /}-separated name, or {@code null} when it is outside the root. */
    private String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(repoRoot)) {
            return null;
        }
        return repoRoot.relativize(normalized).toString().replace('\\', '/');
    }

    /** Every watched file, named repo-relative, sorted by name. */
    private List<FsMetaFile> scan(Inputs inputs) throws IOException {
        Map<String, FsMetaFile> files = new TreeMap<>(FsMetaFormat.NAME_ORDER);
        for (String rootName : inputs.roots()) {
            Path root = repoRoot.resolve(rootName);
            for (FsMetaFile file : FsMetaScanner.scan(root, SCAN_OPTIONS)) {
                String name = rootName + "/" + file.name();
                files.putIfAbsent(name, renamed(file, name));
            }
        }
        for (String fileName : inputs.files()) {
            Path file = repoRoot.resolve(fileName);
            FsMetaScanner.scanFile(repoRoot, file, SCAN_OPTIONS)
                    .ifPresent(scanned -> files.putIfAbsent(fileName, renamed(scanned, fileName)));
        }
        return new ArrayList<>(files.values());
    }

    /** A scanned file under the name the combined state stores it by. */
    private static FsMetaFile renamed(FsMetaFile file, String name) {
        return new FsMetaFile(name, file.mtimeNs(), file.sizeBytes(), file.contentHash(), file.flags());
    }

    /**
     * The scanned files as a validated in-memory pair, for {@link FsMetaDiff#between}. Nothing is written: the
     * alternative, publishing before deciding, would turn a no-op into a write.
     */
    private FsMetaPair pairOf(List<FsMetaFile> files) {
        List<FsMetaFile> ordered = new ArrayList<>(files);
        ordered.sort(java.util.Comparator.comparing(FsMetaFile::name, FsMetaFormat.NAME_ORDER));
        List<String> names = new ArrayList<>(ordered.size());
        List<FsMetaEntry> entries = new ArrayList<>(ordered.size());
        long offset = 0;
        for (FsMetaFile file : ordered) {
            names.add(file.name());
            entries.add(file.toEntry(offset));
            offset += FsMetaCodec.utf8Length(file.name()) + 1L;
        }
        byte[] txt = FsMetaCodec.encodeNames(names);
        byte[] meta = FsMetaCodec.encodeEntries(entries);
        FsMetaConfig config = FsMetaConfig.forPublish(entries.size(), meta, txt, 0L,
                FsMetaHashMode.TEXT, FS_META_WRITER, repoRoot.toString(), "", FsMetaConfig.now());
        return FsMetaPair.of(meta, txt, config);
    }

    private static boolean isPom(String path) {
        return path.equals("pom.xml") || path.endsWith("/pom.xml");
    }

    private static String changeCount(int added, int removed, int modified) {
        List<String> parts = new ArrayList<>(3);
        if (modified > 0) {
            parts.add(modified + " modified");
        }
        if (added > 0) {
            parts.add(added + " added");
        }
        if (removed > 0) {
            parts.add(removed + " removed");
        }
        return parts.isEmpty() ? "the test-affected inputs" : String.join(", ", parts) + " test input file(s)";
    }

    /** The index path as the decision reports it: repo-relative when it is under the root. */
    private String display(Path file) {
        String name = relative(file);
        return name == null ? file.toString().replace('\\', '/') : name;
    }

    /** Atomic write through a same-directory temporary, so a reader never sees half a sidecar. */
    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, content);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String pretty(JsonNode root) {
        return MetadataJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    /** An exception's message, or its class name when it has none. */
    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : oneLine(message);
    }

    private static String oneLine(String message) {
        String line = message.replace('\r', ' ').replace('\n', ' ').strip();
        return line.length() > 300 ? line.substring(0, 300) + "..." : line;
    }

    private static final String USAGE = """
            TestRunState - the disposable test-run state behind scripts/test-affected.js (plan T4)

              --root <dir>                  repository root the state's names are relative to (default: cwd)
              --module-dir <dir>            the test module (default: the current directory)
              --index <file>                the generated tests.json (default: <module-dir>/.jcodebuddy/index/tests.json)
              --state-dir <dir>             the state directory (default: <module-dir>/.jcodebuddy/cache/test-affected)
              --publish                     publish the current tree after a successful run
              --selected <a,b>              the tests the successful run selected (repeatable; for --publish)
              --expect-index-checksum <h>   refuse to publish when the index changed while the tests ran
              --json                        machine-readable output (default: a short human summary)
              --help                        this text

            A missing, corrupt or foreign state is a conservative full (all-unit) decision, never an error.
            It writes nothing unless --publish is given, and --publish is the caller's promise that the run
            succeeded.""".stripTrailing();

    /** The CLI. Returns the process exit code: 0 done, 1 an I/O failure, 2 a usage error. */
    public static int run(String[] args) {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path moduleDir = Path.of("").toAbsolutePath().normalize();
        Path index = null;
        Path stateDir = null;
        boolean publish = false;
        boolean json = false;
        String expectedChecksum = null;
        List<String> selected = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> {
                    System.out.println(USAGE);
                    return 0;
                }
                case "--json" -> json = true;
                case "--publish" -> publish = true;
                case "--root", "--module-dir", "--index", "--state-dir", "--selected",
                        "--expect-index-checksum" -> {
                    if (i + 1 >= args.length) {
                        return usageError(arg + " needs a value");
                    }
                    String value = args[++i];
                    switch (arg) {
                        case "--root" -> root = Path.of(value);
                        case "--module-dir" -> moduleDir = Path.of(value);
                        case "--index" -> index = Path.of(value);
                        case "--state-dir" -> stateDir = Path.of(value);
                        case "--expect-index-checksum" -> expectedChecksum = value;
                        default -> {
                            for (String part : value.split(",")) {
                                if (!part.isBlank()) {
                                    selected.add(part.strip());
                                }
                            }
                        }
                    }
                }
                default -> {
                    return usageError("unexpected argument '" + arg + "'");
                }
            }
        }

        Path rootPath = root.toAbsolutePath().normalize();
        Path modulePath = moduleDir.isAbsolute() ? moduleDir.toAbsolutePath().normalize()
                : rootPath.resolve(moduleDir).normalize();
        Path indexPath = index == null
                ? modulePath.resolve(".jcodebuddy").resolve("index").resolve("tests.json")
                : (index.isAbsolute() ? index.toAbsolutePath().normalize()
                        : rootPath.resolve(index).normalize());

        try {
            TestRunState state = new TestRunState(rootPath, modulePath, indexPath,
                    stateDir == null ? stateDirectory(modulePath) : stateDir);
            if (publish) {
                state.publish(expectedChecksum, selected);
                System.out.print(json
                        ? publishJson(state, indexPath, selected)
                        : "test-run-state: published " + selected.size() + " selected test(s) to "
                                + state.directory() + "\n");
                return 0;
            }
            Decision decision = state.decide();
            System.out.print(json ? decision.toJson() : summary(decision));
            return 0;
        } catch (IOException | RuntimeException failure) {
            System.err.println("test-run-state: " + oneLine(message(failure)));
            return 1;
        }
    }

    private static String summary(Decision decision) {
        StringBuilder text = new StringBuilder();
        text.append("test-run-state: ").append(decision.mode().wire()).append(" - ").append(decision.reason())
                .append('\n');
        for (String changed : decision.changed()) {
            text.append("  changed ").append(changed).append('\n');
        }
        return text.toString();
    }

    private static String publishJson(TestRunState state, Path indexFile, List<String> selected) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("format", RUN_FORMAT);
        root.put("published", true);
        root.put("state", state.directory().toString());
        try {
            root.put("indexChecksum", ContentHash.of(indexFile));
        } catch (IOException unreadable) {
            root.put("indexChecksum", "");
        }
        var selectedNode = root.putArray("selected");
        new TreeSet<>(selected).forEach(selectedNode::add);
        return pretty(root);
    }

    private static int usageError(String message) {
        System.err.println("test-run-state: " + message);
        System.err.println(USAGE);
        return 2;
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }
}
