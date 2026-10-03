package hr.hrg.jcodebuddy.engine.index;

import java.util.List;

import hr.hrg.jcodebuddy.engine.source.TreeQueries;

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
 * @param relations        the type's supertypes, {@code extends} first then {@code implements}, as
 *                         {@link TypeRelation}s — the names as written, empty for a type that declares none. A
 *                         table written before 3.0b carries no {@code relations} field at all and reads as
 *                         empty: that is <em>not recorded</em> rather than "none", which is why the writer now
 *                         always emits the field (an empty array) and the distinction stops mattering for new
 *                         tables
 * @param annotations      the annotations on the declaration, as written with their arguments as written, empty
 *                         for a declaration that carries none — always emitted, for the same not-recorded
 *                         reason as {@code relations} ({@link TypeAnnotation})
 * @param members          what the declaration contains: its fields, methods, constructors and nested types, as
 *                         {@link MemberRecord}s, empty for a declaration that declares none — always emitted, for
 *                         the same not-recorded reason as {@code relations} (plan step 3.0r)
 * @param span             the declaration's character range in its file, or {@code null} when none was recorded
 *                         — annotations and modifiers included, because that is what a reader clicks; a consumer
 *                         needs to be able to *point* at the type (DEC-040 D6)
 * @param permits          a <strong>sealed</strong> type's permitted subtypes, as written and in order; empty for
 *                         a type that is not sealed. Recorded because it is a fact the compiler keeps only as a
 *                         class-file attribute and the reflection API exposes unevenly, so a generator that must
 *                         not emit a subclass of a sealed type has to read it from the source (DEC-040 D1)
 */
public record ClassRecord(String fqn, String path, String kind, List<String> modifiers, String enclosing,
                          int line, int depth, boolean generated, String checksum, String hashCalculatedAt,
                          long size, List<TypeRelation> relations, List<TypeAnnotation> annotations,
                          List<MemberRecord> members, TreeQueries.SourceSpan span, List<String> permits) {

    public ClassRecord {
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        relations = relations == null ? List.of() : List.copyOf(relations);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        members = members == null ? List.of() : List.copyOf(members);
        permits = permits == null ? List.of() : List.copyOf(permits);
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
                hashCalculatedAt, size, relations, annotations, members, span, permits);
    }

    /**
     * A row whose declaration range the caller did not read — no range is a fact, not an empty one (DEC-040 D4).
     */
    public ClassRecord(String fqn, String path, String kind, List<String> modifiers, String enclosing,
                       int line, int depth, boolean generated, String checksum, String hashCalculatedAt,
                       long size, List<TypeRelation> relations, List<TypeAnnotation> annotations,
                       List<MemberRecord> members) {
        this(fqn, path, kind, modifiers, enclosing, line, depth, generated, checksum, hashCalculatedAt, size,
                relations, annotations, members, null, List.of());
    }

    /** A row with a declaration range but no permitted-subtype list, for a caller that read one and not both. */
    public ClassRecord(String fqn, String path, String kind, List<String> modifiers, String enclosing,
                       int line, int depth, boolean generated, String checksum, String hashCalculatedAt,
                       long size, List<TypeRelation> relations, List<TypeAnnotation> annotations,
                       List<MemberRecord> members, TreeQueries.SourceSpan span) {
        this(fqn, path, kind, modifiers, enclosing, line, depth, generated, checksum, hashCalculatedAt, size,
                relations, annotations, members, span, List.of());
    }

    /** Whether the type's own facts (not the file's content) differ from {@code other}. */
    public boolean sameTypeFacts(ClassRecord other) {
        return other != null
                && path.equals(other.path)
                && kind.equals(other.kind)
                && modifiers.equals(other.modifiers)
                && java.util.Objects.equals(enclosing, other.enclosing)
                && line == other.line
                && depth == other.depth
                // A relation change is a type-fact change: a type that starts implementing an interface must
                // not look unchanged to a pass that only compares the file's content (plan step 3.0b).
                && relations.equals(other.relations)
                // An annotation change is a type-fact change for the same reason a relation change is: adding
                // @Deprecated to a type whose bytes are otherwise identical must not look unchanged.
                && annotations.equals(other.annotations)
                // And so is a member change, for the third time and the same reason: adding a field to a type
                // whose bytes changed cannot be distinguished from one whose bytes did not, so the members are
                // what carries the fact (plan step 3.0r).
                && members.equals(other.members)
                // And a position change is a type-fact change too, for the fourth time and the same reason: a
                // member or a supertype moved, and a consumer that points at the file must see that it did.
                && java.util.Objects.equals(span, other.span)
                // Fifth, and for a reason of its own: sealing a type changes who may extend it, which is a fact
                // about the type rather than about any file's bytes.
                && permits.equals(other.permits);
    }
}
