package hr.hrg.hipster.entity.jackson;

import tools.jackson.core.JsonGenerator;

import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.core.ChangePath;
import hr.hrg.hipster.entity.core.CollectionDiagnostic;
import hr.hrg.hipster.entity.core.ListDelta;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Serializes the <strong>deep</strong> changes of a {@link ViewChangeTracking} source as a nested
 * RFC 6902-like JSON patch document (plan.dsflash § 11/6.6), alongside the shallow change-set
 * serializer {@link EntityJacksonChangeSerializer}.
 *
 * <h3>What "deep" adds over the shallow patch</h3>
 * <p>The shallow serializer writes one entry per changed field of the view it is handed, which is
 * all such a view can say about a field whose value is a nested tracked view: the reference moved.
 * The deep patch instead describes the change <em>inside</em> that value, at the position it
 * occupies — including inside a collection element, which is what {@link ChangePath#listIndex()}
 * carries.</p>
 *
 * <h3>Shape</h3>
 * <p>An object with a {@code "fields"} member, each entry carrying an operation and the path to the
 * value it applies to. A leaf states the value it holds now, so "the patch touches only the leaf" is
 * visible in the document rather than inferred from it:</p>
 *
 * <pre>{@code
 * {
 *   "fields": {
 *     "firstName": { "op": "replace", "current": "Ada-2" },
 *     "heads": {
 *       "op": "replace",
 *       "paths": [ { "path": ["heads", 0, "label"], "current": "first-edited" } ],
 *       "collection": { "changes": [ { "op": "move", "index": 2, "from": 0, "identity": 13 } ] }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>No entry carries a {@code previous} value: the tracker keeps none (see
 * {@link ViewChangeTracking}). An audit-style "old → new" document is a comparison of <em>two</em>
 * instances, so a consumer that needs it holds the baseline view its caller gave it, reads the same
 * field there, and pairs the two values itself — including when the leaf lives inside a collection
 * element, where the baseline element is found by the identity the delta already reports.</p>
 *
 * <p>The {@code "path"} array is the RFC 6902 pointer in a form that keeps a collection index an
 * <em>integer</em>, so an entry added at the end ({@code "add"} with index <em>n</em>) is not
 * confused with a document member. Names come from the field constants, indices from the tracker;
 * nothing is looked up in a map (DEC-016).</p>
 *
 * <p>A collection whose elements cannot be matched by identity reports {@code "fallback": true} on
 * the affected deltas and a {@code "diagnostics"} array on the field — the fallback is stated, never
 * silently presented as identity matching.</p>
 */
public final class EntityJacksonDeepChangeSerializer<V, F extends Enum<F> & FieldDef> {

    private final EntityJacksonChangeSerializer<V, F> leaves;

    public EntityJacksonDeepChangeSerializer(ViewMeta<V, F> meta) {
        this.leaves = new EntityJacksonChangeSerializer<>(meta);
    }

    /** Writes the deep patch of {@code tracking}. */
    public void serialize(ViewChangeTracking<F, ?> tracking, JsonGenerator gen) throws IOException {
        gen.writeStartObject();
        gen.writeName("fields");
        gen.writeStartObject();

        List<ChangePath> roots = tracking.changesDeep();
        for (F field : changedFields(roots)) {
            if (field.retired()) {
                continue; // § 4.6/R1.4: a retired tombstone is never written
            }
            gen.writeName(field.name());
            writeField(field, roots, tracking, gen);
        }

        gen.writeEndObject();
        gen.writeEndObject();
    }

    // ------------------------------------------------------------------ one field

    private void writeField(F field, List<ChangePath> roots, ViewChangeTracking<F, ?> tracking,
                            JsonGenerator gen) throws IOException {
        List<ChangePath> nested = new ArrayList<>();
        boolean namesField = false;
        for (ChangePath path : roots) {
            if (path.field() != field) {
                continue;
            }
            namesField = true;
            if (path.next() != null || path.isListElement()) {
                nested.add(path);
            }
        }
        List<ListDelta> deltas = tracking.collectionDeltas().getOrDefault(field.ordinal(), List.of());
        // A collection reports itself as a field-level path when it changed structurally; that is
        // NOT a scalar value, so the contract is asked rather than guessed.
        boolean scalarLeaf = namesField && !tracking.hasCollection(field.ordinal()) && nested.isEmpty();

        gen.writeStartObject();
        gen.writeStringProperty("op", "replace");

        if (scalarLeaf) {
            gen.writeName("current");
            leaves.writeValueFor(gen, field.ordinal(), tracking.currentValue(field));
        }

        if (!nested.isEmpty()) {
            gen.writeName("paths");
            gen.writeStartArray();
            for (ChangePath path : nested) {
                writeNestedPath(path, tracking, gen);
            }
            gen.writeEndArray();
        }

        if (!deltas.isEmpty()) {
            writeCollectionDeltas(deltas, gen);
        }

        writeDiagnostics(field, tracking, gen);
        gen.writeEndObject();
    }

    /**
     * One entered value below this field. The path is written as an array of names and integer
     * indices; when the value is a leaf, the value it holds now is written — taken from the
     * element's tracker, because the value lives in the element and not in this view.
     */
    private void writeNestedPath(ChangePath path, ViewChangeTracking<F, ?> tracking, JsonGenerator gen)
            throws IOException {
        gen.writeStartObject();
        gen.writeName("path");
        gen.writeStartArray();
        for (ChangePath at = path; at != null; at = at.next()) {
            if (at.field() == null) {
                continue;
            }
            gen.writeString(at.field().name());
            if (at.isListElement()) {
                gen.writeNumber(at.listIndex());
            }
        }
        gen.writeEndArray();

        FieldDef leaf = path.leaf();
        gen.writeStringProperty("op", "replace");
        if (leaf != null) {
            // The leaf is a field of the nested element, not of this view, so it is not in this
            // view's type table; the value is written as the POJO it is. That is the price of a patch
            // that describes a value the parent never held.
            Object current = null;
            for (var change : leafChangesOf(tracking, path)) {
                if (change.field() == leaf) {
                    current = change.current();
                    break;
                }
            }
            gen.writePOJOProperty("current", current);
        }
        gen.writeEndObject();
    }

    /**
     * The changed fields of the element a collection path descends through. The element's own tracker
     * is the only place those values exist, so the path is walked back to the field that holds the
     * collection and the element at that index is asked.
     */
    private static List<hr.hrg.hipster.entity.core.FieldChange<?>> leafChangesOf(
            ViewChangeTracking<?, ?> tracking, ChangePath path) {
        ChangePath holder = firstListLevel(path);
        if (holder == null) {
            return changesOf(tracking);
        }
        for (ListDelta delta : collectionDeltasOfTracking(tracking, holder.field().ordinal())) {
            if (delta.index() == holder.listIndex()) {
                return delta.fieldChanges();
            }
        }
        return List.of();
    }

    /** The outermost level of {@code path} that addresses a collection element, or {@code null}. */
    private static ChangePath firstListLevel(ChangePath path) {
        for (ChangePath at = path; at != null; at = at.next()) {
            if (at.isListElement()) {
                return at;
            }
        }
        return null;
    }

    private static List<ListDelta> collectionDeltasOfTracking(ViewChangeTracking<?, ?> tracking, int ordinal) {
        return tracking.collectionDeltas().getOrDefault(ordinal, List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<hr.hrg.hipster.entity.core.FieldChange<?>> changesOf(ViewChangeTracking<?, ?> tracking) {
        List<?> raw = ((ViewChangeTracking) tracking).changedValues();
        List<hr.hrg.hipster.entity.core.FieldChange<?>> changes = new ArrayList<>(raw.size());
        for (Object change : raw) {
            changes.add((hr.hrg.hipster.entity.core.FieldChange<?>) change);
        }
        return changes;
    }

    /** The structural half: one entry per delta, with the operation name an RFC 6902 reader expects. */
    private void writeCollectionDeltas(List<ListDelta> deltas, JsonGenerator gen) throws IOException {
        gen.writeName("collection");
        gen.writeStartObject();
        gen.writeName("changes");
        gen.writeStartArray();
        boolean fallback = false;
        for (ListDelta delta : deltas) {
            fallback |= delta.fallback();
            gen.writeStartObject();
            gen.writeStringProperty("op", operationNameOf(delta));
            gen.writeNumberProperty("index", delta.index());
            if (delta.previousIndex() >= 0 && delta.previousIndex() != delta.index()) {
                gen.writeNumberProperty("from", delta.previousIndex());
            }
            if (delta.identity() != null) {
                gen.writePOJOProperty("identity", delta.identity());
            }
            if (!delta.fieldChanges().isEmpty()) {
                gen.writeName("fields");
                gen.writeStartArray();
                for (var change : delta.fieldChanges()) {
                    if (change.field() != null) {
                        gen.writeString(change.field().name());
                    }
                }
                gen.writeEndArray();
            }
            if (delta.fallback()) {
                gen.writeBooleanProperty("fallback", true);
            }
            gen.writeEndObject();
        }
        gen.writeEndArray();
        if (fallback) {
            gen.writeBooleanProperty("fallback", true);
        }
        gen.writeEndObject();
    }

    private void writeDiagnostics(F field, ViewChangeTracking<F, ?> tracking, JsonGenerator gen)
            throws IOException {
        List<CollectionDiagnostic> diagnostics = tracking.collectionDiagnostics();
        if (diagnostics.isEmpty()) {
            return;
        }
        gen.writeName("diagnostics");
        gen.writeStartArray();
        for (CollectionDiagnostic diagnostic : diagnostics) {
            if (diagnostic.ordinal() != field.ordinal() && diagnostic.ordinal() != -1) {
                continue;
            }
            gen.writeStartObject();
            gen.writeStringProperty("code", diagnostic.code());
            gen.writeStringProperty("message", diagnostic.message());
            gen.writeEndObject();
        }
        gen.writeEndArray();
    }

    private static String operationNameOf(ListDelta delta) {
        return switch (delta.kind()) {
            case ADDED -> "add";
            case REMOVED -> "remove";
            case REORDERED -> "move";
            case REPLACED, FIELD_CHANGED -> "replace";
            case UNCHANGED -> "test";
        };
    }

    // ------------------------------------------------------------------ helpers

    /** The changed fields, deduplicated and in the order the tracker reported them. */
    private Set<F> changedFields(List<ChangePath> roots) {
        Set<F> changed = new LinkedHashSet<>();
        for (ChangePath path : roots) {
            if (path.field() != null) {
                changed.add(castField(path.field()));
            }
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private F castField(FieldDef field) {
        return (F) field;
    }

    /** The rendered paths of {@code tracking}, for messages and tests. */
    public static List<String> renderPaths(ViewChangeTracking<?, ?> tracking) {
        List<String> rendered = new ArrayList<>();
        for (ChangePath path : tracking.changesDeep()) {
            rendered.add(path.render());
        }
        return rendered;
    }
}
