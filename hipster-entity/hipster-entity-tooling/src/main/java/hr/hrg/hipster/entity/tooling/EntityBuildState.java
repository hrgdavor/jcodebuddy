package hr.hrg.hipster.entity.tooling;

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The one-shot build state behind {@code --incremental} (plan {@code hipster-entity-mvp.md} E2).
 *
 * <p>This is the same {@code fs-meta} load/scan/diff/publish loop
 * {@code WatchedRegeneration} uses, applied once per generator invocation rather than to a live batch.
 * It answers one question before a pass does any work: <strong>does this tree need a pass at all?</strong>
 * The answer is one of three:</p>
 *
 * <ul>
 *   <li>{@link Mode#NOOP} — no {@code .java} file's content changed, the pass fingerprint still matches,
 *       and the state was published by a pass whose validation licenses skipping validation again. The
 *       caller returns without parsing, validating or writing anything.</li>
 *   <li>{@link Mode#INCREMENTAL} — existing {@code .java} files changed in place. <strong>E2 has no
 *       selective emission</strong>, so the caller runs the existing full generation pass and publishes
 *       a fresh state; E4 is the step that narrows this to the affected views.</li>
 *   <li>{@link Mode#FULL} — the state is missing/corrupt/foreign, the fingerprint changed, a file was
 *       added or removed, or the recorded validation does not allow a no-op. The existing pass runs
 *       unchanged.</li>
 * </ul>
 *
 * <h3>The state is a cache, never a correctness input</h3>
 * <p>Every failure path in {@link #decide} returns {@link Mode#FULL} with a reason; none throws and none
 * guesses. A missing, unreadable, invalid or unknown-format trio costs one full pass, exactly as a
 * fingerprint mismatch does (plan § 3.1). The state never changes an answer, only whether the reference
 * answer has to be recomputed.</p>
 *
 * <h3>Where it lives, and why not beside {@code src/main/java}</h3>
 * <p>The directory is {@code <marker>/.jcodebuddy/cache/entity-build/}, where {@code <marker>} is the
 * nearest {@link JcodebuddyDirectory#nearestMarker(Path) marker} at or above the report directory and
 * then the source root. That is DEC-026's rule rather than "resolve against the source root": a module's
 * source root is normally {@code <module>/src/main/java}, and putting the state beside it would create a
 * second, module-looking {@code .jcodebuddy/} one level too deep. A project with no marker at all gets
 * {@code <sourceRoot>/.jcodebuddy/cache/entity-build/} as the documented fallback, and the scan ignores
 * every {@code .jcodebuddy} directory so the state never indexes itself.</p>
 *
 * <h3>The fingerprint sidecar</h3>
 * <p>{@code fingerprint.txt} records the facts that change output without changing a source byte — the
 * generator identity/version and class source, the JDK major version, the normalized source root, the
 * metadata and {@code --java-out} directories, the package filter, every output-changing flag, the
 * validation policy, and the validation result of the pass that published the state. It is a plain
 * {@code key=value} text file: the trio's {@code writer}/{@code root} fields identify the format, while
 * this sidecar is what makes "the generator changed" or "a flag changed" a full pass instead of a stale
 * one. A missing or unparsable sidecar is a full pass.</p>
 *
 * <h3>One trio, published only after a successful pass</h3>
 * <p>{@link #publish} rescans the source root after the pass, so the generator's own output is already
 * part of the state, and writes the trio with the same {@code TEXT} hash mode, writer id and root this
 * class reads. It is never called from {@link #decide} and never on a failed pass: a half-published state
 * would make the next invocation's diff a guess.</p>
 */
final class EntityBuildState {

    /** The derived subtree below the nearest {@code .jcodebuddy/} marker (DEC-026's {@code cache/} rule). */
    static final String CACHE_DIRECTORY = "cache";

    /** The state directory's own name, beside {@code watch-fs-meta}. */
    static final String STATE_DIRECTORY = "entity-build";

    /** The fingerprint sidecar's file name. */
    static final String FINGERPRINT_FILE = "fingerprint.txt";

    /** The sidecar's format line; an older or unknown value is a full pass. */
    static final String FINGERPRINT_FORMAT = "jcodebuddy-entity-build-fingerprint/1";

    /** The {@code writer} field of the trio, which is how a foreign trio is recognised and rejected. */
    static final String FS_META_WRITER = "jcodebuddy-entity-build/1";

    /**
     * The scan options: text-aware hashing so a CRLF and an LF checkout agree, and every
     * {@code .jcodebuddy} directory skipped so the state cannot index itself.
     *
     * <p><strong>{@code .gitignore} is deliberately not honoured here.</strong> The generator reads
     * every {@code .java} under the source root, including one a project happens to ignore; a state that
     * skipped such a file would answer "nothing changed" for an edit the pass would have read. The
     * state's subject is what the pass reads, not what git tracks.</p>
     */
    private static final FsMetaScanOptions SCAN_OPTIONS = FsMetaScanOptions.defaults()
            .ignoring(JcodebuddyDirectory.DIR)
            .withoutGitignore();

    /** What the caller should do with this tree. */
    enum Mode {
        /** No pass: nothing changed and the state licenses skipping validation. */
        NOOP,
        /** Existing files changed in place; E2 runs the full pass (selective emission is E4). */
        INCREMENTAL,
        /** No usable state or a change the incremental pass cannot attribute; run the reference pass. */
        FULL
    }

    /**
     * The decision, with the reason a caller prints and the changed names it reports.
     *
     * @param mode    what to do
     * @param reason  one line a person can read; never null
     * @param changed the names whose <em>content</em> changed, relative to the source root and
     *                {@code /}-separated; {@code TOUCHED} entries are deliberately absent
     */
    record Decision(Mode mode, String reason, List<String> changed) {

        Decision {
            if (mode == null || reason == null) {
                throw new IllegalArgumentException("a decision needs a mode and a reason");
            }
            changed = List.copyOf(changed);
        }

        /** A full-pass decision; {@code changed} is empty when the reason is the state or the fingerprint. */
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

        /** The value the fingerprint sidecar carries. */
        String wire() {
            return wire;
        }
    }

    /**
     * Everything about an invocation that can change emitted source without changing a byte of it.
     *
     * <p>Paths are normalized only when the fingerprint is rendered, so a caller may pass the CLI's
     * relative arguments unchanged. Lists keep the caller's order: the CLI's order is the configuration,
     * and treating a reordered list as a different configuration can only cost a full pass.</p>
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

    private final Path sourceRoot;
    private final Path directory;

    /**
     * The state for one invocation.
     *
     * @param sourceRoot the tree the pass reads and writes; normalized here
     * @param directory  the state directory, normally from {@link #stateDirectory(Path, Path)}
     */
    EntityBuildState(Path sourceRoot, Path directory) {
        if (sourceRoot == null) {
            throw new IllegalArgumentException("a source root is required");
        }
        if (directory == null) {
            throw new IllegalArgumentException("a state directory is required");
        }
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        this.directory = directory.toAbsolutePath().normalize();
    }

    /** The state for a pass over {@code sourceRoot} whose reports go to {@code metadataOutput}. */
    static EntityBuildState forPass(Path sourceRoot, Path metadataOutput) {
        return new EntityBuildState(sourceRoot, stateDirectory(sourceRoot, metadataOutput));
    }

    /**
     * The state directory for a pass: the nearest marker at or above the report directory, then the source
     * root, then the documented {@code <sourceRoot>/.jcodebuddy} fallback.
     *
     * <p>The report directory is asked first for the reason {@code EntityMetadataGenerator.resolveModuleRoot}
     * gives: the pass already writes its metadata there, so the module that owns the report owns the state.
     * A report directory outside the module (a temp directory, a test) still resolves to the module's
     * marker, and only a tree with no marker anywhere falls back to the source root.</p>
     */
    static Path stateDirectory(Path sourceRoot, Path metadataOutput) {
        Path marker = JcodebuddyDirectory.nearestMarker(metadataOutput);
        if (marker == null) {
            marker = JcodebuddyDirectory.nearestMarker(sourceRoot);
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

    /** The state directory this instance reads and writes. */
    Path directory() {
        return directory;
    }

    /**
     * Decides what this invocation should do, before validation or parsing.
     *
     * <p>No branch throws: a state or a scan that cannot be read is a full pass with a reason, which is
     * the whole point of the state being a cache.</p>
     */
    Decision decide(Config config) {
        FsMetaLoad loaded;
        try {
            loaded = FsMetaPair.loadIfPresent(directory);
        } catch (IOException | RuntimeException unreadable) {
            return Decision.full("the build state at " + directory + " cannot be read ("
                    + message(unreadable) + ")", List.of());
        }
        if (loaded == null) {
            return Decision.full("no build state at " + directory, List.of());
        }
        if (!loaded.ok() || loaded.pair() == null) {
            return Decision.full("the build state at " + directory + " is not usable: "
                    + loaded.describe(), List.of());
        }
        FsMetaPair previous = loaded.pair();

        // A trio from another writer, with another hash contract or about another root is not "old": it
        // is a different state, and diffing it would compare hashes that do not mean the same thing.
        if (!FS_META_WRITER.equals(loaded.config().writer())) {
            return Decision.full("the build state at " + directory + " was written by '"
                    + loaded.config().writer() + "', not " + FS_META_WRITER, List.of());
        }
        if (!FsMetaHashMode.TEXT.wire().equals(loaded.config().hashNormalize())) {
            return Decision.full("the build state at " + directory + " was hashed with '"
                    + loaded.config().hashNormalize() + "', not " + FsMetaHashMode.TEXT.wire(), List.of());
        }
        if (!sourceRoot.toString().equals(loaded.config().root())) {
            return Decision.full("the build state describes the root '" + loaded.config().root()
                    + "', not " + sourceRoot, List.of());
        }

        // `--force` is an explicit request for the canonical text, so it may not be answered by "nothing
        // changed" — plan § 3.5 lists it with `--full` in the full-pass row.
        if (config.force()) {
            return Decision.full("--force requests a full pass", List.of());
        }

        Map<String, String> stored = readFingerprint();
        if (stored == null) {
            return Decision.full("the build-state fingerprint at " + directory.resolve(FINGERPRINT_FILE)
                    + " is missing, unreadable or not understood", List.of());
        }
        Map<String, String> current = fingerprint(config);
        String mismatch = firstMismatch(stored, current);
        if (mismatch != null) {
            return Decision.full("the pass fingerprint changed: " + mismatch
                    + " ('" + stored.getOrDefault(mismatch, "<absent>") + "' -> '"
                    + current.getOrDefault(mismatch, "<absent>") + "')", List.of());
        }

        FsMetaPair currentPair;
        try {
            currentPair = pairOf(scanJavaFiles());
        } catch (IOException | RuntimeException unreadable) {
            return Decision.full("the source root " + sourceRoot + " could not be scanned ("
                    + message(unreadable) + ")", List.of());
        }

        List<String> changed = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int modified = 0;
        int unknown = 0;
        for (FsMetaDiff.Change change : FsMetaDiff.between(previous, currentPair)) {
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
                case UNKNOWN -> {
                    unknown++;
                    changed.add(change.name());
                }
                // A same-content save is exactly what the state is for: the bytes did not change, so the
                // derived work is still valid. `WatchedRegeneration` filters TOUCHED the same way.
                case TOUCHED -> {
                }
            }
        }

        if (added > 0 || removed > 0 || unknown > 0) {
            return Decision.full("the source set changed: " + changes(added, removed, unknown)
                    + " (a new or removed type can change more than its own file)", List.copyOf(changed));
        }
        if (modified > 0) {
            return new Decision(Mode.INCREMENTAL,
                    modified + " existing source file(s) changed (E2 runs the existing full pass)",
                    List.copyOf(changed));
        }

        // Nothing changed. The state may license a no-op only when the pass that wrote it validated the
        // same policy cleanly; with the policy OFF nothing needed validating, which is the one exception
        // the plan states rather than an oversight (an absent `--validate` must stay usable).
        String validation = stored.get("validation");
        boolean validationClean = ValidationOutcome.CLEAN.wire().equals(validation);
        if (config.validationPolicy() != EntityMetadataGenerator.Policy.OFF && !validationClean) {
            return Decision.full("no source file changed, but the state records validation '"
                    + (validation == null ? "<absent>" : validation) + "' and policy "
                    + config.validationPolicy() + " permits a no-op only after a clean pass", List.of());
        }
        return new Decision(Mode.NOOP,
                "no source file changed and the pass fingerprint matches", List.of());
    }

    /**
     * Publishes the state after a successful pass, then records the pass's validation outcome.
     *
     * <p>The trio is written first and the fingerprint last: a fingerprint that claimed a state which was
     * not written would be worse than no fingerprint, because the next run would compare the current scan
     * against an older trio and call the difference a modify instead of the full pass it is. The trio
     * itself is published by {@link FsMetaWriter}, so its write order and re-read validation are the
     * format's, not this class's.</p>
     *
     * @param config     the invocation that just succeeded
     * @param validation what its validation policy did
     * @throws IOException when the trio or the sidecar cannot be written; the caller treats that as a
     *                     warning, because the generation itself already succeeded
     */
    void publish(Config config, ValidationOutcome validation) throws IOException {
        List<FsMetaFile> files = scanJavaFiles();
        // The sidecar must never be older than the trio it describes. If writing the new fingerprint
        // below fails, a sidecar left over from the previous pass could match this invocation's
        // configuration and record a clean validation for a tree it never validated. Removing it first
        // makes that failure cost a full pass instead, which is the direction the state is allowed to
        // be wrong in. (It is also why the trio is published before the sidecar, not after it.)
        Files.deleteIfExists(directory.resolve(FINGERPRINT_FILE));
        FsMetaLoad published = FsMetaWriter.publish(directory, files, FsMetaHashMode.TEXT,
                FS_META_WRITER, sourceRoot.toString());
        if (!published.ok()) {
            throw new IOException("the entity build state could not be validated after publishing: "
                    + published.describe());
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : fingerprint(config).entrySet()) {
            text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        text.append("validation=").append(validation.wire()).append('\n');
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(FINGERPRINT_FILE), text.toString(), StandardCharsets.UTF_8);
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

    /** Every {@code .java} file under the source root, as the scanner records it. */
    private List<FsMetaFile> scanJavaFiles() throws IOException {
        List<FsMetaFile> java = new ArrayList<>();
        for (FsMetaFile file : FsMetaScanner.scan(sourceRoot, SCAN_OPTIONS)) {
            // The generator reads only Java; a resource's bytes cannot change its output, so the state
            // records only what the pass actually reads.
            if (file.name().endsWith(".java")) {
                java.add(file);
            }
        }
        return java;
    }

    /**
     * The scanned files as a validated in-memory pair, for {@link FsMetaDiff#between}.
     *
     * <p>This is the same three steps {@link FsMetaWriter#publish} performs before writing — names,
     * entries, and the commit document with their sizes and hashes — except that nothing is written. The
     * alternative, publishing the current scan before deciding, would turn a no-op into a write and would
     * record files whose generation has not succeeded yet.</p>
     */
    private FsMetaPair pairOf(List<FsMetaFile> files) {
        List<FsMetaFile> ordered = new ArrayList<>(files);
        ordered.sort(Comparator.comparing(FsMetaFile::name, FsMetaFormat.NAME_ORDER));
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
                FsMetaHashMode.TEXT, FS_META_WRITER, sourceRoot.toString(), "", FsMetaConfig.now());
        return FsMetaPair.of(meta, txt, config);
    }

    /**
     * The invocation's fingerprint, in the order {@link #publish} writes it.
     *
     * <p>{@link EntityMetadataGenerator#generatorIdentity()} rather than the bare version: which artifact
     * the classes came from is part of "the same generator", and the identity line already distinguishes an
     * installed jar from the reactor's {@code target/classes}.</p>
     */
    private Map<String, String> fingerprint(Config config) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("format", FINGERPRINT_FORMAT);
        values.put("generator", EntityMetadataGenerator.generatorIdentity());
        values.put("jdk", Integer.toString(Runtime.version().feature()));
        values.put("sourceRoot", normalized(sourceRoot));
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
     * The stored fingerprint, or {@code null} when it is absent, unreadable or not a {@code key=value}
     * document. {@code null} is deliberately not an empty map: "no usable fingerprint" and "a fingerprint
     * that matches" must not be one step apart.
     */
    private Map<String, String> readFingerprint() {
        Path file = directory.resolve(FINGERPRINT_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    return null;
                }
                values.put(line.substring(0, equals), line.substring(equals + 1));
            }
            return values.isEmpty() ? null : values;
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * The first key whose value differs, including a key present on only one side.
     *
     * <p>{@code validation} is the one key the current fingerprint does not produce — it describes the
     * previous pass rather than this invocation — so it is compared by the no-op rule, not here.</p>
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
    private static String changes(int added, int removed, int unknown) {
        List<String> parts = new ArrayList<>(3);
        if (added > 0) {
            parts.add(added + " added");
        }
        if (removed > 0) {
            parts.add(removed + " removed");
        }
        if (unknown > 0) {
            parts.add(unknown + " without a usable fingerprint");
        }
        return String.join(", ", parts);
    }

    /** An exception's message, or its class name when it has none. */
    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
