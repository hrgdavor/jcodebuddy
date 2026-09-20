package hr.hrg.hipster.entity.tooling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The module's central file index: the one place a source path is written, and the one place a file id
 * is assigned (DEC-028).
 *
 * <h3>What problem this solves</h3>
 * <p>Every location the metadata records used to be a full module-relative path — 83 characters on
 * average in this repository — and a field has many locations, so the same 30 paths were written
 * hundreds of times across the marker documents. Worse, each document computed its own answer, so two
 * documents could disagree about what a path meant. The index replaces the repeated path with a short
 * <strong>id</strong> that every document shares, and states each path exactly once:</p>
 *
 * <pre>{@code
 * // <module>/.jcodebuddy/index/files.json
 * {
 *   "format": 1,
 *   "module": "hipster-entity-example",
 *   "sourceRoot": "src/main/java",
 *   "files": {
 *     "Person":         "src/main/java/hr/hrg/hipster/entityexample/person/entity/Person.java",
 *     "PersonSummary":  "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java",
 *     "PersonSummary_": "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary_.java"
 *   }
 * }
 * }</pre>
 *
 * <h3>Why central and per module, not a table per document</h3>
 * <ul>
 *   <li><strong>One place assigns the ids.</strong> Module-wide uniqueness is a property of the whole set
 *       of files, so computing it once is correct by construction. Per-document tables would each have to
 *       compute the same uniqueness over their own subset and could disagree — a file whose simple name
 *       collides with a file no document happens to mention would get different ids in different
 *       documents.</li>
 *   <li><strong>One place to regenerate, diff and version.</strong> A rename changes one row; a project
 *       that commits its metadata as a contract reviews one table instead of three.</li>
 *   <li><strong>It is the natural home for the module's other indexes.</strong> The directory is the
 *       index and {@code files.json} is its first table: {@code hashes.json} (content identity for a
 *       watcher or an incremental pass) and the dependency edges are <em>reserved</em> here so the shape
 *       does not have to change when they land. See {@link #README_TEXT}.</li>
 * </ul>
 *
 * <p>The cost, stated honestly: a document is no longer self-contained. A consumer needs the document
 * <em>and</em> the table, which is why every document carries a {@code fileIndex} pointer to it and why a
 * consumer that cannot read the table must fail loudly rather than render a page of dangling ids.</p>
 *
 * <h3>Where it is written</h3>
 * <p>{@code <nearest .jcodebuddy above the report directory>/index/files.json}; when the report directory
 * is not inside a {@code .jcodebuddy} at all — a temp directory in a test — it goes to
 * {@code <report directory>/index/files.json}. That is why the pointer is <em>emitted</em> rather than
 * assumed: the relative distance to the table depends on the layout.</p>
 *
 * <p>The directory's own {@code README.md} is part of DEC-026's layout of a module's
 * {@code .jcodebuddy/}, so it is written only when the index actually lives in one. A fallback
 * {@code <report dir>/index} is not a module layout, gets no README, and therefore keeps the
 * "a report directory holds JSON only" guard true ({@code GeneratorGuardTest}).</p>
 */
public final class ModuleFileIndex {

    /** The table's own version. An unknown value means "do not trust this table" — never guess. */
    public static final int FORMAT = 1;

    /** The addressing table's file name inside {@link #INDEX_DIR_NAME}. */
    public static final String FILE_NAME = "files.json";

    /** The directory name, a sibling of {@code metadata/} under a module's {@code .jcodebuddy/}. */
    public static final String INDEX_DIR_NAME = "index";

    /** The directory's README, required by DEC-026 for every {@code .jcodebuddy/} subfolder. */
    public static final String README_NAME = "README.md";

    /** The index directory's purpose, its tables, and what is reserved but not built (DEC-028 § 2.3). */
    static final String README_TEXT = """
            # The module index

            Addressing tables that a metadata document in this module references, written by the
            `hipster-entity-generator` pass. Derived output, like `metadata/`: ignored by default, and
            opt-in together with the metadata subtree (see `../README.md`, "Track policy").

            ## `files.json` — the addressing table

            One row per file the pass indexed or wrote: a short **id** to the file's **module-relative
            path**. This is the only place in the whole metadata tree where a source path is written —
            every `file` value in a `<Marker>.metadata.json` is an id that resolves here, which is what
            keeps a path from being repeated once per location.

            * `format` — the table's own version. A consumer that does not recognise it must refuse the
              table and say why, never guess.
            * `module`, `sourceRoot` — diagnostics, so the file is self-describing when opened directly.
            * `files` — id to path, sorted by id. Paths are module-relative with forward slashes, never
              absolute and never `..`.

            Ids are **derived from the path**, never from a counter: the file's simple name, qualified by
            the shortest package suffix that disambiguates it among all the files this pass indexed
            (`PersonSummary`, but `entity.Person` when another package holds a `Person` too). That makes
            an id deterministic — a function of the path and the set of paths, with nothing depending on
            visit order — identical in every document, and readable in a diff. The trade-off is
            deliberate: adding a file whose simple name collides with an existing one may lengthen the
            existing id, which a hash would avoid at the cost of readability.

            ## Reserved, not implemented

            The directory is a *directory of tables* so these can be added without changing any
            consumer's contract:

            * `hashes.json` — content identity: id to a hash of the file's bytes (CRLF normalised to LF
              first, so the same content is valid on any checkout), plus a header naming the algorithm,
              the tooling revision and the flags that change output. Never a correctness input: a
              missing, unreadable or version-mismatched table must force a full pass. It is what would
              let a watcher or an incremental pass decide what actually changed without re-reading and
              re-parsing the tree.
            * `artifacts[].inputs` in a document — the dependency edges: per emitted artifact, the file
              ids it was generated *from*. That turns the index into the graph an incremental pass needs
              instead of one it must re-derive by parsing.

            Neither is written today. `metadata/watch/<toolSet>/metadata.db` remains the watch agent's
            own cache and is not replaced by this directory.

            This README is tracked and human-owned: the pass creates it when it is absent and never
            overwrites it.
            """;

    private final Path indexDir;
    private final Path indexFile;
    private final Path reportDir;
    private final String moduleName;
    private final String sourceRoot;
    private final boolean insideJcodebuddy;

    /** Every module-relative path the pass indexed or wrote, in sorted order so the ids cannot depend on walk order. */
    private final TreeSet<String> collected = new TreeSet<>();

    private Map<String, String> idToPath;
    private Map<String, String> pathToId;

    private ModuleFileIndex(Path indexDir, Path reportDir, String moduleName, String sourceRoot,
                            boolean insideJcodebuddy) {
        this.indexDir = indexDir;
        this.indexFile = indexDir.resolve(FILE_NAME);
        this.reportDir = reportDir;
        this.moduleName = moduleName == null ? "" : moduleName;
        this.sourceRoot = sourceRoot == null ? "" : sourceRoot;
        this.insideJcodebuddy = insideJcodebuddy;
    }

    /**
     * Resolves the index location for a pass and creates the index.
     *
     * @param reportDir  the directory the marker documents are written to
     * @param moduleRoot the module the recorded paths are relative to
     * @param sourceRoot the module's source root, recorded as a diagnostic
     */
    public static ModuleFileIndex forPass(Path reportDir, Path moduleRoot, Path sourceRoot) {
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
        return new ModuleFileIndex(dir, reportDir, module, relativeSourceRoot, inside);
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
    private static String moduleRelative(Path base, Path file) {
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

    /**
     * Records a file the pass indexed or wrote.
     *
     * <p>Called for the indexed sources during the walk and for each artifact file once it exists, which
     * is why ids are computed lazily: the set must be complete before the first id is resolved, and
     * {@link #idFor} freezes it on first use.</p>
     */
    public void add(String moduleRelativePath) {
        if (moduleRelativePath == null || moduleRelativePath.isBlank()) {
            return;
        }
        if (!isModuleRelative(moduleRelativePath)) {
            // Not a programming error to hide: a caller that hands this index a path it cannot hold is
            // a bug in the caller, and recording it would put a meaningless row in every consumer's table.
            throw new IllegalArgumentException(
                    "not a module-relative path, so it cannot be indexed: " + moduleRelativePath);
        }
        if (idToPath != null) {
            throw new IllegalStateException("the index is frozen; add every file before resolving an id: "
                    + moduleRelativePath);
        }
        collected.add(moduleRelativePath);
    }

    /** Records several files; {@code null} and blank entries are ignored. */
    public void addAll(Iterable<String> moduleRelativePaths) {
        if (moduleRelativePaths == null) {
            return;
        }
        for (String path : moduleRelativePaths) {
            add(path);
        }
    }

    /**
     * The id assigned to {@code moduleRelativePath}, assigning every id on first use.
     *
     * <p>An unindexed path is a <strong>bug</strong>, not a fallback: writing the path into a document
     * would quietly break the rule that a path is stated once, and a consumer would then have two
     * sources of truth that can disagree. It throws rather than degrades.</p>
     */
    public String idFor(String moduleRelativePath) {
        freeze();
        String id = pathToId.get(moduleRelativePath);
        if (id == null) {
            throw new IllegalStateException("the file is not in this pass's index, so it has no id: "
                    + moduleRelativePath + " (indexed: " + collected.size() + " file(s) in " + indexFile
                    + "). Every path a document mentions must be added to the index before toJson runs.");
        }
        return id;
    }

    /** The module-relative path behind {@code id}, or {@code null} when the id is unknown. */
    public String pathFor(String id) {
        freeze();
        return idToPath.get(id);
    }

    /** The whole table, id to path, sorted by id — what the JSON writer emits. */
    public Map<String, String> files() {
        freeze();
        return idToPath;
    }

    /** The table's file, {@code <index dir>/files.json}. */
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
     * The pointer every document of this pass carries: the index file's path relative to the report
     * directory, with forward slashes ({@code ../../index/files.json}).
     *
     * <p>Emitted rather than assumed, so a consumer that has only a document can find the table without
     * knowing the {@code .jcodebuddy} layout — and so the answer stays right for the fallback layout,
     * where the table sits inside the report directory instead of beside it.</p>
     */
    public String documentPointer() {
        return pointerFrom(reportDir);
    }

    /**
     * The pointer a document carries: the index file's path relative to the document's own directory,
     * with forward slashes ({@code ../../index/files.json}). Emitted rather than assumed so a consumer
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

    /**
     * Assigns every id, once, from the complete set of paths.
     *
     * <p>Grouped by simple name and then qualified by the <em>shortest</em> package suffix that
     * disambiguates the whole group, so the answer is a function of the set — never of the order the
     * files were visited. Two files in the same package with the same simple name (possible only across
     * source roots) fall back to the full path, which is still deterministic and still unique.</p>
     */
    private synchronized void freeze() {
        if (idToPath != null) {
            return;
        }
        Map<String, List<String>> bySimpleName = new TreeMap<>();
        for (String path : collected) {
            bySimpleName.computeIfAbsent(simpleNameOf(path), __ -> new ArrayList<>()).add(path);
        }

        Map<String, String> idToPath = new TreeMap<>();
        Map<String, String> pathToId = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> group : bySimpleName.entrySet()) {
            String simpleName = group.getKey();
            List<String> members = group.getValue();
            Map<String, String> assigned = qualify(simpleName, members);
            for (Map.Entry<String, String> entry : assigned.entrySet()) {
                idToPath.put(entry.getValue(), entry.getKey());
                pathToId.put(entry.getKey(), entry.getValue());
            }
        }
        this.idToPath = idToPath;
        this.pathToId = pathToId;
    }

    /** Shortest unique qualification for one simple-name group: path to id. */
    private static Map<String, String> qualify(String simpleName, List<String> members) {
        Map<String, String> assigned = new LinkedHashMap<>();
        if (members.size() == 1) {
            assigned.put(members.get(0), simpleName);
            return assigned;
        }

        int maxSegments = 0;
        for (String member : members) {
            maxSegments = Math.max(maxSegments, packageSegments(member).length);
        }
        for (int suffix = 1; suffix <= maxSegments; suffix++) {
            Map<String, String> candidate = new LinkedHashMap<>();
            boolean ambiguous = false;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String member : members) {
                String prefix = packageSuffix(member, suffix);
                String id = prefix.isEmpty() ? simpleName : prefix + "." + simpleName;
                if (!seen.add(id)) {
                    ambiguous = true;
                    break;
                }
                candidate.put(member, id);
            }
            if (!ambiguous) {
                return candidate;
            }
        }

        // Same package, same simple name, two source roots: the full path is the only unique answer.
        for (String member : members) {
            assigned.put(member, member.endsWith(".java")
                    ? member.substring(0, member.length() - ".java".length()).replace('/', '.')
                    : member.replace('/', '.'));
        }
        return assigned;
    }

    /** The file name without its {@code .java} extension. */
    private static String simpleNameOf(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
    }

    /** The path's package segments, i.e. everything but the file name. */
    private static String[] packageSegments(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return new String[0];
        }
        return path.substring(0, slash).split("/");
    }

    /** The last {@code count} package segments joined by dots, or {@code ""} when there is no package. */
    private static String packageSuffix(String path, int count) {
        String[] segments = packageSegments(path);
        int from = Math.max(0, segments.length - count);
        return String.join(".", java.util.Arrays.copyOfRange(segments, from, segments.length));
    }

    /**
     * Writes {@code files.json} (and, in a module, the directory's README when it is absent).
     *
     * <p>Deterministic bytes for an unchanged tree: no timestamp, no count that varies with the checkout,
     * ids sorted, paths in sorted order. A project that commits the table must not get diff noise from a
     * pass that changed nothing.</p>
     */
    public void write() throws IOException {
        freeze();
        writeReadmeIfAbsent();
        Files.createDirectories(indexDir);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"format\": ").append(FORMAT).append(",\n");
        sb.append("  \"module\": \"").append(EntityMetadataGenerator.escapeJson(moduleName)).append("\",\n");
        sb.append("  \"sourceRoot\": \"").append(EntityMetadataGenerator.escapeJson(sourceRoot)).append("\",\n");
        sb.append("  \"files\": {");
        if (idToPath.isEmpty()) {
            sb.append("}\n");
        } else {
            sb.append("\n");
            int index = 0;
            for (Map.Entry<String, String> entry : idToPath.entrySet()) {
                sb.append("    \"").append(EntityMetadataGenerator.escapeJson(entry.getKey())).append("\": \"")
                        .append(EntityMetadataGenerator.escapeJson(entry.getValue())).append("\"");
                sb.append(index < idToPath.size() - 1 ? ",\n" : "\n");
                index++;
            }
            sb.append("  }\n");
        }
        sb.append("}\n");
        Files.writeString(indexFile, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Creates the directory's README when it is absent, and <strong>never</strong> overwrites one.
     *
     * <p>{@code .jcodebuddy/index/README.md} is a tracked, human-owned file: rewriting it on every pass
     * would silently revert a human's edit, which is exactly what DEC-020 forbids for generated blocks.
     * The pass owns {@code files.json} and only seeds the README.</p>
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
}
