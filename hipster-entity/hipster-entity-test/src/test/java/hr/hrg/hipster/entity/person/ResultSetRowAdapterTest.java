package hr.hrg.hipster.entity.person;

import hr.hrg.hipster.entity.api.ViewMeta;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The integration boundary of plan.dsflash § 7.1/2.3: a {@link ResultSet} row becomes the positional
 * {@code Object[]} the ordinal contract describes, and every accessor of the frozen {@code person}
 * fixture reads its own slot.
 *
 * <p>The contract this pins down —</p>
 * <pre>
 *   values[f.ordinal()] == the value of field f, for every f in the companion FieldDef enum
 *   values.length       == FieldDef.values().length
 *   column order comes from ViewMeta, never from SELECT *
 * </pre>
 * <p>— is "the only interface a real project's persistence layer has to implement" (§ 7.1). It is
 * asserted here rather than only in the tooling module because this is where the hand-written
 * fixture lives: a change to how the fixture declares its fields, or to the ordinal array it is read
 * from, has to break something visible.</p>
 *
 * <p>The adapter below is deliberately the ~15-line reference shape from
 * {@code user/patterns/jdbc-row-adapter.md}, not the generator's output. That is the point of the
 * task: the boundary is implementable by hand in a few lines, and the generated
 * {@code <View>RowAdapter} is a convenience over exactly this, not a new contract. The generated
 * version is exercised on its own in the tooling module's
 * {@code GeneratedAdapterRoundTripTest}.</p>
 */
class ResultSetRowAdapterTest {

    /**
     * The reference adapter: read every field by the name {@code ViewMeta} gives it.
     *
     * <p>{@code values.length} comes from {@code meta.fieldCount()} and each slot is filled from the
     * column whose name is {@code meta.fieldNameAt(i)}. There is no {@code SELECT *} assumption and no
     * reflection: the field order is the enum's, and a row that does not carry a column simply leaves
     * that slot {@code null} (S4 — absent is a legitimate state, not an error).</p>
     */
    private static Object[] fromResultSet(ResultSet rs, ViewMeta<?, ?> meta) throws Exception {
        Object[] values = new Object[meta.fieldCount()];
        for (int i = 0; i < values.length; i++) {
            values[i] = rs.getObject(meta.fieldNameAt(i));
        }
        return values;
    }

    /** A {@link ResultSet} that answers {@code getObject(String)} from a map and refuses anything else. */
    private static ResultSet fakeResultSet(Map<String, Object> columns) {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSetRowAdapterTest.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getObject" -> columns.get((String) args[0]);
                    case "toString" -> "FakeResultSet" + columns.keySet();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "the adapter called ResultSet." + method.getName()
                                    + "; the reference adapter reads only by column name");
                });
    }

    @Test
    void aResultSetRowBecomesOneSlotPerField() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 42L);
        row.put("firstName", "Ada");
        row.put("lastName", "Lovelace");
        row.put("departmentName", "Engineering");
        row.put("metadata", Map.of("role", List.of(1L)));

        Object[] values = fromResultSet(fakeResultSet(row), PersonSummary_.META);

        Assertions.assertEquals(PersonSummary_.values().length, values.length,
                "the array must be exactly as long as the field enum, so values[f.ordinal()] is "
                        + "total for every constant");
        Assertions.assertEquals(6, values.length, "id, firstName, lastName, age, departmentName, metadata");
    }

    @Test
    void everyAccessorReadsItsOwnSlot() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 42L);
        row.put("firstName", "Ada");
        row.put("lastName", "Lovelace");
        // departmentName deliberately absent: it is JOINED, so a row from the entity table has no
        // column for it and the adapter leaves the slot empty.
        row.put("metadata", Map.of("role", List.of(7L, 8L)));

        Object[] values = fromResultSet(fakeResultSet(row), PersonSummary_.META);
        PersonSummary view = PersonSummary_.META.create(values);

        // Every accessor, in enum order, asserted against the row rather than against the array, so a
        // slot swap shows up as a wrong value rather than as a consistent mistake.
        Assertions.assertEquals(42L, view.id());
        Assertions.assertEquals("Ada", view.firstName());
        Assertions.assertEquals("Lovelace", view.lastName());
        Assertions.assertEquals(Map.of("role", List.of(7L, 8L)), view.metadata());
        Assertions.assertNull(view.age(),
                "a DERIVED field still occupies its ordinal and may be left null by an adapter (§ 7.1)");
        Assertions.assertNull(view.departmentName(),
                "a JOINED field likewise: the ordinal exists, the column does not");
    }

    @Test
    void anAbsentColumnIsNotTheSameAsAnEmptyValue() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("firstName", null);
        row.put("lastName", "Lovelace");
        row.put("metadata", null);

        Object[] values = fromResultSet(fakeResultSet(row), PersonSummary_.META);
        PersonSummary view = PersonSummary_.META.create(values);

        Assertions.assertNull(view.firstName(), "an explicit SQL NULL becomes a null slot (S4)");
        Assertions.assertNull(view.metadata());
        Assertions.assertNull(values[PersonSummary_.age.ordinal()],
                "and a column the row never carried is indistinguishable from an explicit null in the "
                        + "array — the distinction the JSON serializer preserves lives above this "
                        + "boundary, not in it");
        Assertions.assertEquals("Lovelace", view.lastName(),
                "while its neighbours are unaffected: absent slots do not shift their successors");
    }

    @Test
    void theFieldNamesComeFromViewMetaNotFromColumnPosition() throws Exception {
        // A row whose map iteration order is the reverse of the enum order. The adapter is driven by
        // meta.fieldNameAt(i), so the slot each value lands in must be the enum's, not the map's.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metadata", Map.of());
        row.put("departmentName", "Engineering");
        row.put("age", 36);
        row.put("lastName", "Lovelace");
        row.put("firstName", "Ada");
        row.put("id", 9L);

        Object[] values = fromResultSet(fakeResultSet(row), PersonSummary_.META);
        PersonSummary view = PersonSummary_.META.create(values);

        Assertions.assertEquals(9L, view.id());
        Assertions.assertEquals("Ada", view.firstName());
        Assertions.assertEquals("Lovelace", view.lastName());
        Assertions.assertEquals(36, view.age());
        Assertions.assertEquals("Engineering", view.departmentName());
        Assertions.assertEquals(Map.of(), view.metadata());
    }
}
