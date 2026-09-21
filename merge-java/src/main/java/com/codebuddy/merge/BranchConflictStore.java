// {@link com.codebuddy.merge.BranchConflictStore} Per-branch store of recorded conflict decisions.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Stores conflict decisions for one branch, on disk, so that the next time the
 * branch is brought up to date the same conflicts are resolved without asking
 * again.
 *
 * <h2>Layout</h2>
 * <pre>
 * .jcodebuddy/merge-history/&lt;branch&gt;/
 *   decisions/
 *     &lt;conflict-type&gt;-&lt;content-hash&gt;.json   one file per recorded decision
 * </pre>
 *
 * <p>The directory is per branch and is intended to be checked in: it is the
 * branch's memory of how it resolved recurring conflicts, and it is what turns a
 * repeated rebase from a chore into a no-op.
 *
 * <p>A decision is only consulted when the incoming conflict has the same
 * {@link ConflictSignature} - same type, same file, same shape of disagreement.
 * Anything else is treated as a new conflict, so a stale decision can never be
 * applied to code it was not made for.
 */
public class BranchConflictStore {

    /**
     * Create an instance-scoped store that never touches disk and shares nothing
     * with any other store.
     *
     * <p>Two resolvers can legitimately be built with the same branch name yet
     * must not influence each other: one may run a strict verification gate and
     * another a permissive one, and a decision recorded by the first must not be
     * replayed past the second's gate. Isolation comes from a branch name that
     * cannot collide, so the in-instance memoisation is preserved while
     * cross-instance communication is impossible.
     */
    public static BranchConflictStore instanceScoped() {
        String unique = INSTANCE_SCOPED_PREFIX + UUID.randomUUID();
        return new BranchConflictStore(unique,
            Path.of(System.getProperty("java.io.tmpdir"), "merge-java", unique), true);
    }

    /**
     * Create an in-memory store for a named branch: nothing is read or written on
     * disk, and its decisions are private to it.
     */
    public static BranchConflictStore inMemory(String branchName) {
        return new BranchConflictStore(branchName,
            Path.of(System.getProperty("java.io.tmpdir"), "merge-java", "in-memory"),
            true);
    }

    private static final String INSTANCE_SCOPED_PREFIX = "in-memory-";

    /**
     * Version of the decision-file format.
     *
     * <p>Written into every file and checked on load. An entry written by a newer
     * version is ignored and counted rather than guessed at, because a decision
     * that is misread is worse than one that is re-made: it would be replayed
     * silently against code it was never made for.
     */
    public static final int SCHEMA_VERSION = 1;

    /** Sub-directory holding the decision files. */
    public static final String DECISIONS_DIR = "decisions";

    /**
     * Branch name used when a caller never said which branch it is resolving.
     * Decisions for this sentinel are held in memory only: writing them would
     * scatter files under a meaningless directory and could replay a decision in
     * a context it was never made for.
     */
    public static final String UNKNOWN_BRANCH = "unknown";

    private final String branchName;
    private final Path historyRoot;
    private final Path decisionsDir;
    private final boolean inMemoryOnly;
    private final Map<ConflictSignature, ConflictResolution> decisions;
    private final List<String> loadDiagnostics = new ArrayList<>();

    /**
     * Entries skipped while loading, with the reason. A merge must not fail
     * because the history is imperfect, but silently ignoring a corrupt entry
     * would hide a real problem, so the reasons are kept for a caller to report.
     */
    public List<String> getLoadDiagnostics() {
        return List.copyOf(loadDiagnostics);
    }

    /**
     * Forget every decision older than the given instant.
     *
     * <p>The decision directory is checked in, so it accumulates: a decision whose
     * conflict has not been seen in months is dead weight in every diff. This is
     * the supported way to keep it bounded.
     *
     * @return the number of decisions removed
     */
    public int prune(Instant olderThan) {
        Objects.requireNonNull(olderThan, "olderThan");
        List<ConflictSignature> expired = new ArrayList<>();
        decisions.forEach((signature, resolution) -> {
            if (resolution.getResolvedAt().isBefore(olderThan)) {
                expired.add(signature);
            }
        });
        for (ConflictSignature signature : expired) {
            decisions.remove(signature);
            if (shouldPersist()) {
                deleteFile(signature);
            }
        }
        return expired.size();
    }

    /**
     * Forget every decision not seen for the given number of days.
     *
     * @return the number of decisions removed
     */
    public int pruneStale(Duration maxAge) {
        Objects.requireNonNull(maxAge, "maxAge");
        return prune(Instant.now().minus(maxAge));
    }

    /**
     * The oldest decision's timestamp, if any. Useful for a size guard in CI.
     */
    public Optional<Instant> oldestDecisionAt() {
        return decisions.values().stream()
            .map(ConflictResolution::getResolvedAt)
            .min(Instant::compareTo);
    }

    public BranchConflictStore(String branchName, Path historyRoot) {
        this(branchName, historyRoot, false);
    }

    /**
     * @param inMemoryOnly when true nothing is read from or written to disk, so
     *                     the store cannot influence or be influenced by other
     *                     runs. Used for pure, single-shot resolution.
     */
    public BranchConflictStore(String branchName, Path historyRoot, boolean inMemoryOnly) {
        this.branchName = branchName == null ? "unknown" : branchName;
        this.inMemoryOnly = inMemoryOnly;
        this.historyRoot = (historyRoot == null
            ? Path.of(".jcodebuddy", "merge-history", this.branchName)
            : historyRoot).toAbsolutePath();
        this.decisionsDir = this.historyRoot.resolve(DECISIONS_DIR);
        this.decisions = new ConcurrentHashMap<>();
        if (shouldLoad()) {
            load();
        }
    }

    public BranchConflictStore(Path historyRoot, String branchName) {
        this(branchName, historyRoot);
    }

    /**
     * True when this store keeps decisions in memory only.
     */
    public boolean isInMemoryOnly() {
        return inMemoryOnly;
    }

    /**
     * Record a decision for later replay. Only replayable resolutions are
     * stored; a one-off manual choice is not a policy and must not be replayed.
     */
    public void record(Conflict conflict, ConflictResolution resolution) {
        Objects.requireNonNull(conflict, "conflict");
        Objects.requireNonNull(resolution, "resolution");
        if (!resolution.isReplayable()) {
            return;
        }
        ConflictSignature signature = ConflictSignature.of(conflict);
        ConflictResolution stored = ConflictResolution.copyOf(resolution)
            .branchName(branchName)
            .build();
        decisions.put(signature, stored);
        if (shouldPersist()) {
            persist(signature, stored);
        }
    }

    /**
     * True when decisions belong on disk. Skipped for in-memory stores and for
     * the {@link #UNKNOWN_BRANCH} sentinel, where the decision still replays
     * within this instance but leaves nothing behind.
     */
    private boolean shouldPersist() {
        return !inMemoryOnly && !UNKNOWN_BRANCH.equals(branchName);
    }

    /**
     * True when decisions may be read back from disk.
     *
     * <p>An in-memory store must not load, because it is created per resolver
     * instance: loading would let a resolver whose decisions were never meant to
     * persist pick up decisions written by an unrelated earlier run - including
     * resolutions that had been recorded without going through that resolver's
     * verification gate.
     */
    private boolean shouldLoad() {
        return !inMemoryOnly && !UNKNOWN_BRANCH.equals(branchName);
    }

    /**
     * The decision previously recorded for this exact conflict, if any.
     */
    public Optional<ConflictResolution> findDecision(Conflict conflict) {
        Objects.requireNonNull(conflict, "conflict");
        return Optional.ofNullable(decisions.get(ConflictSignature.of(conflict)));
    }

    /**
     * The decision recorded for a signature.
     */
    public Optional<ConflictResolution> findDecision(ConflictSignature signature) {
        Objects.requireNonNull(signature, "signature");
        return Optional.ofNullable(decisions.get(signature));
    }

    /**
     * Every recorded decision, keyed by signature.
     */
    public Map<ConflictSignature, ConflictResolution> allDecisions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(decisions));
    }

    /**
     * Decisions recorded for one file.
     */
    public List<ConflictResolution> decisionsForFile(String filePath) {
        String normalised = filePath == null ? "" : filePath.replace('\\', '/');
        List<ConflictResolution> matching = new ArrayList<>();
        decisions.forEach((signature, resolution) -> {
            if (signature.filePath().equals(normalised)) {
                matching.add(resolution);
            }
        });
        return matching;
    }

    /**
     * Forget one decision. Used when a recorded choice is superseded.
     */
    public boolean forget(ConflictSignature signature) {
        ConflictResolution removed = decisions.remove(signature);
        if (removed != null) {
            deleteFile(signature);
            return true;
        }
        return false;
    }

    /**
     * Forget everything recorded for this branch.
     */
    public void clear() {
        decisions.clear();
        if (Files.isDirectory(decisionsDir)) {
            try (Stream<Path> files = Files.list(decisionsDir)) {
                files.filter(path -> path.toString().endsWith(".json")).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Could not delete " + path, e);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException("Could not list " + decisionsDir, e);
            }
        }
    }

    public String getBranchName() {
        return branchName;
    }

    public Path getHistoryRoot() {
        return historyRoot;
    }

    public Path getDecisionsDir() {
        return decisionsDir;
    }

    public int size() {
        return decisions.size();
    }

    /**
     * Read every decision file under the branch directory. Unreadable files are
     * skipped rather than failing the merge: a corrupt history entry must not
     * block work.
     */
    private void load() {
        if (!Files.isDirectory(decisionsDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(decisionsDir)) {
            files.filter(path -> path.toString().endsWith(".json")).forEach(path -> {
                try {
                    DecisionFile.read(Files.readString(path, StandardCharsets.UTF_8))
                        .ifPresentOrElse(
                            entry -> decisions.put(entry.signature(), entry.resolution()),
                            () -> loadDiagnostics.add("ignored unreadable or unsupported decision: "
                                + path.getFileName()));
                } catch (IOException | RuntimeException ex) {
                    // A corrupt entry must not fail a merge: the conflict is simply
                    // resolved again rather than mis-resolved. The reason is kept so
                    // a caller can surface it.
                    loadDiagnostics.add("ignored " + path.getFileName() + ": " + ex.getMessage());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read history at " + decisionsDir, e);
        }
    }

    private void persist(ConflictSignature signature, ConflictResolution resolution) {
        try {
            Files.createDirectories(decisionsDir);
            Path target = decisionsDir.resolve(signature.toFileName() + ".json");
            Path temp = decisionsDir.resolve(signature.toFileName() + ".json.tmp");
            Files.writeString(temp, DecisionFile.write(signature, resolution), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist the decision for " + signature, e);
        }
    }

    private void deleteFile(ConflictSignature signature) {
        Path target = decisionsDir.resolve(signature.toFileName() + ".json");
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + target, e);
        }
    }

    /**
     * On-disk representation of one recorded decision.
     */
    record DecisionEntry(ConflictSignature signature, ConflictResolution resolution) {
    }

    /**
     * Minimal JSON reader/writer for the decision files.
     *
     * <p>Written by hand rather than pulling in a JSON dependency: the shape is
     * tiny and fixed, and the module deliberately avoids making callers adopt a
     * serialization library.
     */
    static final class DecisionFile {

        private DecisionFile() {
        }

        static String write(ConflictSignature signature, ConflictResolution resolution) {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n");
            json.append("  \"conflictType\": ").append(quote(signature.conflictType().name())).append(",\n");
            json.append("  \"filePath\": ").append(quote(signature.filePath())).append(",\n");
            json.append("  \"contentHash\": ").append(quote(signature.contentHash())).append(",\n");
            json.append("  \"branchName\": ").append(quote(resolution.getBranchName())).append(",\n");
            json.append("  \"resolutionStrategy\": ")
                .append(quote(resolution.getResolutionStrategy().name())).append(",\n");
            json.append("  \"kind\": ").append(quote(resolution.getKind().name())).append(",\n");
            json.append("  \"sticky\": ").append(resolution.isSticky()).append(",\n");
            json.append("  \"resolvedAt\": ").append(quote(resolution.getResolvedAt().toString())).append(",\n");
            json.append("  \"conflictId\": ").append(quote(resolution.getConflictId())).append(",\n");
            json.append("  \"explanation\": ").append(quote(resolution.getExplanation())).append(",\n");
            json.append("  \"resolvedCode\": ").append(quote(resolution.getResolvedCode())).append("\n");
            json.append("}\n");
            return json.toString();
        }

        /**
         * Parse a decision file. Returns empty when required fields are absent
         * or the recorded resolution is not replayable.
         */
        static Optional<DecisionEntry> read(String json) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String rawLine : json.split("\n")) {
                String line = rawLine.trim();
                if (!line.startsWith("\"") || !line.contains(":")) {
                    continue;
                }
                int separator = line.indexOf(':');
                String key = unquote(line.substring(0, separator).trim());
                String value = line.substring(separator + 1).trim();
                if (value.endsWith(",")) {
                    value = value.substring(0, value.length() - 1).trim();
                }
                fields.put(key, unquote(value));
            }

            String typeName = fields.get("conflictType");
            String filePath = fields.get("filePath");
            String contentHash = fields.get("contentHash");
            String strategyName = fields.get("resolutionStrategy");
            if (typeName == null || filePath == null || contentHash == null || strategyName == null) {
                return Optional.empty();
            }

            // A missing version is treated as the current one so that files
            // written before versioning are still readable; an unknown future
            // version is refused rather than misread.
            String version = fields.get("schemaVersion");
            if (version != null) {
                try {
                    int parsedVersion = Integer.parseInt(version.trim());
                    if (parsedVersion > SCHEMA_VERSION) {
                        return Optional.empty();
                    }
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }

            ConflictType type;
            ConflictResolution.ResolutionStrategy strategy;
            try {
                type = ConflictType.valueOf(typeName);
                strategy = ConflictResolution.ResolutionStrategy.valueOf(strategyName);
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }

            ConflictSignature signature = new ConflictSignature(type, filePath, contentHash);

            ConflictResolution resolution = ConflictResolution.builder()
                .filePath(filePath)
                .type(type)
                .resolvedCode(fields.getOrDefault("resolvedCode", ""))
                .resolutionStrategy(strategy)
                .kind(parseKind(fields.get("kind"), strategy))
                .sticky(Boolean.parseBoolean(fields.getOrDefault("sticky", "true")))
                .explanation(fields.getOrDefault("explanation", ""))
                .conflictId(fields.getOrDefault("conflictId", ""))
                .branchName(fields.getOrDefault("branchName", "unknown"))
                .resolvedAt(parseInstant(fields.get("resolvedAt")))
                .build();

            if (!resolution.isReplayable()) {
                return Optional.empty();
            }
            return Optional.of(new DecisionEntry(signature, resolution));
        }

        private static ConflictResolution.ResolutionKind parseKind(
            String value, ConflictResolution.ResolutionStrategy strategy) {
            if (value != null) {
                try {
                    return ConflictResolution.ResolutionKind.valueOf(value);
                } catch (IllegalArgumentException ignored) {
                    // Fall through to the strategy-derived default.
                }
            }
            return ConflictResolution.builder()
                .type(ConflictType.IMPORT_ADD)
                .resolvedCode("")
                .resolutionStrategy(strategy)
                .build()
                .getKind();
        }

        private static Instant parseInstant(String value) {
            if (value == null) {
                return null;
            }
            try {
                return Instant.parse(value);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static String quote(String value) {
            if (value == null) {
                return "null";
            }
            StringBuilder escaped = new StringBuilder(value.length() + 2);
            escaped.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> escaped.append("\\\"");
                    case '\\' -> escaped.append("\\\\");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            escaped.append(String.format("\\u%04x", (int) c));
                        } else {
                            escaped.append(c);
                        }
                    }
                }
            }
            escaped.append('"');
            return escaped.toString();
        }

        private static String unquote(String value) {
            if (value == null || value.equals("null")) {
                return null;
            }
            String trimmed = value.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            StringBuilder result = new StringBuilder(trimmed.length());
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c != '\\' || i + 1 >= trimmed.length()) {
                    result.append(c);
                    continue;
                }
                char next = trimmed.charAt(++i);
                switch (next) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case 'u' -> {
                        if (i + 4 < trimmed.length()) {
                            result.append((char) Integer.parseInt(trimmed.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> result.append(next);
                }
            }
            return result.toString();
        }
    }
}
