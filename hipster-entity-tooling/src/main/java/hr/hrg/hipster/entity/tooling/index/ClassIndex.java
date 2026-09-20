package hr.hrg.hipster.entity.tooling.index;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import tools.jackson.databind.JsonNode;

import hr.hrg.hipster.entity.tooling.EntityMetadataGenerator;
import hr.hrg.hipster.entity.tooling.SourceReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The module's class index: one row per type the module compiles, keyed by fully qualified name
 * (DEC-029).
 *
 * <h3>What problem this solves</h3>
 * <p>The metadata recorded <em>where a field's locations are</em> and almost nothing about <em>the
 * classes those locations live in</em>: nothing knew a file's content identity, nothing knew a type's
 * kind or modifiers, and the only name a document carried for a file was an id derived from the set of
 * paths one pass happened to index. So every other generator had to re-walk and re-parse the tree to
 * answer "does this type exist, what is it, has it changed", and a path was stated in a table nobody
 * could join against by name.</p>
 *
 * <p>This class answers those questions from one JSON table:</p>
 *
 * <pre>{@code
 * // <module>/.jcodebuddy/index/classes.json
 * { "format": 1, "module": "hipster-entity-example", "sourceRoot": "src/main/java",
 *   "hash": { "algo": "wyhash64", "normalize": "lf", "of": "content" },
 *   "classes": {
 *     "com.example.Person": { "path": "src/main/java/com/example/Person.java", "kind": "interface",
 *       "modifiers": ["abstract", "public"], "line": 14, "depth": 0, "size": 1837,
 *       "checksum": "3f2c8d91a4b7e601",
 *       "hashCalculatedAt": "2026-05-14T09:12:33Z" } } }
 * }</pre>
 *
 * <h3>Why the key is the FQN and not an id</h3>
 * <p>An id — 4 bytes, a hash, a counter, a positional index — is a fact about <em>our pass</em>: it
 * changes when a file moves, when a row is inserted, when the id scheme's salt changes, and it means
 * nothing to a tool that cannot run our writer. A fully qualified name is a fact about the code, and it
 * is the one reference a Java IDE's rename refactor updates everywhere it appears, <strong>including in
 * text files that are not Java</strong>. Choosing a shorter key here would hand this project a class of
 * silent stale references that DEC-019 and DEC-022 exist to prevent. The cost is stated in DEC-029: an
 * FQN is longer than a hash, and a package or type rename changes the key.</p>
 *
 * <h3>Where it is written</h3>
 * <p>{@code <nearest .jcodebuddy above the report directory>/index/classes.json}; when the report
 * directory is not inside a {@code .jcodebuddy} at all — a temp directory in a test — it goes to
 * {@code <report directory>/index/classes.json}. That is why each document carries an emitted
 * {@code classIndex} pointer rather than assuming the layout.</p>
 *
 * <p>The directory's own {@code README.md} is part of DEC-026's layout, so it is written only when the
 * table actually lives in one. A fallback {@code <report dir>/index} is not a module layout, gets no
 * README, and therefore keeps "a report directory holds JSON only" true
 * ({@code GeneratorGuardTest}).</p>
 *
 * <h3>Timestamps</h3>
 * <p>{@code hashCalculatedAt} changes <strong>if and only if</strong> the row's checksum changes (or the
 * row is new): {@link #merge} carries the previous instant forward on unchanged content. That is what
 * makes the table byte-identical across two passes on an unchanged tree, and therefore committable and
 * reviewable in a diff. The clock is injectable so a test can assert it.</p>
 */
public final class ClassIndex {

    /** The table's own version. An unknown value means "do not trust this table" — never guess. */
    public static final int FORMAT = 1;

    /** The table's file name inside {@link #INDEX_DIR_NAME}. */
    public static final String FILE_NAME = "classes.json";

    /**
     * The file-modification sidecar beside the table: FQN to the file's last-modified time.
     *
     * <p>Separate from {@link #FILE_NAME} because of one hard fact: an {@code mtime} is a property of
     * <em>a working tree</em>, not of the source. It cannot survive a fresh checkout or a branch switch,
     * so writing it into the table would make a committed table differ from a regenerated one on every
     * machine — the opposite of what this table is for. Keeping it here lets the table itself stay a
     * content-addressed, portable artifact while a watcher still gets the cheap pre-filter (compare a
     * timestamp before hashing a file) that {@code plan.metadata-locations.md} § 2.3.2 asked for.</p>
     *
     * <p>Written by the same pass as the table, and never read as a correctness input: a missing or
     * mismatched sidecar costs a full hash, which is the only safe answer.</p>
     */
    public static final String MTIME_FILE_NAME = "mtimes.json";

    /** The directory name, a sibling of {@code metadata/} under a module's {@code .jcodebuddy/}. */
    public static final String INDEX_DIR_NAME = "index";

    /** The directory's README, required by DEC-026 for every {@code .jcodebuddy/} subfolder. */
    public static final String README_NAME = "README.md";

    /** The instant format of {@code hashCalculatedAt}: ISO-8601 UTC, second precision. */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    /**
     * The index directory's purpose, its table, and what is reserved but not built (DEC-029 § 2.5).
     *
     * <p>Tracked and human-owned: the pass creates this file when it is absent and never overwrites it,
     * so a human's edit survives every later pass (DEC-020's rule applied to this directory).</p>
     */
    static final String README_TEXT = """
            # The module index

            The module's **class index**, written by the `hipster-entity-generator` pass. Derived output,
            like `metadata/`: ignored by default, and opt-in together with the metadata subtree (see
            `../README.md`, "Track policy").

            ## `classes.json` — the class index

            One row per **type** the module compiles, keyed by its **fully qualified name**: the file that
            declares it, that file's content identity, and the type's kind and modifiers. This is the only
            place in the whole metadata tree where a source path is written — every `file` value in a
            `<Marker>.metadata.json` is an FQN that resolves here, which is what keeps a path from being
            repeated once per location.

            * `format` — the table's own version. A consumer that does not recognise it must refuse the
              table and say why, never guess.
            * `module`, `sourceRoot` — diagnostics, so the file is self-describing when opened directly.
            * `hash` — the content-identity contract: `algo` (the algorithm the checksums use),
              `normalize` (`lf`, i.e. CRLF is normalised to LF before hashing) and `of` (`content`). A
              table whose `hash` does not match the reading build must force a full pass, never a guess.
            * `classes` — FQN to row, sorted by key.

            A row carries:

            * `path` — the declaring file, module-relative with forward slashes, never absolute and never
              `..`.
            * `kind` — `class`, `interface`, `enum`, `record` or `annotation`.
            * `modifiers` — the declaration's Java modifier keywords, **sorted** (`public`, `protected`,
              `private`, `abstract`, `static`, `final`, `sealed`, `non-sealed`, `strictfp`). Sorted so a
              reordered modifier list is not a diff.
            * `enclosing`, `depth` — a member type's enclosing type and how deeply it is nested; `null`
              and `0` for a top-level type. A member type is its own row, so a document can reference
              `…PersonSummary.Record` and have it resolve.
            * `line` — the declaration's start line (its name), 1-based.
            * `generated` — `1` when the pass wrote the file (it carries a DEC-021 header), `0` otherwise.
            * `checksum` — 16 hex characters: `Wyhash64` over the file's bytes with CRLF normalised to LF.
              The same algorithm and the same normalisation the watch agent's own tables use, so the two
              agree about what "the same content" means.
            * `hashCalculatedAt` — the ISO-8601 UTC instant that checksum was **calculated**. It changes if
              and only if `checksum` changes (or the row is new), so it dates the content rather than the
              build: an unchanged tree produces a byte-identical table on every pass.
            * `size` — the file's size in bytes, as hashed.

            The file's last-modified time is **not** in this table: an `mtime` belongs to a working tree
            and cannot survive a checkout, so writing it here would make a committed table differ from a
            regenerated one on every machine. It lives in `mtimes.json` beside this file, which a watcher
            may use as a cheap pre-filter and which nothing treats as a correctness input.

            ### Why the key is a fully qualified name and not an id

            An id — a hash, a counter, a 4-byte value, a positional index — is a fact about the pass that
            wrote it: it changes when a file moves, when a row is inserted, or when the id scheme changes,
            and it means nothing to a tool that cannot run our writer. A fully qualified name is a fact
            about the code, and it is the one reference a Java IDE's rename refactor updates everywhere it
            appears — including in text files that are not Java. That is the trade this index makes, and
            the cost is stated: **a package or type rename changes the key**, so a reference in a document
            is stale until the next pass regenerates it. The pass reports that as a removed row plus an
            added row, and the document it rewrites carries the new name.

            ## `mtimes.json` — the working-tree sidecar

            FQN to the file's last-modified time in epoch milliseconds, written by the same pass as the
            table. It is **not** part of the class index, and deliberately so: an `mtime` is a property of a
            working tree rather than of the source, so a value committed to git would differ on every
            checkout while the content it describes did not. A watcher may compare it against the
            filesystem as a cheap pre-filter before hashing anything; nothing may treat it as a
            correctness input, and a missing or unreadable sidecar simply costs a full hash.

            ## Reserved, not implemented
            The directory is a *directory of tables* so these can be added without changing any consumer's
            contract:

            * `generator` — a header value naming the tooling revision and the flags that change output.
              Defined, not written until something consumes it: the same "options are part of the
              fingerprint" rule the `hash` header follows.
            * the dependency edges (`artifacts[].inputs` in a document) — per emitted artifact, the file
              ids it was generated *from*. That turns the index into the graph an incremental pass needs
              instead of one it must re-derive by parsing.
            * a pointer in a generated artifact's DEC-021 header naming the class index row it came from
              — the machine-readable form of DEC-019's navigability rule for generated Java.

            None of those is written today. `metadata/watch/<toolSet>/metadata.db` remains the watch
            agent's own cache and is not replaced by this directory.

            This README is tracked and human-owned: the pass creates it when it is absent and never
            overwrites it.
            """;

    private final Path indexDir;
    private final Path indexFile;
    private final Path reportDir;
    private final Path moduleRoot;
    private final String moduleName;
    private final String sourceRoot;
    private final boolean insideJcodebuddy;
    private final Clock clock;

    /** Every row, by FQN. Iterated in key order when written, so the file's order is the key sort. */
    private final Map<String, ClassRecord> byFqn = new TreeMap<>();

    /** Every row's FQN, by declaring path — what {@link #fqnForPath} and {@link #byPath} answer from. */
    private final Map<String, List<String>> byPath = new HashMap<>();

    /**
     * The table this pass read before it started, when there was one.
     *
     * <p>It is the reason {@code hashCalculatedAt} can date content rather than the build: the instant is
     * decided at {@link #write} time, once the row's checksum is known, by comparing against this
     * table.</p>
     */
    private ClassIndex previous;

    /**
     * The last-modified times this pass resolved, by FQN — the {@link #MTIME_FILE_NAME} sidecar's content.
     *
     * <p>A row's value is the previous sidecar's when the row's content is unchanged, and the file's own
     * {@code mtime} otherwise: the sidecar answers "when did this row's content last change, as this pass
     * saw it", which is what a pre-filter needs and is stable across passes that changed nothing.</p>
     */
    private final Map<String, Long> mtimes = new TreeMap<>();

    /** Paths this pass read or wrote that declared no type at all — reported, never given a row. */
    private final TreeSet<String> typeLessFiles = new TreeSet<>();

    private ClassIndex(Path indexDir, Path reportDir, Path moduleRoot, String moduleName, String sourceRoot,
                       boolean insideJcodebuddy, Clock clock) {
        this.indexDir = indexDir;
        this.indexFile = indexDir.resolve(FILE_NAME);
        this.reportDir = reportDir;
        this.moduleRoot = moduleRoot == null ? reportDir : moduleRoot;
        this.moduleName = moduleName == null ? "" : moduleName;
        this.sourceRoot = sourceRoot == null ? "" : sourceRoot;
        this.insideJcodebuddy = insideJcodebuddy;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Resolves the index location for a pass and creates the index.
     *
     * @param reportDir  the directory the marker documents are written to
     * @param moduleRoot the module the recorded paths are relative to
     * @param sourceRoot the module's source root, recorded as a diagnostic
     */
    public static ClassIndex forPass(Path reportDir, Path moduleRoot, Path sourceRoot) {
        return forPass(reportDir, moduleRoot, sourceRoot, Clock.systemUTC());
    }

    /** {@link #forPass(Path, Path, Path)} with an injectable clock, so a test can pin the timestamp. */
    public static ClassIndex forPass(Path reportDir, Path moduleRoot, Path sourceRoot, Clock clock) {
        Path jcodebuddy = nearestJcodebuddy(reportDir);
        Path dir;
        boolean inside;
        if (jcodebuddy != null) {
            dir = jcodebuddy.resolve(INDEX_DIR_NAME);
            inside = true;
        } else {
            dir = reportDir.resolve(INDEX_DIR_NAME);
            inside = false;
        }
        String module = moduleRoot == null || moduleRoot.getFileName() == null
                ? ""
                : moduleRoot.getFileName().toString();
        String relativeSourceRoot = moduleRoot == null || sourceRoot == null
                ? ""
                : moduleRelative(moduleRoot, sourceRoot);
        return new ClassIndex(dir, reportDir, moduleRoot, module, relativeSourceRoot, inside, clock);
    }

    /** The nearest ancestor (inclusive) named {@code .jcodebuddy}, or {@code null}. */
    private static Path nearestJcodebuddy(Path start) {
        if (start == null) {
            return null;
        }
        for (Path cursor = start.toAbsolutePath().normalize(); cursor != null; cursor = cursor.getParent()) {
            Path name = cursor.getFileName();
            if (name != null && EntityMetadataGenerator.JCODEBUDDY_DIR.equals(name.toString())
                    && Files.isDirectory(cursor)) {
                return cursor;
            }
        }
        return null;
    }

    /** {@code file} relative to {@code base}, with forward slashes; never absolute. */
    static String moduleRelative(Path base, Path file) {
        try {
            return base.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (IllegalArgumentException noRelativePath) {
            return file.getFileName() == null ? file.toString() : file.getFileName().toString();
        }
    }

    /**
     * Whether {@code path} is a module-relative path this index can hold.
     *
     * <p>The contract is DEC-028 § 3.2.2: forward slashes, never absolute, never {@code ..}, never
     * project-relative. A file outside the module has no such path, and the honest thing is to say so
     * rather than to write a {@code ..} path that means nothing in another module's table.</p>
     */
    public static boolean isModuleRelative(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")) {
            return false;
        }
        if (path.contains("\\") || path.matches("^[A-Za-z]:.*")) {
            return false;
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    // ── the writer's API ────────────────────────────────────────────────────────────────────────────

    /**
     * Registers one file's type declarations. Called once per file the pass read or wrote.
     *
     * <p>A file that declares no type (a {@code package-info.java}, a file of only comments)
     * contributes <strong>no row</strong>: the table's key space is types, and a file with none has no
     * key. It is remembered so {@link #typeLessFiles()} can report it rather than let it vanish.</p>
     *
     * <p><strong>Throws when two different paths claim one FQN.</strong> The language makes FQNs unique,
     * so a duplicate means two source roots declare the same type — the case F-44 in this repository is
     * the recorded cost of resolving by iteration order ("last one wins"). A pass that cannot say which
     * of two files it means must not produce a table that pretends there is only one.</p>
     */
    public void addTypes(String moduleRelativePath, List<TypeFacts> types, boolean generated) {
        requireModuleRelative(moduleRelativePath);
        if (types == null || types.isEmpty()) {
            typeLessFiles.add(moduleRelativePath);
            return;
        }
        // A file that declares a type is not type-less, even if an earlier call registered it as such.
        typeLessFiles.remove(moduleRelativePath);
        for (TypeFacts type : types) {
            put(new ClassRecord(type.fqn(), moduleRelativePath, type.kind(), type.modifiers(),
                    type.enclosing(), type.line(), type.depth(), generated, "", null, -1L));
        }
    }

    /**
     * Remembers a file the pass read, before (or instead of) its types are known.
     *
     * <p>The walk calls this for every file it visits, so a file that turns out to declare nothing — a
     * {@code package-info.java}, or one the parser could not read — is still named by the pass's report
     * rather than disappearing. {@link #addTypes} upgrades the entry when the file turns out to declare
     * types.</p>
     */
    public void addFile(String moduleRelativePath) {
        requireModuleRelative(moduleRelativePath);
        typeLessFiles.add(moduleRelativePath);
    }

    /** Registers one file's type declarations, reading them from a parsed unit. */
    public void addTypes(String moduleRelativePath, CompilationUnit unit, boolean generated) {
        List<TypeFacts> types = new ArrayList<>();
        for (TypeDeclaration<?> declaration : unit.getTypes()) {
            collectTypes(declaration, types);
        }
        addTypes(moduleRelativePath, types, generated);
    }

    /**
     * Registers a file the pass <strong>wrote</strong>, reading it back from disk.
     *
     * <p>A generated artifact is a file like any other in this table — it declares types, it has content
     * and it can change — so it gets the same treatment, with {@code generated = true}. The caller must
     * invoke this <em>after</em> the file exists, or its size and checksum describe the previous
     * revision.</p>
     */
    public void addGeneratedArtifact(String moduleRelativePath, Path fileOnDisk) throws IOException {
        requireModuleRelative(moduleRelativePath);
        CompilationUnit unit = SourceReader.readUnit(fileOnDisk);
        if (unit == null) {
            // A generated file this pass cannot read is a bug in an emitter rather than a fact about the
            // tree, and giving it a type-less row would hide it. The pass's own divergence reporter
            // already reports unreadable files, so here the file is simply remembered as type-less —
            // named by the table rather than silently absent from it.
            typeLessFiles.add(moduleRelativePath);
            return;
        }
        addTypes(moduleRelativePath, unit, true);
    }

    private void put(ClassRecord row) {
        ClassRecord previous = byFqn.get(row.fqn());
        if (previous != null && !previous.path().equals(row.path())) {
            throw new IllegalStateException("two files declare " + row.fqn() + ": "
                    + previous.path() + " and " + row.path()
                    + ". A fully qualified name is unique in Java, so this is two source roots holding "
                    + "the same type — resolve it (drop one root, or rename one type) rather than "
                    + "letting the pass pick a winner by iteration order.");
        }
        byFqn.put(row.fqn(), row);
        byPath.computeIfAbsent(row.path(), __ -> new ArrayList<>()).add(row.fqn());
    }

    private void requireModuleRelative(String moduleRelativePath) {
        if (!isModuleRelative(moduleRelativePath)) {
            throw new IllegalArgumentException(
                    "not a module-relative path, so it cannot be indexed: " + moduleRelativePath);
        }
    }

    /** One declaration's facts, member types included — the row set of one file. */
    private static void collectTypes(TypeDeclaration<?> declaration, List<TypeFacts> into) {
        into.add(TypeFacts.of(declaration));
        for (com.github.javaparser.ast.body.BodyDeclaration<?> member : declaration.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collectTypes(nested, into);
            }
        }
    }

    // ── the reader's API ────────────────────────────────────────────────────────────────────────────

    /** The row {@code fqn} names, or {@code null} when this module has no such type. */
    public ClassRecord row(String fqn) {
        return fqn == null ? null : byFqn.get(fqn);
    }

    /** Every row of the file at {@code moduleRelativePath}, in declaration order. */
    public List<ClassRecord> byPath(String moduleRelativePath) {
        List<String> fqns = byPath.get(moduleRelativePath);
        if (fqns == null) {
            return List.of();
        }
        List<ClassRecord> rows = new ArrayList<>(fqns.size());
        for (String fqn : fqns) {
            rows.add(byFqn.get(fqn));
        }
        return rows;
    }

    /** Every row, in FQN order — the order the table is written in. */
    public List<ClassRecord> rows() {
        return List.copyOf(byFqn.values());
    }

    /** How many types the table describes. */
    public int size() {
        return byFqn.size();
    }

    /**
     * The FQN a document should use for the file at {@code moduleRelativePath}: the type the file's
     * first top-level declaration introduces (its primary type).
     *
     * <p>A document names a <em>type</em>, and this is the one the file is named after — a Java file
     * holding {@code PersonSummary.Record} is {@code PersonSummary.java}, so its primary type is
     * {@code …PersonSummary}. A file that declares no type has no FQN to name; that is a bug in the
     * caller (a document referencing a file the table cannot describe), so it fails loudly rather than
     * returning a path that would put a filesystem path back into a document.</p>
     */
    public String fqnForPath(String moduleRelativePath) {
        if (moduleRelativePath == null || moduleRelativePath.isBlank()) {
            return null;
        }
        List<ClassRecord> rows = byPath(moduleRelativePath);
        if (rows.isEmpty()) {
            throw new IllegalStateException("the file has no type in this pass's class index, so no "
                    + "document can name it by FQN: " + moduleRelativePath + " (" + byFqn.size()
                    + " type(s) indexed in " + indexFile + "). Every file a document mentions must be "
                    + "registered with its types before toJson runs.");
        }
        ClassRecord primary = rows.get(0);
        for (ClassRecord candidate : rows) {
            if (candidate.depth() == 0) {
                return candidate.fqn();
            }
        }
        return primary.fqn();
    }

    /** Every path this pass read that declared no type at all — reported, never given a row. */
    public List<String> typeLessFiles() {
        return List.copyOf(typeLessFiles);
    }

    /** The table's file, {@code <index dir>/classes.json}. */
    public Path indexFile() {
        return indexFile;
    }

    /** The directory the table lives in. */
    public Path indexDir() {
        return indexDir;
    }

    /** The {@code module} header value. */
    public String moduleName() {
        return moduleName;
    }

    /** The {@code sourceRoot} header value. */
    public String sourceRoot() {
        return sourceRoot;
    }

    /**
     * The pointer every document of this pass carries: the table's path relative to the report
     * directory, with forward slashes ({@code ../../index/classes.json}).
     *
     * <p>Emitted rather than assumed, so a consumer that has only a document can find the table without
     * knowing the {@code .jcodebuddy} layout — and so the answer stays right for the fallback layout,
     * where the table sits inside the report directory instead of beside it.</p>
     */
    public String documentPointer() {
        return pointerFrom(reportDir);
    }

    /**
     * The pointer a document carries: the table's path relative to the document's own directory, with
     * forward slashes ({@code ../../index/classes.json}). Emitted rather than assumed so a consumer
     * never has to guess the {@code .jcodebuddy} layout.
     */
    public String pointerFrom(Path documentDir) {
        try {
            return documentDir.toAbsolutePath().normalize().relativize(indexFile.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (IllegalArgumentException noRelativePath) {
            return indexFile.getFileName().toString();
        }
    }

    // ── timestamps, merging and change reporting ────────────────────────────────────────────────────

    /** The instant {@code hashCalculatedAt} is written with, at second precision. */
    private String now() {
        return TIMESTAMP.format(Instant.now(clock));
    }

    /**
     * Remembers {@code previous} as the table this pass started from, and returns this index for
     * chaining.
     *
     * <p><strong>This is the only place a timestamp can be carried forward.</strong> The instant is not
     * decided here — at this point no row has a checksum yet, because a checksum is read from the file
     * during the walk and the row may well be about a file the pass has not written yet. It is decided in
     * {@link #write}, where the rule is exact and checkable: a row keeps the previous table's
     * {@code hashCalculatedAt} if and only if its {@code checksum} is unchanged.</p>
     *
     * <p>Without that rule every pass would rewrite every timestamp, the table would differ on every run,
     * and it could neither be committed nor reviewed in a diff.</p>
     */
    public ClassIndex merge(ClassIndex previous) {
        this.previous = previous;
        return this;
    }

    /** What changed between two tables. */
    public enum ChangeKind {
        /** The FQN is in this table and was not in the previous one. */
        ADDED,
        /** The FQN was in the previous table and is not in this one. */
        REMOVED,
        /** The FQN is in both and its file's checksum differs. */
        CONTENT,
        /** The same file appears with a different FQN in the two tables — a type or package rename. */
        RENAMED_TYPE
    }

    /**
     * One difference between two tables.
     *
     * @param kind the difference
     * @param fqn  the FQN in the newer table (for {@link ChangeKind#REMOVED}, the one that left)
     * @param path the declaring file
     */
    public record Change(ChangeKind kind, String fqn, String path) {
        @Override
        public String toString() {
            return kind.name().toLowerCase(java.util.Locale.ROOT) + " " + fqn + " (" + path + ")";
        }
    }

    /**
     * Every difference between {@code previous} and this table, in FQN order — the kernel an incremental
     * pass needs, answered without reading the tree.
     *
     * <p>{@code kind}, {@code modifiers} and {@code generated} are deliberately <em>not</em> change
     * signal of their own: a change to any of them also changes the file's content, which
     * {@link ChangeKind#CONTENT} already reports.</p>
     */
    public List<Change> changedSince(ClassIndex previous) {
        if (previous == null) {
            return List.of();
        }
        List<Change> changes = new ArrayList<>();
        for (ClassRecord current : byFqn.values()) {
            ClassRecord old = previous.row(current.fqn());
            if (old == null) {
                changes.add(new Change(ChangeKind.ADDED, current.fqn(), current.path()));
            } else if (!old.checksum().isBlank() && !current.checksum().isBlank()
                    && !old.checksum().equals(current.checksum())) {
                changes.add(new Change(ChangeKind.CONTENT, current.fqn(), current.path()));
            } else if (!old.path().equals(current.path())) {
                changes.add(new Change(ChangeKind.RENAMED_TYPE, current.fqn(), current.path()));
            }
        }
        for (ClassRecord old : previous.rows()) {
            if (row(old.fqn()) == null) {
                changes.add(new Change(ChangeKind.REMOVED, old.fqn(), old.path()));
            }
        }
        changes.sort((a, b) -> {
            int byFqn = a.fqn().compareTo(b.fqn());
            return byFqn != 0 ? byFqn : a.kind().compareTo(b.kind());
        });
        return changes;
    }

    /** A one-line summary of {@link #changedSince}, for the pass's note. */
    public static String summarize(List<Change> changes) {
        int added = 0;
        int removed = 0;
        int content = 0;
        int renamed = 0;
        for (Change change : changes) {
            switch (change.kind()) {
                case ADDED -> added++;
                case REMOVED -> removed++;
                case CONTENT -> content++;
                case RENAMED_TYPE -> renamed++;
            }
        }
        return added + " added, " + content + " content change(s), " + removed + " removed, "
                + renamed + " renamed";
    }

    // ── writing ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Writes {@code classes.json} (and, in a module, the directory's README when it is absent).
     *
     * <p>Every row's file facts are resolved here, from the files the pass registered: the checksum, the
     * size and the mtime are read once, and {@code hashCalculatedAt} is stamped only where
     * {@link #merge} did not carry an older instant forward. Deterministic bytes for an unchanged tree:
     * rows in FQN order, no pass-level timestamp, no walk order.</p>
     *
     * @throws IOException when the table cannot be written, or when a registered file cannot be read —
     *                     a row with an invented checksum would be a claim about content nobody looked at
     */
    public void write() throws IOException {
        stampFileFacts();
        writeReadmeIfAbsent();
        Files.createDirectories(indexDir);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"format\": ").append(FORMAT).append(",\n");
        sb.append("  \"module\": \"").append(EntityMetadataGenerator.escapeJson(moduleName)).append("\",\n");
        sb.append("  \"sourceRoot\": \"").append(EntityMetadataGenerator.escapeJson(sourceRoot)).append("\",\n");
        sb.append("  \"hash\": { \"algo\": \"").append(ContentHash.ALGO)
                .append("\", \"normalize\": \"").append(ContentHash.NORMALIZE)
                .append("\", \"of\": \"content\" },\n");
        sb.append("  \"classes\": {");
        if (byFqn.isEmpty()) {
            sb.append("}\n");
        } else {
            sb.append("\n");
            int index = 0;
            for (ClassRecord row : byFqn.values()) {
                appendRow(sb, row);
                sb.append(index < byFqn.size() - 1 ? ",\n" : "\n");
                index++;
            }
            sb.append("  }\n");
        }
        sb.append("}\n");
        Files.writeString(indexFile, sb.toString(), StandardCharsets.UTF_8);
        writeMtimes();
    }

    /**
     * Writes the {@code mtimes.json} sidecar: FQN to last-modified time, in key order.
     *
     * <p>Separate from the table because an {@code mtime} belongs to a working tree and cannot be
     * committed; see {@link #MTIME_FILE_NAME}.</p>
     */
    private void writeMtimes() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"format\": ").append(FORMAT).append(",\n");
        sb.append("  \"of\": \"").append(FILE_NAME).append("\",\n");
        sb.append("  \"mtimes\": {");
        if (mtimes.isEmpty()) {
            sb.append("}\n");
        } else {
            sb.append("\n");
            int index = 0;
            for (Map.Entry<String, Long> entry : mtimes.entrySet()) {
                sb.append("    \"").append(EntityMetadataGenerator.escapeJson(entry.getKey()))
                        .append("\": ").append(entry.getValue());
                sb.append(index < mtimes.size() - 1 ? ",\n" : "\n");
                index++;
            }
            sb.append("  }\n");
        }
        sb.append("}\n");
        Files.writeString(indexDir.resolve(MTIME_FILE_NAME), sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Resolves every row's file facts from disk, and decides what is carried forward from the previous
     * table.
     *
     * <p>The rule, applied per row: <strong>if the checksum is unchanged, {@code hashCalculatedAt} and the
     * sidecar's {@code mtime} are the previous table's</strong>; otherwise both are this pass's.</p>
     *
     * <p>{@code hashCalculatedAt} is the requirement — it dates the content, not the build, or the table
     * would differ on every pass and could not be committed.</p>
     *
     * <p>{@code mtime} follows it for the same reason, and that is a deliberate trade worth stating: a
     * generated artifact is rewritten on every pass, so its filesystem mtime moves even though its bytes
     * do not, and keeping the fresh value would make the sidecar differ on every run for files that did
     * not change. The value the sidecar carries therefore means "when this row's <em>content</em> last
     * changed, as this pass saw it", which is exactly what a pre-filter needs.</p>
     */
    private void stampFileFacts() throws IOException {
        for (Map.Entry<String, ClassRecord> entry : byFqn.entrySet()) {
            ClassRecord row = entry.getValue();
            Path file = moduleRoot.resolve(row.path());
            if (!Files.isRegularFile(file)) {
                throw new IOException("the class index cannot describe " + row.fqn() + ": its file is "
                        + "not there: " + file + ". A row whose size and checksum describe a file nobody "
                        + "read is worse than no row.");
            }
            String checksum = ContentHash.of(file);
            long size = Files.size(file);
            ClassRecord old = previous == null ? null : previous.row(row.fqn());
            boolean unchanged = old != null && checksum.equals(old.checksum())
                    && old.hashCalculatedAt() != null && !old.hashCalculatedAt().isBlank();
            Long oldMtime = previous == null ? null : previous.mtimeOf(row.fqn());
            entry.setValue(row.withFileFacts(checksum,
                    unchanged ? old.hashCalculatedAt() : now(), size));
            mtimes.put(row.fqn(), unchanged && oldMtime != null
                    ? oldMtime
                    : Files.getLastModifiedTime(file).toMillis());
        }
    }

    /** The sidecar's value for {@code fqn}, or {@code null} when this table has none. */
    public Long mtimeOf(String fqn) {
        return mtimes.get(fqn);
    }

    /** The module root every row's path is relative to — the root the pass resolved. */
    public Path moduleRoot() {
        return moduleRoot;
    }

    private static void appendRow(StringBuilder sb, ClassRecord row) {
        // Key order is fixed and chosen so the fields that repeat a fact already visible in the row are
        // left out when they carry no information: `enclosing: null` and `generated: 0` are the common
        // case for a top-level hand-written type (by far the majority of rows), and writing them would
        // add a line per row to a table whose whole purpose is to be read.
        sb.append("    \"").append(EntityMetadataGenerator.escapeJson(row.fqn())).append("\": { \"path\": \"")
                .append(EntityMetadataGenerator.escapeJson(row.path())).append("\", \"kind\": \"")
                .append(EntityMetadataGenerator.escapeJson(row.kind())).append("\", \"modifiers\": [");
        for (int i = 0; i < row.modifiers().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("\"").append(EntityMetadataGenerator.escapeJson(row.modifiers().get(i))).append("\"");
        }
        sb.append("], \"line\": ").append(row.line()).append(", \"depth\": ").append(row.depth());
        if (row.generated()) {
            sb.append(", \"generated\": 1");
        }
        if (row.enclosing() != null) {
            sb.append(", \"enclosing\": \"").append(EntityMetadataGenerator.escapeJson(row.enclosing())).append("\"");
        }
        sb.append(", \"size\": ").append(row.size())
                .append(", \"checksum\": \"").append(row.checksum()).append("\", \"hashCalculatedAt\": \"")
                .append(row.hashCalculatedAt()).append("\" }");
    }

    /**
     * Creates the directory's README when it is absent, and <strong>never</strong> overwrites one.
     *
     * <p>{@code .jcodebuddy/index/README.md} is a tracked, human-owned file: rewriting it on every pass
     * would silently revert a human's edit, which is exactly what DEC-020 forbids for generated blocks.
     * The pass owns {@code classes.json} and only seeds the README.</p>
     */
    private void writeReadmeIfAbsent() throws IOException {
        if (!insideJcodebuddy) {
            // A fallback <report dir>/index is not a module layout, so it is not a DEC-026 subfolder and
            // needs no README — which is also what keeps "a report directory holds JSON only" true.
            return;
        }
        Files.createDirectories(indexDir);
        Path readme = indexDir.resolve(README_NAME);
        if (Files.exists(readme)) {
            return;
        }
        Files.writeString(readme, README_TEXT, StandardCharsets.UTF_8);
    }

    // ── reading ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Reads a table written by {@link #write}, or returns {@code null} when there is none or it cannot be
     * trusted.
     *
     * <p>Lenient about unknown extra keys, strict about {@code format} and the {@code hash} contract: a
     * table whose version or algorithm this build does not recognise must force a full pass rather than
     * be half-believed. The reason is carried in {@link Readers} so a caller can report it.</p>
     */
    public static ClassIndex read(Path indexFile, Path reportDir, Path moduleRoot, Path sourceRoot) {
        return read(indexFile, reportDir, moduleRoot, sourceRoot, null);
    }

    /** {@link #read(Path, Path, Path, Path)} that records why a table was refused, when it was. */
    public static ClassIndex read(Path indexFile, Path reportDir, Path moduleRoot, Path sourceRoot,
                                  List<String> problems) {
        if (indexFile == null || !Files.isRegularFile(indexFile)) {
            if (problems != null) {
                problems.add("no class index at " + indexFile);
            }
            return null;
        }
        String text;
        try {
            text = Files.readString(indexFile, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            if (problems != null) {
                problems.add("the class index at " + indexFile + " could not be read: " + unreadable.getMessage());
            }
            return null;
        }
        ClassIndex parsed = parse(text, reportDir, moduleRoot, sourceRoot, problems);
        return parsed;
    }

    /**
     * Reads the {@code (FQN → module-relative path)} map a document's references resolve through.
     *
     * <p>This is the shape {@code EntityMetadataGenerator.fromJson} consumes: the model keeps paths, the
     * JSON carries FQNs, and this is the one conversion table between them. Reading the DEC-028
     * {@code files.json} as well is the one-revision legacy path (DEC-029 § "legacy reading"): a document
     * written before this table existed still resolves.</p>
     */
    public static Map<String, String> pathsByFqn(Path indexFile) {
        Map<String, String> paths = new LinkedHashMap<>();
        if (indexFile == null || !Files.isRegularFile(indexFile)) {
            return paths;
        }
        ClassIndex read = read(indexFile, indexFile.getParent(), indexFile.getParent(), null, null);
        if (read == null) {
            return paths;
        }
        for (ClassRecord row : read.rows()) {
            paths.put(row.fqn(), row.path());
        }
        return paths;
    }

    /** The DEC-028 readable-id table, for the one-revision legacy read. */
    public static Map<String, String> legacyPathsById(Path filesJson) {
        Map<String, String> paths = new LinkedHashMap<>();
        if (filesJson == null || !Files.isRegularFile(filesJson)) {
            return paths;
        }
        try {
            JsonNode root = EntityMetadataGenerator.OBJECT_MAPPER.readTree(
                    Files.readString(filesJson, StandardCharsets.UTF_8));
            for (Map.Entry<String, JsonNode> entry : root.path("files").properties()) {
                paths.put(entry.getKey(), entry.getValue().asText());
            }
        } catch (IOException | RuntimeException unreadable) {
            return paths;
        }
        return paths;
    }

    private static ClassIndex parse(String text, Path reportDir, Path moduleRoot, Path sourceRoot,
                                    List<String> problems) {
        JsonNode root;
        try {
            root = EntityMetadataGenerator.OBJECT_MAPPER.readTree(text);
        } catch (RuntimeException notJson) {
            if (problems != null) {
                problems.add("the class index is not valid JSON: " + notJson.getMessage());
            }
            return null;
        }
        int format = root.path("format").asInt(-1);
        if (format != FORMAT) {
            if (problems != null) {
                problems.add("the class index has format " + format + ", and this build understands only "
                        + FORMAT);
            }
            return null;
        }
        String algo = root.path("hash").path("algo").asText("");
        String normalize = root.path("hash").path("normalize").asText("");
        if (!ContentHash.ALGO.equals(algo) || !ContentHash.NORMALIZE.equals(normalize)) {
            if (problems != null) {
                problems.add("the class index was hashed with " + algo + "/" + normalize
                        + ", and this build hashes with " + ContentHash.ALGO + "/" + ContentHash.NORMALIZE
                        + " — its checksums are not comparable, so it must force a full pass");
            }
            return null;
        }

        ClassIndex index = new ClassIndex(reportDir.resolve(INDEX_DIR_NAME), reportDir, moduleRoot,
                root.path("module").asText(""), root.path("sourceRoot").asText(""),
                false, Clock.systemUTC());
        for (Map.Entry<String, JsonNode> entry : root.path("classes").properties()) {
            JsonNode node = entry.getValue();
            List<String> modifiers = new ArrayList<>();
            for (JsonNode modifier : node.path("modifiers")) {
                modifiers.add(modifier.asText());
            }
            index.put(new ClassRecord(entry.getKey(), node.path("path").asText(""),
                    node.path("kind").asText(""), modifiers,
                    node.hasNonNull("enclosing") ? node.path("enclosing").asText() : null,
                    node.path("line").asInt(-1), node.path("depth").asInt(0),
                    node.path("generated").asInt(0) == 1, node.path("checksum").asText(""),
                    node.path("hashCalculatedAt").asText(null), node.path("size").asLong(-1L)));
        }
        index.readMtimes(index.indexFile.resolveSibling(MTIME_FILE_NAME), problems);
        return index;
    }

    /** Reads the {@code mtimes.json} sidecar beside {@code file}, when there is one. */
    private void readMtimes(Path mtimeFile, List<String> problems) {
        if (!Files.isRegularFile(mtimeFile)) {
            return;
        }
        try {
            JsonNode root = EntityMetadataGenerator.OBJECT_MAPPER.readTree(
                    Files.readString(mtimeFile, StandardCharsets.UTF_8));
            for (Map.Entry<String, JsonNode> entry : root.path("mtimes").properties()) {
                mtimes.put(entry.getKey(), entry.getValue().asLong(-1L));
            }
        } catch (IOException | RuntimeException unreadable) {
            // The sidecar is never a correctness input: a value it cannot provide costs one extra hash
            // per row, which is the safe answer. Reported so a corrupt file is not a silent mystery.
            if (problems != null) {
                problems.add("the mtime sidecar at " + mtimeFile + " could not be read (" 
                        + unreadable.getMessage() + "), so every row's content will be hashed again");
            }
        }
    }

    /** A consumer's view of why a table could not be used, for a diagnostic in DEC-022's format. */
    public static final class Readers {
        private Readers() {
        }

        /** The default table path of a module root. */
        public static Path defaultIndexFile(Path moduleRoot) {
            return moduleRoot.resolve(EntityMetadataGenerator.JCODEBUDDY_DIR)
                    .resolve(INDEX_DIR_NAME).resolve(FILE_NAME);
        }
    }
}
