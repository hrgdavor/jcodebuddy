package hr.hrg.hipster.entity.tooling.meta;

import java.util.Objects;

/**
 * One place a field is, as a pass <em>recorded</em> it while it wrote the file.
 *
 * <p>A field is not in one place: a {@code COLUMN} field exists as an accessor in the interface that
 * declares it, as a constant and a {@code forName} arm in the view's field enum, as a component of the
 * record materialization and as a stored field, an accessor, a setter and an ordinal-switch arm in each
 * builder. The metadata used to record only the declaring accessor's declaration start, so a consumer
 * that wanted the other locations had to re-scan the source and infer them by naming convention — which
 * is what the Bun page does today and what the central metadata now answers instead.</p>
 *
 * <p><strong>{@code path} is a module-relative path, and the model carries paths while the JSON
 * carries ids</strong> (DEC-028). That split is not an accident of implementation: an id cannot exist at
 * the moment a location is extracted. Ids are a property of the <em>whole</em> set of files a pass
 * indexed, and that set is only complete after every emitter has run, because the generated siblings are
 * indexed as they are written — while the interface-side locations are recorded during the parse, long
 * before. Converting to an id at extraction time would therefore mean either freezing the table before
 * the artifacts are known (and losing a simple-name collision caused by a generated file) or resolving an
 * id against a partial table. So the conversion happens once, in {@code toJson}, which runs after the
 * index is complete; {@code fromJson} converts back, and {@code Property.sourcePath} keeps its meaning for
 * every existing Java consumer. A {@code ..}-free path is one-to-one with its id, so the
 * de-duplication key below is unaffected by which of the two it holds.</p>
 *
 * <p>This is an internal working type: it is what the extraction helpers hand to each other while a
 * view's detail is assembled. The JSON does <em>not</em> carry a flat location list — a field's
 * locations are grouped per artifact in {@code views[].fields[].at} (see {@link ViewFieldMeta}), and the
 * simple-declaration record {@code views[].properties[]} carries no location map at all, because the
 * view's field map already holds the interface-side lines for every field it declares.</p>
 *
 * @param artifact the display name of the type the location is <em>in</em> ({@code PersonSummary},
 *                 {@code PersonSummary.Record}, {@code PersonSummaryBuilder}) — not the file, because the
 *                 view's own file holds several artifacts (the interface, a nested record, a nested
 *                 {@code Write}) and a bare file id cannot tell them apart. A nested or inherited
 *                 declaration is labelled with its own type for the same reason
 * @param role     what the location is: one of the fixed role vocabulary in DEC-028
 *                 ({@code accessor}, {@code annotation}, {@code enum-constant}, {@code name-slot},
 *                 {@code record-component}, {@code field}, {@code setter}, {@code ordinal-slot})
 * @param path     the module-relative path of the file the location lives in; {@code toJson} writes the
 *                 fully qualified name of the type that file declares (DEC-029), and a path is never
 *                 written into a document
 * @param line     1-based line number of the member, or {@code -1} when the member's position was not
 *                 resolved. A role that does not exist for a field is <em>absent</em> from the map
 *                 rather than present with {@code -1}: a {@code DERIVED} field has no setter anywhere,
 *                 and reporting a line for one would be a lie
 */
public record SourceLocation(String artifact, String role, String path, int line) {

    public SourceLocation {
        artifact = artifact == null ? "" : artifact;
        role = role == null ? "" : role;
        path = path == null ? "" : path;
    }

    /** Whether this location has a usable line — the guard against recording an unresolved position. */
    public boolean resolved() {
        return line > 0;
    }

    /**
     * The de-duplication key the two extraction halves agree on.
     *
     * <p>The interface-side scan ({@code parseProperty}) and the artifact-side scan
     * ({@code MetadataLocations}) both see a view's own accessor, so both must collapse to one entry.
     * The key includes the artifact because the <em>same</em> field can legitimately appear twice in one
     * file for two different artifacts — an outer interface and its nested record both declare it — and
     * keying on the file alone would silently drop one of them.</p>
     */
    public String key() {
        return artifact + "\u0000" + role + "\u0000" + path + "\u0000" + line;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof SourceLocation other && key().equals(other.key());
    }

    @Override
    public int hashCode() {
        return Objects.hash(artifact, role, path, line);
    }
}
