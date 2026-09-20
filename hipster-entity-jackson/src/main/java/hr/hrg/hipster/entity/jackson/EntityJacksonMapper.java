package hr.hrg.hipster.entity.jackson;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;
import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.ViewReader;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

import java.io.IOException;

/**
 * Reflection-free Jackson serializer/deserializer for array-backed view proxies.
 *
 * <p>Serialization uses metadata from {@link ViewMeta} and {@link ViewReader#get(int)}.
 * This module does not maintain duplicate schema objects or maps by field name.</p>
 */
public final class EntityJacksonMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private EntityJacksonMapper() {
    }

    public static <V extends EntityBase<?>, F extends Enum<F> & FieldDef> void toJson(ViewMeta<V, F> meta,
                                                                 ViewReader entity,
                                                                 java.io.Writer writer) {
        try (JsonGenerator gen = OBJECT_MAPPER.createGenerator(writer)) {
            new EntityJacksonViewSerializer<>(meta).serialize(entity, gen);
            gen.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize entity", e);
        }
    }

    /**
     * Writes a view that is <strong>not</strong> a {@link ViewReader}.
     *
     * <p>The ordinal serializer walks {@code ViewReader.get(ordinal)}, but a {@code RECORD}-level
     * materialization is a plain record implementing the view interface and is deliberately not a
     * {@code ViewReader} — it has no positional array. The caller supplies the mapping from the view
     * to its positional array, which is the same mapping {@code ViewMeta.create(Object[])} performs
     * in reverse, so nothing about the ordinal contract is bypassed and no reflection is involved.</p>
     *
     * <p>A {@link hr.hrg.hipster.entity.core.ViewChangeTracking} source does not need this overload:
     * the change-set serializer works from the view's own accessors through its {@code diff()}.</p>
     *
     * @param positional maps a view instance to its positional array, in field-enum ordinal order
     */
    public static <V extends EntityBase<?>, F extends Enum<F> & FieldDef> void toJson(ViewMeta<V, F> meta,
                                                                 V entity,
                                                                 java.util.function.Function<V, Object[]> positional,
                                                                 java.io.Writer writer) {
        Object[] values = positional.apply(entity);
        if (values == null || values.length < meta.fieldCount()) {
            throw new IllegalArgumentException("the positional array must cover all "
                    + meta.fieldCount() + " fields, got "
                    + (values == null ? "null" : values.length));
        }
        ViewReader reader = new hr.hrg.hipster.entity.core.EntityReadArray<>(meta.fieldType(), values);
        toJson(meta, reader, writer);
    }

    /**
     * One-off deserialization using a per-call {@link EntityJacksonViewDeserializer} instance.
     * Acceptable for single calls; for hot loops, pre-build the deserializer and call
     * {@link EntityJacksonViewDeserializer#deserialize(JsonParser)} directly.
     */
    public static <V extends EntityBase<?>, F extends Enum<F> & FieldDef> V fromJson(ViewMeta<V, F> meta,
                                                                JsonParser p) {
        try {
            return new EntityJacksonViewDeserializer<>(meta).deserialize(p);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize entity", e);
        }
    }

    public static <V extends EntityBase<?>, F extends Enum<F> & FieldDef> EntityJacksonViewModule<V, F> module(ViewMeta<V, F> meta) {
        return new EntityJacksonViewModule<>(meta);
    }

    /**
     * Writes only the fields a tracking source reports as changed — the JSON shape of a partial
     * update (plan.dsflash § 7.2/2.4–2.5).
     *
     * <p>Per S4, a field that did not change is omitted entirely; a changed field whose value is
     * {@code null} is written as an explicit {@code null}.</p>
     *
     * <p>Each entry is the field's <strong>current</strong> value. The document is a JSON Merge
     * Patch, not an audit trail: the tracker keeps no previous value, so a caller that wants to
     * report "Ada → Grace" pairs this document with the baseline view it built the tracker from and
     * compares the two field by field.</p>
     */
    public static <V, F extends Enum<F> & FieldDef> void toJsonChanges(ViewMeta<V, F> meta,
                                                                      ViewChangeTracking<F, ?> tracking,
                                                                      java.io.Writer writer) {
        try (JsonGenerator gen = OBJECT_MAPPER.createGenerator(writer)) {
            new EntityJacksonChangeSerializer<>(meta).serialize(tracking, gen);
            gen.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize change set", e);
        }
    }

    /** The change-set serializer as a reusable instance, for hot paths and for module registration. */
    public static <V, F extends Enum<F> & FieldDef> EntityJacksonChangeSerializer<V, F> changeSerializer(ViewMeta<V, F> meta) {
        return new EntityJacksonChangeSerializer<>(meta);
    }

    /**
     * Writes the <strong>deep</strong> changes as a nested RFC 6902-like patch document, derived
     * from {@link ViewChangeTracking#changesDeep()} (plan.dsflash § 11/6.6).
     *
     * <p>Where {@link #toJsonChanges} reports "this field changed", this reports <em>where inside
     * it</em> the change is — including at a collection index — so a caller can patch a nested value
     * without replacing its parent. It is the deep counterpart, not a replacement: the shallow
     * document remains the right answer when a whole field was reassigned.</p>
     */
    public static <V, F extends Enum<F> & FieldDef> void toJsonDeepChanges(ViewMeta<V, F> meta,
                                                                          ViewChangeTracking<F, ?> tracking,
                                                                          java.io.Writer writer) {
        try (JsonGenerator gen = OBJECT_MAPPER.createGenerator(writer)) {
            // The serializer needs the field enum of the tracked source, not the view type parameter:
            // a deep patch is built from the tracking contract, so the meta's view type is free.
            new EntityJacksonDeepChangeSerializer<>(meta).serialize(tracking, gen);
            gen.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize deep change set", e);
        }
    }

    /** The deep-change serializer as a reusable instance. */
    public static <V, F extends Enum<F> & FieldDef> EntityJacksonDeepChangeSerializer<V, F> deepChangeSerializer(ViewMeta<V, F> meta) {
        return new EntityJacksonDeepChangeSerializer<>(meta);
    }

    public static <V extends EntityBase<?>, F extends Enum<F> & FieldDef> tools.jackson.databind.ObjectMapper registerModule(tools.jackson.databind.ObjectMapper mapper, ViewMeta<V, F> meta) {
        return mapper.rebuild().addModule(module(meta)).build();
    }
}
