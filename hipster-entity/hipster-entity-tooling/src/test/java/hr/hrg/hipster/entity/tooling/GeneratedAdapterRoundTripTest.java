package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entity.api.ViewReader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Phase 7.4 acceptance test (plan.dsflash § 12.1/7.4): a fake {@link ResultSet} round-trips a row
 * through the <strong>generated</strong> reader → view → binder → a fake {@link PreparedStatement}.
 *
 * <p>The sibling {@code ViewAdapterGeneratorTest} asserts the emitted source text and that it
 * compiles. That is necessary and not sufficient: it cannot tell whether the emitted
 * {@code ORDINALS} array addresses the right slots, which is the one thing about a positional adapter
 * that a compiler is blind to. This test runs the generated classes.</p>
 *
 * <h3>The fixture deliberately contains both exclusions at once</h3>
 * <p>The view is generated twice: first with {@code firstName, lastName, email, age}, then with
 * {@code lastName} deleted from the interface. That leaves the ordinal ledger as</p>
 * <pre>
 *   id=0, firstName=1, lastName=2 (retired tombstone), email=3, age=4 (DERIVED)
 * </pre>
 * <p>and the binder must therefore emit {@code COLUMNS = {"id", "firstName", "email"}} with
 * {@code ORDINALS = {0, 1, 3}}. A tombstone has no accessor left to carry {@code @FieldSource}, so it
 * reports {@code COLUMN} and {@code FieldKind} alone cannot exclude it — the ordinal is what
 * distinguishes it, which is exactly what this test checks and a text assertion cannot.</p>
 *
 * <p>The reader, by contrast, fills <em>every</em> slot including the tombstone's and the derived
 * field's: {@code values.length == fieldCount} is the contract, and R1.4 says a tombstone slot is
 * still read when the row carries it.</p>
 *
 * <p>No database, driver or network is involved. The fakes are {@link Proxy} doubles that implement
 * only the methods the generated code calls and throw for anything else, so a change in what the
 * adapter calls shows up as a failure rather than as a silently ignored interaction.</p>
 */
class GeneratedAdapterRoundTripTest {

    private static final String MARKER = """
            package rt.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /** The first revision: four accessors, one of them DERIVED. */
    private static final String VIEW_BEFORE = """
            package rt.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            import hr.hrg.hipster.entity.api.View;
            @View
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
                String email();
                @FieldSource(kind = FieldKind.DERIVED, expression = "COUNT(*)")
                Integer age();
            }
            """;

    /** The second revision: {@code lastName} removed, so its constant becomes a tombstone at 2. */
    private static final String VIEW_AFTER = """
            package rt.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            import hr.hrg.hipster.entity.api.View;
            @View
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String email();
                @FieldSource(kind = FieldKind.DERIVED, expression = "COUNT(*)")
                Integer age();
            }
            """;

    /** The emitted ordinal layout the assertions are written against. */
    private static final int ID = 0;
    private static final int FIRST_NAME = 1;
    private static final int LAST_NAME_TOMBSTONE = 2;
    private static final int EMAIL = 3;
    private static final int AGE_DERIVED = 4;

    private record Generated(Path tree, Path classes, Class<?> view, Class<?> enumClass,
                             Class<?> rowAdapter, Class<?> binder, Object meta, int fieldCount) {
    }

    /**
     * Generates in place (twice, to create the tombstone), compiles the tree, and loads the generated
     * classes in a child loader.
     */
    private Generated generateCompileAndLoad() throws Exception {
        Path tree = Files.createTempDirectory("adapter-rt");
        Path packageDir = tree.resolve("rt/hr");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("PersonEntity.java"), MARKER);
        Files.writeString(packageDir.resolve("PersonSummary.java"), VIEW_BEFORE);

        EntityMetadataGenerator.setGenerateAdapters(true);
        try {
            // In place, because the ledger is read from the file the next pass is about to replace.
            EntityMetadataGenerator.generate(tree, tree);
            Files.writeString(packageDir.resolve("PersonSummary.java"), VIEW_AFTER);
            EntityMetadataGenerator.generate(tree, tree);
        } finally {
            EntityMetadataGenerator.setGenerateAdapters(false);
        }

        Path classes = CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "adapter round trip",
                CompileHarness.javaSourcesUnder(tree), List.of());

        // The generated classes must be loaded together with the tree they were compiled from, and
        // with `api`/`core` from the parent loader so ViewMeta and ViewReader are the same types this
        // test holds.
        java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] { classes.toUri().toURL() }, getClass().getClassLoader());
        Class<?> view = loader.loadClass("rt.hr.PersonSummary");
        Class<?> enumClass = loader.loadClass("rt.hr.PersonSummary_");
        Object meta = enumClass.getField("META").get(null);
        int fieldCount = ((Object[]) enumClass.getMethod("values").invoke(null)).length;
        return new Generated(tree, classes, view, enumClass,
                loader.loadClass("rt.hr.PersonSummaryRowAdapter"),
                loader.loadClass("rt.hr.PersonSummaryBinder"), meta, fieldCount);
    }

    /** A {@link ResultSet} that answers {@code getObject(String)} from a map and refuses everything else. */
    private static ResultSet fakeResultSet(Map<String, Object> columns) {
        return (ResultSet) Proxy.newProxyInstance(
                GeneratedAdapterRoundTripTest.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getObject" -> columns.get((String) args[0]);
                    case "toString" -> "FakeResultSet" + columns.keySet();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "the generated reader called ResultSet." + method.getName()
                                    + ", which the fake does not implement");
                });
    }

    /** A {@link PreparedStatement} that records what the binder wrote. */
    private static final class FakeStatement {
        final List<String> writes = new ArrayList<>();

        PreparedStatement proxy() {
            return (PreparedStatement) Proxy.newProxyInstance(
                    GeneratedAdapterRoundTripTest.class.getClassLoader(),
                    new Class<?>[] { PreparedStatement.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setObject" -> {
                            writes.add("object(" + args[0] + ")=" + args[1]);
                            yield null;
                        }
                        case "setNull" -> {
                            writes.add("null(" + args[0] + ")");
                            yield null;
                        }
                        case "toString" -> "FakePreparedStatement";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                "the generated binder called PreparedStatement." + method.getName()
                                        + ", which the fake does not implement");
                    });
        }
    }

    private static Object[] read(Generated generated, ResultSet rs) throws Exception {
        return (Object[]) generated.rowAdapter()
                .getMethod("fromResultSet", ResultSet.class, ViewMeta.class)
                .invoke(null, rs, generated.meta());
    }

    /**
     * The binder is driven by the ordinal array, so it needs a {@link ViewReader} and nothing else —
     * which is exactly what the generated binder's signature says. The array is built over the
     * <em>loaded</em> field enum, so its length check is the loaded enum's constant count.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ViewReader readArray(Generated generated, Object[] values) {
        return new hr.hrg.hipster.entity.core.EntityReadArray(generated.enumClass(), values);
    }

    private static void bind(Generated generated, PreparedStatement ps, int startIndex, ViewReader view)
            throws Exception {
        generated.binder()
                .getMethod("bind", PreparedStatement.class, int.class, ViewReader.class)
                .invoke(null, ps, startIndex, view);
    }

    @Test
    void aRowIsReadIntoOneSlotPerFieldAndEveryAccessorAgrees() throws Exception {
        Generated generated = generateCompileAndLoad();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 7L);
        row.put("firstName", "Ada");
        row.put("lastName", null);
        row.put("email", "ada@example.test");
        row.put("age", null);

        Object[] values = read(generated, fakeResultSet(row));

        Assertions.assertEquals(generated.fieldCount(), values.length,
                "values.length must equal the field enum's constant count, tombstones included — that "
                        + "is the ordinal contract's first rule");
        Assertions.assertEquals(5, values.length,
                "id, firstName, lastName(tombstone), email, age(derived)");

        Object view = generated.meta().getClass().getMethod("create", Object[].class)
                .invoke(generated.meta(), (Object) values);
        Assertions.assertEquals(7L, generated.view().getMethod("id").invoke(view));
        Assertions.assertEquals("Ada", generated.view().getMethod("firstName").invoke(view));
        Assertions.assertEquals("ada@example.test", generated.view().getMethod("email").invoke(view));
        Assertions.assertNull(generated.view().getMethod("age").invoke(view),
                "a DERIVED field the row cannot fill stays null (S4)");

        // The tombstone slot exists and is readable positionally, but the interface no longer
        // declares an accessor for it — which is what makes it a tombstone rather than a field.
        Assertions.assertNull(values[LAST_NAME_TOMBSTONE]);
        Assertions.assertThrows(NoSuchMethodException.class,
                () -> generated.view().getMethod("lastName"),
                "the retired accessor is gone from the view, and only the slot remains");
    }

    @Test
    void aNullColumnStaysNullInsteadOfBeingCoerced() throws Exception {
        Generated generated = generateCompileAndLoad();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", null);
        row.put("firstName", null);
        row.put("lastName", null);
        row.put("email", null);
        row.put("age", null);

        Object[] values = read(generated, fakeResultSet(row));

        for (int i = 0; i < values.length; i++) {
            Assertions.assertNull(values[i], "slot " + i + " is absent, not a default");
        }
        // S4: absent is a real state, so create() must accept it rather than reject the row.
        Object view = generated.meta().getClass().getMethod("create", Object[].class)
                .invoke(generated.meta(), (Object) values);
        Assertions.assertNull(generated.view().getMethod("id").invoke(view));
        Assertions.assertNull(generated.view().getMethod("firstName").invoke(view));
    }

    @Test
    void theBinderWritesOnlyWritableNonNullTombstonedFieldsInOrdinalOrder() throws Exception {
        Generated generated = generateCompileAndLoad();

        Assertions.assertArrayEquals(new String[] { "id", "firstName", "email" },
                (String[]) generated.binder().getField("COLUMNS").get(null),
                "the tombstone and the DERIVED field are both excluded — and FieldKind alone could "
                        + "not exclude the tombstone, because a retired constant has no accessor left "
                        + "to carry @FieldSource");
        Assertions.assertArrayEquals(new int[] { ID, FIRST_NAME, EMAIL },
                (int[]) generated.binder().getField("ORDINALS").get(null),
                "and the surviving email column is read from ordinal 3, not from the tombstone's 2");

        Object[] values = new Object[generated.fieldCount()];
        values[ID] = 7L;
        values[FIRST_NAME] = "Ada";
        values[LAST_NAME_TOMBSTONE] = "SHOULD-NEVER-BE-WRITTEN";
        values[EMAIL] = "ada@example.test";
        values[AGE_DERIVED] = 999;

        FakeStatement statement = new FakeStatement();
        bind(generated, statement.proxy(), 1, readArray(generated, values));

        Assertions.assertEquals(List.of("object(1)=7", "object(2)=Ada", "object(3)=ada@example.test"),
                statement.writes,
                "exactly three writes, in ordinal order, starting at the caller's index — so a "
                        + "PreparedStatement plan stays stable and neither the tombstone nor the "
                        + "derived value can reach a column");
    }

    @Test
    void aNullFieldIsWrittenAsSqlNullRatherThanSkipped() throws Exception {
        Generated generated = generateCompileAndLoad();
        Object[] values = new Object[generated.fieldCount()];
        values[ID] = 7L;
        values[FIRST_NAME] = null;
        values[EMAIL] = "ada@example.test";

        FakeStatement statement = new FakeStatement();
        bind(generated, statement.proxy(), 1, readArray(generated, values));

        Assertions.assertEquals(List.of("object(1)=7", "null(2)", "object(3)=ada@example.test"),
                statement.writes,
                "a null column is written as SQL NULL in its own position; skipping it would shift "
                        + "every later parameter and silently update the wrong column");
    }

    @Test
    void theSqlFragmentsNameExactlyTheWritableColumns() throws Exception {
        Generated generated = generateCompileAndLoad();

        String insert = (String) generated.binder().getMethod("insertSql", String.class)
                .invoke(null, "person");
        Assertions.assertEquals("INSERT INTO person (id, firstName, email) VALUES (?, ?, ?)", insert);
        Assertions.assertFalse(insert.contains("age"), "the DERIVED field is never a column: " + insert);
        Assertions.assertFalse(insert.contains("lastName"),
                "and neither is the tombstone: " + insert);

        // The partial-update path is the natural consumer of the change set: only changed ordinals.
        boolean[] changed = new boolean[generated.fieldCount()];
        changed[EMAIL] = true;
        String update = (String) generated.binder()
                .getMethod("updateSql", String.class, boolean[].class)
                .invoke(null, "person", changed);
        Assertions.assertEquals("UPDATE person SET email = ?", update,
                "only the changed column appears");

        boolean[] untouched = new boolean[generated.fieldCount()];
        Assertions.assertNull(generated.binder()
                        .getMethod("updateSql", String.class, boolean[].class)
                        .invoke(null, "person", untouched),
                "with nothing changed there is no UPDATE to send");

        // A change to the tombstone's ordinal must not produce an UPDATE either: there is no column
        // for it, and emitting one would be a write to a field the schema no longer has.
        boolean[] tombstoneChanged = new boolean[generated.fieldCount()];
        tombstoneChanged[LAST_NAME_TOMBSTONE] = true;
        Assertions.assertNull(generated.binder()
                        .getMethod("updateSql", String.class, boolean[].class)
                        .invoke(null, "person", tombstoneChanged),
                "a change flagged on a retired ordinal produces no SQL at all");
    }

    @Test
    void theBinderIsDrivenByTheOrdinalArrayNotByTheColumnsPosition() throws Exception {
        Generated generated = generateCompileAndLoad();
        Object[] values = new Object[generated.fieldCount()];
        // Deliberately distinct values so a swap is visible: if the binder used the column's index
        // instead of ORDINALS[i], email's value would land in firstName's position.
        values[FIRST_NAME] = "first";
        values[EMAIL] = "email";

        FakeStatement statement = new FakeStatement();
        bind(generated, statement.proxy(), 1, readArray(generated, values));

        Assertions.assertEquals(List.of("null(1)", "object(2)=first", "object(3)=email"),
                statement.writes,
                "the third write carries ORDINALS[2] == 3, i.e. email's slot, not the third column's "
                        + "own index");
    }
}
