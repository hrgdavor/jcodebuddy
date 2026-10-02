package hr.hrg.hipster.entity.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The checkable half of the ordinal contract (plan.dsflash § 4.2/D7, § 6.4 test 19) — the
 * <em>only</em> test in {@code hipster-entity-api}, which is where the check it exercises lives.
 *
 * <p>The plan specified this test as "a FieldDef enum whose declaration order was changed fails the
 * check". That formulation cannot be written: {@code fieldValues} <em>is</em>
 * {@code getEnumConstants()}, and {@link Enum#ordinal()} is final and declaration-ordered, so no
 * enum literal can have a "wrong" ordinal and any assertion about it is a tautology. The plan says so
 * itself, in the same paragraph that requires this test, and forbids re-adding the vacuous form in a
 * later consistency pass.</p>
 *
 * <p>So the test pins what the check can actually catch, and each case is a failure mode that has
 * occurred or is one typo away:</p>
 * <ul>
 *   <li>an <strong>empty</strong> field enum — a view with no readable layout;</li>
 *   <li>a {@code NAME_MAPPER} that <strong>lost an arm</strong> (returns {@code null} for a real
 *       constant) — F-34's class of defect, except that the ledger is not the thing that would
 *       silently rebuild here: every {@code meta.forName(name)} in a parse loop would return
 *       {@code null} and the field would be dropped from the payload with no diagnostic;</li>
 *   <li>a mapper whose arm was <strong>typo'd</strong> so a name resolves to the <em>wrong</em>
 *       constant — the worse variant, because it writes a value into the wrong slot;</li>
 *   <li>the happy path, so the check is not merely "everything throws".</li>
 * </ul>
 *
 * <p>Order itself is enforced at build time by {@code EnumConstantOrderChecker} (R1, DEC-023) and
 * witnessed at runtime by {@code allFields} in the metadata JSON — deliberately not here.</p>
 */
class DefaultViewMetaContractTest {

    @Test
    void aWellFormedFieldEnumAndMapperConstruct() {
        // Type arguments are explicit, exactly as the generated META constants write them: `V` appears
        // only in the constructor's parameters, so it cannot be inferred from a diamond.
        DefaultViewMeta<TestView, TwoField_> meta = new DefaultViewMeta<TestView, TwoField_>(
                TestView.class, TwoField_.class, TwoField_::forName, values -> null);

        Assertions.assertEquals(2, meta.fieldCount());
        Assertions.assertEquals("id", meta.fieldNameAt(0));
        Assertions.assertEquals(TwoField_.id, meta.forName().forName("id"));
        Assertions.assertEquals(TwoField_.name, meta.forName().forName("name"));
    }

    @Test
    void anEmptyFieldEnumFailsFast() {
        IllegalStateException thrown = Assertions.assertThrows(IllegalStateException.class,
                () -> new DefaultViewMeta<TestView, EmptyField_>(TestView.class, EmptyField_.class,
                        EmptyField_::forName, values -> null));

        Assertions.assertTrue(thrown.getMessage().contains("empty_field_enum"),
                "the diagnostic names the kind, per the DEC-022 format: " + thrown.getMessage());
        Assertions.assertTrue(thrown.getMessage().contains(EmptyField_.class.getName()),
                "and names the enum, so the message is actionable: " + thrown.getMessage());
    }

    @Test
    void aMapperThatLostAnArmFailsFastAndNamesTheConstant() {
        IllegalStateException thrown = Assertions.assertThrows(IllegalStateException.class,
                () -> new DefaultViewMeta<TestView, TwoField_>(TestView.class, TwoField_.class,
                        fieldName -> "name".equals(fieldName) ? TwoField_.name : null, values -> null));

        Assertions.assertTrue(thrown.getMessage().contains("name_map_not_lossless"),
                "a lost forName arm must be reported, not tolerated: " + thrown.getMessage());
        Assertions.assertTrue(thrown.getMessage().contains("\"id\""),
                "and must name the constant that failed to round-trip: " + thrown.getMessage());
        Assertions.assertTrue(thrown.getMessage().contains("null"),
                "saying what it resolved to instead: " + thrown.getMessage());
    }

    /**
     * The mis-resolving arm is the one that corrupts data rather than dropping it: the name is
     * accepted, so the parse loop writes the value, and it lands in another field's slot.
     */
    @Test
    void aMapperThatResolvesANameToTheWrongConstantFailsFast() {
        IllegalStateException thrown = Assertions.assertThrows(IllegalStateException.class,
                () -> new DefaultViewMeta<TestView, TwoField_>(TestView.class, TwoField_.class,
                        fieldName -> "id".equals(fieldName) ? TwoField_.name : TwoField_.id,
                        values -> null));

        Assertions.assertTrue(thrown.getMessage().contains("name_map_not_lossless"),
                "a name that resolves to the wrong constant is a persisted-layout corruption, not a "
                        + "round-trip detail: " + thrown.getMessage());
    }
}
