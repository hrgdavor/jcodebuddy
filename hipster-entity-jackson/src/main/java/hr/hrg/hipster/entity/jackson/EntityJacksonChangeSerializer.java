package hr.hrg.hipster.entity.jackson;

import tools.jackson.core.JsonGenerator;

import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.core.FieldChange;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Serializes a {@link ViewChangeTracking} source as a <strong>change set</strong>: only the fields
 * that actually changed (plan.dsflash § 7.2/2.4).
 *
 * <p>This is the visible payoff of Phase 1's tracking work — the piece that made a tracked partial
 * update expressible as JSON.</p>
 *
 * <h3>Field names</h3>
 * <p>Names come from {@code meta.fieldNameAt(ordinal)}, i.e. {@code FieldDef.name()}, which by the
 * naming contract <em>is</em> the accessor name. No name&rarr;ordinal {@code HashMap} is built:
 * iteration is positional over the marked ordinals the tracker reports (DEC-016).</p>
 *
 * <h3>Presence (S4)</h3>
 * <p>A field that did not change is not written at all — never as an explicit {@code null}. A null
 * that <em>is</em> written is the changed field's current value, which is meaningful on its own
 * (the field is now null); "it was not null before" is a statement about the caller's baseline
 * instance, not about this document.</p>
 *
 * <h3>Shape</h3>
 * <p>{@code {"firstName":"Grace","age":37}} — a JSON Merge Patch of the changed fields, carrying
 * their <strong>current</strong> values only. There is no {@code previous}/{@code current} mode,
 * because the tracker keeps no previous value: a consumer that needs old values is given the
 * baseline view by its caller (the instance this tracking view was built from) and compares the two
 * itself, field by field, with whatever equality its own domain requires.</p>
 */
public final class EntityJacksonChangeSerializer<V, F extends Enum<F> & FieldDef> {

    private static final byte TYPE_STRING = 1;
    private static final byte TYPE_INT = 2;
    private static final byte TYPE_LONG = 3;
    private static final byte TYPE_DOUBLE = 4;
    private static final byte TYPE_FLOAT = 5;
    private static final byte TYPE_BOOLEAN = 6;
    private static final byte TYPE_OBJECT = 7;

    private final String[] fieldNames;
    private final byte[] fieldTypeCode;

    public EntityJacksonChangeSerializer(ViewMeta<V, F> meta) {
        F[] fields = meta.fieldValues();
        this.fieldNames = new String[fields.length];
        this.fieldTypeCode = new byte[fields.length];
        for (int i = 0; i < fields.length; i++) {
            fieldNames[i] = fields[i].name();
            fieldTypeCode[i] = typeCodeOf(fields[i].javaType());
        }
    }

    private static byte typeCodeOf(Type type) {
        if (type == String.class) {
            return TYPE_STRING;
        }
        if (type == Integer.class || type == int.class) {
            return TYPE_INT;
        }
        if (type == Long.class || type == long.class) {
            return TYPE_LONG;
        }
        if (type == Double.class || type == double.class) {
            return TYPE_DOUBLE;
        }
        if (type == Float.class || type == float.class) {
            return TYPE_FLOAT;
        }
        if (type == Boolean.class || type == boolean.class) {
            return TYPE_BOOLEAN;
        }
        return TYPE_OBJECT;
    }

    /** Writes only the changed fields, each with the value it holds now. */
    public void serialize(ViewChangeTracking<F, ?> tracking, JsonGenerator gen) throws IOException {
        gen.writeStartObject();

        List<FieldChange<F>> changes = tracking.changedValues();
        for (FieldChange<F> change : changes) {
            int ordinal = change.field().ordinal();
            if (isRetired(change.field())) {
                continue; // § 4.6/R1.4: a retired tombstone is never written
            }
            gen.writeName(fieldNames[ordinal]);
            writeValue(gen, fieldTypeCode[ordinal], change.current());
        }

        gen.writeEndObject();
    }

    /**
     * Whether the constant is an R1.4 tombstone. Checked reflectively against the
     * {@code FieldDef.retired()} default so this module keeps compiling against a {@code FieldDef}
     * that predates the accessor.
     */
    private static boolean isRetired(FieldDef field) {
        return field.retired();
    }

    /**
     * Writes one field value with this view's per-ordinal type code (the type code decides whether the
     * value is written as a scalar, a boolean or a POJO).
     *
     * <p>Public so {@link EntityJacksonDeepChangeSerializer} can reuse the exact same value-writing
     * rules for a deep leaf; a second implementation would be a second place for the two serializers
     * to disagree.</p>
     */
    public void writeValueFor(JsonGenerator gen, int ordinal, Object value) throws IOException {
        writeValue(gen, fieldTypeCode[ordinal], value);
    }

    private void writeValue(JsonGenerator gen, byte typeCode, Object value) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        switch (typeCode) {
            case TYPE_STRING -> gen.writeString((String) value);
            case TYPE_INT -> gen.writeNumber(((Number) value).intValue());
            case TYPE_LONG -> gen.writeNumber(((Number) value).longValue());
            case TYPE_DOUBLE -> gen.writeNumber(((Number) value).doubleValue());
            case TYPE_FLOAT -> gen.writeNumber(((Number) value).floatValue());
            case TYPE_BOOLEAN -> gen.writeBoolean((Boolean) value);
            default -> gen.writePOJO(value);
        }
    }
}
