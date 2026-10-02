package hr.hrg.hipster.entity.tooling.index;

import java.util.List;

/**
 * One row of the module class index — one type declaration (DEC-029).
 *
 * <p>The row is keyed by {@link #fqn} in the table. {@code path}, {@code size}, {@code mtime},
 * {@code checksum} and {@code hashCalculatedAt} are facts about the <em>file</em> that declares the
 * type, so two member types of one file repeat them: that repetition is accepted deliberately, because
 * it is what lets every reference in the metadata tree be a type name.</p>
 *
 * @param fqn              the fully qualified type name — the table's key and the way every document
 *                         references this type
 * @param path             the declaring file, module-relative with forward slashes, never absolute and
 *                         never {@code ..}
 * @param kind             {@code class} / {@code interface} / {@code enum} / {@code record} /
 *                         {@code annotation}
 * @param modifiers        the declaration's Java modifier keywords, sorted
 * @param enclosing        the FQN of the enclosing type, or {@code null}
 * @param line             the declaration's start line (its name), 1-based, or {@code -1}
 * @param depth            0 for a top-level type, the number of enclosing types otherwise
 * @param generated        whether the pass wrote the file (it carries a DEC-021 header)
 * @param checksum         {@link ContentHash} of the file's LF-normalised content, 16 hex characters
 * @param hashCalculatedAt the ISO-8601 UTC instant that checksum was calculated — preserved across
 *                         passes while the content is unchanged, so it dates the content rather than
 *                         the build
 * @param size             the file's size in bytes, as hashed
 */
public record ClassRecord(String fqn, String path, String kind, List<String> modifiers, String enclosing,
                          int line, int depth, boolean generated, String checksum, String hashCalculatedAt,
                          long size) {

    public ClassRecord {
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
    }

    /**
     * The same row with the file facts the pass just read — its checksum, the instant it was calculated
     * and the size.
     *
     * <p>The file's last-modified time is deliberately not a row field: it belongs to a working tree
     * rather than to the source, so it lives in the derived {@code mtimes.json} sidecar
     * ({@link ClassIndex#MTIME_FILE_NAME}) and never in a table a project may commit.</p>
     */
    public ClassRecord withFileFacts(String checksum, String hashCalculatedAt, long size) {
        return new ClassRecord(fqn, path, kind, modifiers, enclosing, line, depth, generated, checksum,
                hashCalculatedAt, size);
    }

    /** Whether the type's own facts (not the file's content) differ from {@code other}. */
    public boolean sameTypeFacts(ClassRecord other) {
        return other != null
                && path.equals(other.path)
                && kind.equals(other.kind)
                && modifiers.equals(other.modifiers)
                && java.util.Objects.equals(enclosing, other.enclosing)
                && line == other.line
                && depth == other.depth;
    }
}
