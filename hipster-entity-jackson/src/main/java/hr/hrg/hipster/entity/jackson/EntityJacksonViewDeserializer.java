package hr.hrg.hipster.entity.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.FieldNameMapper;
import hr.hrg.hipster.entity.api.ViewMeta;

import java.io.IOException;
import java.lang.reflect.Type;

/**
 * Zero-allocation (in hot path) Jackson deserializer for array-backed entity views.
 *
 * <p>Construct once per view type via {@link #EntityJacksonViewDeserializer(ViewMeta)};
 * the {@link ValueReader} array and interned field names are built eagerly at construction time
 * and reused across all calls. Per-call allocations are limited to:</p>
 * <ul>
 *   <li>{@code Object[fieldCount]} — field value staging array</li>
 *   <li>The view object produced by {@link ViewMeta#create}</li>
 * </ul>
 *
 * <p><strong>Field-name lookup</strong> uses interned-string identity ({@code ==}) as the
 * fast path. Jackson's {@code MappingJsonFactory} interns field names by default
 * ({@code JsonParser.Feature.INTERN_FIELD_NAMES}), so the linear scan over
 * {@code internedNames[]} is typically 1–2 comparisons. {@link ViewMeta#forName} is
 * the fallback for non-interned names.</p>
 *
 * <p><strong>Hot-path rule:</strong> do <em>not</em> construct a new instance inside a
 * tight parse loop. Construct once and reuse — see {@link EntityJacksonViewModule} for the
 * module-registered singleton pattern.</p>
 *
 * @param <V> the view interface type
 * @param <F> the companion field enum type
 */
public final class EntityJacksonViewDeserializer<V extends EntityBase<?>, F extends Enum<F> & FieldDef> {

    private interface ValueReader {
        Object read(JsonParser p) throws IOException;
    }

    private static ValueReader readerFor(Type type) {
        if (type == String.class) {
            return JsonParser::getText;
        }
        if (type == Long.class || type == long.class) {
            return JsonParser::getLongValue;
        }
        if (type == Integer.class || type == int.class) {
            return p -> Integer.valueOf(p.getIntValue());
        }
        if (type == Boolean.class || type == boolean.class) {
            return JsonParser::getBooleanValue;
        }
        if (type == Double.class || type == double.class) {
            return JsonParser::getDoubleValue;
        }
        if (type == Float.class || type == float.class) {
            return p -> Float.valueOf(p.getFloatValue());
        }
        // Fallback for complex types: ObjectReader cached per-field to avoid repeated type resolution.
        return new ValueReader() {
            private volatile ObjectReader cachedReader;

            @Override
            public Object read(JsonParser p) throws IOException {
                ObjectReader reader = cachedReader;
                if (reader == null) {
                    ObjectCodec codec = p.getCodec();
                    if (codec instanceof ObjectMapper om) {
                        reader = om.readerFor(om.getTypeFactory().constructType(type));
                    } else if (codec instanceof ObjectReader or) {
                        reader = or.forType(type);
                    } else {
                        ObjectMapper om = new ObjectMapper();
                        reader = om.readerFor(om.getTypeFactory().constructType(type));
                    }
                    cachedReader = reader;
                }
                return reader.readValue(p);
            }
        };
    }

    private final ViewMeta<V, F> meta;
    private final int fieldCount;
    private final ValueReader[] readers;
    /** Interned field names parallel to readers[]; enables fast {@code ==} identity check. */
    private final String[] internedNames;
    private FieldNameMapper<F> forName;

    /**
     * Builds the reader cache eagerly for the given view metadata.
     * Construct once per view type and reuse across all parse calls.
     *
     * @param meta view metadata supplying field types, name mapper, and view factory
     */
    public EntityJacksonViewDeserializer(ViewMeta<V, F> meta) {
        this.meta = meta;
        this.forName = meta.forName();
        this.fieldCount = meta.fieldCount();
        this.readers = new ValueReader[fieldCount];
        this.internedNames = new String[fieldCount];
        for (int i = 0; i < fieldCount; i++) {
            readers[i] = readerFor(meta.fieldTypeAt(i));
            internedNames[i] = meta.fieldNameAt(i).intern();
        }
    }

    /**
     * Deserializes a view from the current position in {@code p}.
     *
     * <p>Uses {@link JsonParser#nextFieldName()} to advance to each field name in a single
     * parser state transition (avoids a separate {@code currentName()} call per field).</p>
     *
     * @param p the parser, positioned at or before the {@code START_OBJECT} token
     * @return the deserialized view instance
     */
    public V deserialize(JsonParser p) throws IOException {
        Object[] values = new Object[fieldCount];

        JsonToken token = p.currentToken();
        if (token == null) {
            token = p.nextToken();
        }
        if (token != JsonToken.START_OBJECT) {
            throw new IllegalArgumentException("Expected JSON object");
        }

        String name;
        while ((name = p.nextFieldName()) != null) {
            JsonToken valueToken = p.nextToken(); // advance to value; capture to avoid redundant currentToken() call
            int ord = findOrdinal(name);
            if (ord >= 0) {
                values[ord] = valueToken == JsonToken.VALUE_NULL ? null : readers[ord].read(p);
            } else {
                p.skipChildren();
            }
        }

        return meta.create(values);
    }

    /**
     * Fast ordinal lookup: identity check on pre-interned names (fast path when Jackson
     * has {@code INTERN_FIELD_NAMES} enabled), then {@link ViewMeta#forName} switch fallback.
     */
    private int findOrdinal(String name) {
        final String[] names = internedNames;
        for (int i = 0, n = fieldCount; i < n; i++) {
            if (name == names[i]) return i;
        }
        F field = this.forName.forName(name);
        return field != null ? field.ordinal() : -1;
    }

    // ---------------------------------------------------------------------------
    // Removed: old no-arg constructor + deserialize(ViewMeta,JsonParser) and
    //          deserializeSwitch(ViewMeta,JsonParser) — rebuilt readers on every call.
    //          Use EntityJacksonViewDeserializer(ViewMeta) + deserialize(JsonParser) instead.
    // The legacy path is preserved in EntityJacksonMapper.fromJson(ViewMeta,JsonParser)
    // for one-off calls where construction cost is acceptable.
    // ---------------------------------------------------------------------------
    // Placeholder block deleted intentionally — see git history if needed.
    // ---------------------------------------------------------------------------

    /* legacy: kept for binary compatibility with any direct callers not yet migrated */
    @Deprecated
    public <V2 extends EntityBase<?>, F2 extends Enum<F2> & FieldDef> V2 deserialize(
            ViewMeta<V2, F2> legacyMeta, JsonParser p) throws IOException {
        // Construct a temporary typed deserializer — builds reader cache per call.
        // Migrate callers to EntityJacksonViewDeserializer(ViewMeta) + deserialize(JsonParser).
        return new EntityJacksonViewDeserializer<>(legacyMeta).deserialize(p);
    }

    @Deprecated
    public <V2 extends EntityBase<?>, F2 extends Enum<F2> & FieldDef> V2 deserializeSwitch(
            ViewMeta<V2, F2> legacyMeta, JsonParser p) throws IOException {
        return new EntityJacksonViewDeserializer<>(legacyMeta).deserialize(p);
    }

}
