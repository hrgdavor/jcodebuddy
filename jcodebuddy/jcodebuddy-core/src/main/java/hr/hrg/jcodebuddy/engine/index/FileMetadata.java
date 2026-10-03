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
 * @param generated whether the file carries a DEC-021 generator header (a fact about the file's own text)
 * @param types     the declarations the file contains, in source order, as table rows
 * @param imports   the file's import lines as written, in order, empty when it writes none
 */
public record FileMetadata(String path, String checksum, long size, boolean generated, List<ClassRecord> types,
                           List<String> imports) {

    /** The format of an entry document, versioned separately from the class table: it evolves on its own. */
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
    public static FileMetadata of(String path, String checksum, long size, boolean generated,
                                  List<TypeFacts> types, List<String> imports) {
        List<ClassRecord> rows = new ArrayList<>(types.size());
        for (TypeFacts type : types) {
            rows.add(new ClassRecord(type.fqn(), path, type.kind(), type.modifiers(), type.enclosing(),
                    type.line(), type.depth(), generated, checksum, null, size, type.relations(),
                    type.annotations(), type.members(), type.span(), type.permits()));
        }
        return new FileMetadata(path, checksum, size, generated, rows, imports);
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
                root.path("size").asLong(-1L), root.path("generated").asInt(0) == 1, types, imports);
    }
}
