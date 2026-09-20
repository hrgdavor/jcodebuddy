package hr.hrg.hipster.entity.tooling.meta;

/**
 * One type declaration that belongs to a view — the inventory {@code views[].artifacts[]} is written
 * from.
 *
 * <p>A view is more than its interface. The pass emits the field enum, the record materialization, the
 * plain builder, the tracking builder, the validator and (when {@code --adapters} is on) the row adapter
 * and the binder, and the interface itself may declare nested types the generated artifacts target. The
 * page shows those as columns, so the metadata has to name them — and it has to name them from the pass
 * that <em>wrote</em> them rather than from a render-time scan that recognises them by naming convention
 * and a header comment.</p>
 *
 * <p>The list also carries the <strong>foreign declaring interfaces</strong> the view's fields reference,
 * with {@code own = false}: an inherited field's {@code accessor} lives in {@code Person}, not in the
 * view's own file, and it needs an entry in the list because a field's location map is keyed by artifact
 * id and every location must resolve. Widening the list was chosen over a second key space
 * ({@code "foreign": {…}}) because a consumer then handles one shape, and because the page already shows
 * a foreign declaring interface as an aspect of the view.</p>
 *
 * @param id        small integer, unique within the view's list and assigned in reading order — the
 *                  view's own file first, then its nested types, then the generated siblings in the order
 *                  the pass emits them, then the foreign declaring interfaces in first-reference order.
 *                  This is the key {@code fields[].at} uses, so it must be a function of the (already
 *                  deterministic) artifact list. It is a <em>view-local</em> id: it identifies an entry of
 *                  this view's list and nothing else
 * @param name      the display name the page prints: {@code PersonSummary}, {@code PersonSummary.Record},
 *                  {@code PersonSummaryBuilder}. Nested types are {@code Outer.Inner} because that is how
 *                  a Java developer names them
 * @param kind      {@code interface} / {@code record} / {@code enum} / {@code class}
 * @param file      the module-relative path of the file in the model, written to JSON as the
 *                  <strong>id</strong> {@code ModuleFileIndex} assigned it — the split DEC-028 § 4.7
 *                  prescribes and that the write ordering forces: ids exist only once the whole pass has
 *                  run, while this inventory is built as each view is emitted, so the conversion happens
 *                  at the single point where JSON is written, never here
 * @param line      the declaration line of this type <em>inside</em> that file; a nested type has its own
 * @param generated whether the generator owns the file, i.e. whether it carries a DEC-021 header. A
 *                  hand-written nested record is an artifact of the view without being generated, and
 *                  the page says so
 * @param own       whether the artifact is declared in the view's own file or was generated for it. A
 *                  foreign declaring interface is listed with {@code own = false}
 * @param header    the DEC-021 description text of a generated file, or {@code null} for a hand-written
 *                  one. Carried so a consumer does not have to re-read the file to label the artifact
 */
public record ArtifactMeta(int id, String name, String kind, String file, int line, boolean generated,
                           boolean own, String header) {

    public ArtifactMeta {
        name = name == null ? "" : name;
        kind = kind == null ? "" : kind;
        file = file == null ? "" : file;
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public String getFile() {
        return file;
    }

    public int getLine() {
        return line;
    }

    public boolean isGenerated() {
        return generated;
    }

    public boolean isOwn() {
        return own;
    }

    public String getHeader() {
        return header;
    }
}
