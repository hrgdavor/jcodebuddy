package hr.hrg.jcodebuddy.engine.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

import hr.hrg.jcodebuddy.engine.MetadataJson;

/**
 * One Java source file's <strong>base metadata</strong>: everything DEC-041 D2 allows in the cacheable layer, and
 * nothing else.
 *
 * <p>A base entry is the strict subset of the model derivable from <em>one file's bytes alone</em> — its path,
 * its content checksum, its size, whether it carries a generator header, the types it declares, and its import
 * lines. No resolution, no reverse edges, no freshness verdict, no clock: a fact needing a second file is
 * <strong>extended</strong> metadata and belongs to the table built from these entries (DEC-041 D1/D6). That is
 * not a filing convention; it is what makes the entry independent, and the independence is measurable — an entry
 * for file {@code A} is byte-identical whether file {@code B} exists, is edited, or is deleted.</p>
 *
 * <p><strong>The hash is the reason a cache may be reused at all.</strong> {@link #checksum()} is the file's
 * LF-normalised content hash (DEC-029 § 4, the identity this repository already uses), and a consumer that finds
 * the file's current hash different <em>must</em> recompute rather than answer from the entry: a stored copy
 * cannot announce that it is stale, and a hash is what makes the staleness free to detect (DEC-040 D2).</p>
 *
 * <p><strong>The stored rows are the table's rows</strong>, written by {@link ClassIndex#appendRow} and read back
 * by {@link ClassIndex#readRow}. A second row shape for the cache would mean two writers, two readers and a class
 * of bug where they disagree about what a row is; the cost of sharing is that one file's entry repeats its path,
 * checksum and size on each of its rows, and the entry's own header is the single authority for reuse.</p>
 *
 * <p><strong>No source text is stored</strong>, which matters more here than anywhere else: a per-file cache is
 * exactly where keeping the file's text becomes tempting, because the file was just read. The facts are names and
 * ranges, and the written form is recovered by slicing the file at a range with the checksum as the guard
 * (DEC-040 D2) — so an entry is kilobytes, not the file.</p>
 *
 * @param path      the file's module-relative path, with {@code /} separators — the entry's identity, since every
 *                  other fact in it is about that file
 * @param checksum  the file's content checksum, in the same algorithm and normalisation the table uses
 * @param size      the file's size in bytes, as hashed
 * @param lastModified the file's last-modified time in epoch milliseconds, as hashed, or {@code -1} when the entry
 *                  predates this field — the first tier of the staleness gate (plan step 6.6), because a `stat` is
 *                  cheap enough to run on every file of every pass while a hash is not
 * @param generated whether the file carries a DEC-021 generator header (a fact about the file's own text)
 * @param types     the declarations the file contains, in source order, as table rows
 * @param imports   the file's import lines as written, in order, empty when it writes none
 */
public record FileMetadata(String path, String checksum, long size, long lastModified, boolean generated,
                           List<ClassRecord> types, List<String> imports) {

    /**
     * The format of an entry document, versioned separately from the class table: it evolves on its own.
     *
     * <p><strong>{@code lastModified} was added without bumping this</strong>, and that is deliberate (plan step
     * 6.6): a new field whose absence has a defined meaning ({@code -1} = "unknown, so hash and decide") does not
     * make an older entry unreadable, and the next {@link #toJson} backfills it. A bump would have invalidated every
     * warm cache in every checkout to gain nothing, which is the trade a versioned format exists to make
     * deliberately rather than reflexively.</p>
     */
    public static final int ENTRY_FORMAT = 1;

    public FileMetadata {
        path = path == null ? "" : path;
        checksum = checksum == null ? "" : checksum;
        types = types == null ? List.of() : List.copyOf(types);
        imports = imports == null ? List.of() : List.copyOf(imports);
    }

    /**
     * The facts of one parse, as an entry: the file's identity plus what {@link ClassIndex#factsOf} read.
     *
     * <p>A convenience for the pass rather than a second extraction path: it only assembles what the caller
     * already read.</p>
     */
    public static FileMetadata of(String path, String checksum, long size, long lastModified, boolean generated,
                                  List<TypeFacts> types, List<String> imports) {
        List<ClassRecord> rows = new ArrayList<>(types.size());
        for (TypeFacts type : types) {
            rows.add(new ClassRecord(type.fqn(), path, type.kind(), type.modifiers(), type.enclosing(),
                    type.line(), type.depth(), generated, checksum, null, size, type.relations(),
                    type.annotations(), type.members(), type.span(), type.permits()));
        }
        return new FileMetadata(path, checksum, size, lastModified, generated, rows, imports);
    }

    /**
     * Whether this entry describes the file whose content hash is {@code currentChecksum}.
     *
     * <p>The reuse question, and the only one a cache may answer: an entry that does not describe the current
     * bytes must not be used, whatever else it says (DEC-041 D3).</p>
     */
    public boolean describes(String currentChecksum) {
        return checksum != null && !checksum.isEmpty() && checksum.equals(currentChecksum);
    }

    /**
     * The <strong>first tier</strong> of the staleness gate (plan step 6.6): may this entry be reused because the
     * filesystem says nothing about the file moved?
     *
     * <p>Cheapest first, as the maintainer specified on 2026-10-08 — *"mtime and file size first as easily checkable
     * for FS metadata and then hash as slowest check"*. A `stat` costs no read, so a whole repository's worth of
     * them costs less than hashing one large file; a hash is therefore computed only when this returns
     * {@code false}.</p>
     *
     * <p><strong>It is a shortcut to the same answer, never a weaker one.</strong> Size and mtime both matching means
     * the file was not written to since the entry was stored; a file edited within the filesystem's timestamp
     * resolution and left the same size is the one case this cannot see, and that case is why this tier is allowed to
     * answer *reuse* only when {@link #parse} produced an entry that a hash had already authenticated once. An entry
     * with no recorded time ({@code -1}, written before this field existed) answers {@code false} and takes the hash
     * path, so a legacy entry is usable and backfills itself on the next store.</p>
     *
     * @param currentSize         the file's size now, from a `stat`
     * @param currentLastModified the file's last-modified time now, in epoch milliseconds
     */
    public boolean matchesStat(long currentSize, long currentLastModified) {
        return lastModified > 0 && size >= 0 && currentLastModified == lastModified && currentSize == size;
    }

    /**
     * This entry with the file's current size and last-modified time — the self-healing half of the gate.
     *
     * <p>A tier-2 answer ("touched, not changed", or a legacy entry with no recorded time) proves the checksum is
     * still right, so the entry may be rewritten with the stat that was just observed and the next pass answers in
     * tier 1. Without this, a touched file — and every entry written before the timestamp existed — would be hashed
     * on <em>every</em> pass forever: a silent, permanent cost with no symptom, which is exactly the kind of thing
     * the gate exists to remove.</p>
     */
    public FileMetadata withStat(long currentSize, long currentLastModified) {
        return new FileMetadata(path, checksum, currentSize, currentLastModified, generated, types, imports);
    }

    /**
     * This entry as one JSON document, deterministic: the same facts write the same bytes, so a cache entry is
     * diffable and a rebuild that changed nothing produces an identical file.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"format\": ").append(ENTRY_FORMAT).append(",\n");
        sb.append("  \"layer\": \"base\",\n");
        sb.append("  \"path\": \"").append(MetadataJson.escape(path)).append("\",\n");
        sb.append("  \"checksum\": \"").append(checksum).append("\",\n");
        sb.append("  \"size\": ").append(size).append(",\n");
        sb.append("  \"lastModified\": ").append(lastModified).append(",\n");
        sb.append("  \"generated\": ").append(generated ? 1 : 0).append(",\n");
        sb.append("  \"imports\": [");
        for (int i = 0; i < imports.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("\"").append(MetadataJson.escape(imports.get(i))).append("\"");
        }
        sb.append("],\n");
        // The rows are the TABLE's rows, written by the table's own writer: one shape, one reader (DEC-041 D8).
        sb.append("  \"classes\": {");
        for (int i = 0; i < types.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n");
            ClassIndex.appendRow(sb, types.get(i), "    ");
        }
        sb.append(types.isEmpty() ? "}\n" : "\n  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * An entry read from {@code json}, or {@code null} when this is not an entry this build understands.
     *
     * <p>A {@code null} is never fatal — it costs a parse (DEC-041 D7). Reported rather than silent when a
     * {@code problems} list is given, because "the cache is cold" and "the cache is corrupt" are different
     * operational facts, and both are answers a caller may act on.</p>
     */
    public static FileMetadata parse(String json, List<String> problems) {
        JsonNode root;
        try {
            root = MetadataJson.mapper().readTree(json);
        } catch (RuntimeException notJson) {
            if (problems != null) {
                problems.add("a base entry is not valid JSON: " + notJson.getMessage());
            }
            return null;
        }
        int format = root.path("format").asInt(-1);
        if (format != ENTRY_FORMAT) {
            if (problems != null) {
                problems.add("a base entry has format " + format + ", and this build understands only "
                        + ENTRY_FORMAT);
            }
            return null;
        }
        List<ClassRecord> types = new ArrayList<>();
        for (Map.Entry<String, JsonNode> row : root.path("classes").properties()) {
            ClassRecord parsed = ClassIndex.readRow(row.getKey(), row.getValue(), problems);
            if (parsed == null) {
                // The table's rule, applied to an entry: a row this contract cannot describe makes the artifact
                // unusable, and the caller recomputes it rather than reading half of it.
                return null;
            }
            types.add(parsed);
        }
        List<String> imports = new ArrayList<>();
        for (JsonNode line : root.path("imports")) {
            imports.add(line.asText(""));
        }
        return new FileMetadata(root.path("path").asText(""), root.path("checksum").asText(""),
                root.path("size").asLong(-1L), root.path("lastModified").asLong(-1L),
                root.path("generated").asInt(0) == 1, types, imports);
    }
}
