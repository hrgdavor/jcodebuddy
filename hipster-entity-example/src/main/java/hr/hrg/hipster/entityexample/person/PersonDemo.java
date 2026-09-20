package hr.hrg.hipster.entityexample.person;

import hr.hrg.hipster.entity.jackson.EntityJacksonMapper;
import hr.hrg.hipster.entityexample.person.entity.PersonSummary;
import hr.hrg.hipster.entityexample.person.entity.PersonSummaryBuilderTracking;
import hr.hrg.hipster.entityexample.person.entity.PersonSummary_;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The end-to-end demo of {@code plan.dsflash.md} § 9/4.2: a row array in, a read view, JSON out, one
 * field changed, the changed fields with the values they hold now, and the change set as a JSON
 * Merge Patch.
 *
 * <p>Every step is ordinary, navigable Java: a developer can follow it from {@code main} to the
 * generated classes without running anything, which is what {@code AGENTS.md} § 1 / DEC-019
 * requires. Nothing here is reflective and no framework is involved.</p>
 *
 * <p>The SQL materialization the plan's § 12.1 sketched — generated {@code <View>RowAdapter} /
 * {@code <View>Binder} classes that read a {@code ResultSet} and build {@code INSERT}/{@code UPDATE}
 * fragments — is <strong>not</strong> part of this example. It is a draft/exploration in the tooling
 * behind the explicit {@code --adapters} flag (see the tooling README and
 * {@code user/patterns/jdbc-row-adapter.md}), and the example deliberately ships no generated SQL
 * classes until that exploration is settled.</p>
 *
 * <p>Run it with {@code scripts/run-demo.cmd} or directly:</p>
 * <pre>
 *   java -cp hipster-entity-example/target/classes:hipster-entity-core/target/classes:... \
 *        hr.hrg.hipster.entityexample.person.PersonDemo
 * </pre>
 */
public final class PersonDemo {

    /** One database row, in ordinal order: {@code values[field.ordinal()]}. */
    private static final Object[] ROW = {
            1L,                       // id
            "Ada",                    // firstName
            "Lovelace",               // lastName
            36,                       // age          (DERIVED — read-only)
            "Engineering",            // departmentName (JOINED — read-only)
            emptyMetadata()           // metadata
    };

    private static Map<String, List<Long>> emptyMetadata() {
        return new LinkedHashMap<>();
    }

    /**
     * The view as its positional array, in field-enum ordinal order.
     *
     * <p>This is the ordinal contract read out of the view's own accessors. It is written by hand
     * here for the demo; a real project generates it (or uses the array-backed proxy, which <em>is</em>
     * a {@code ViewReader} and needs no mapping).</p>
     */
    private static Object[] positional(PersonSummary view) {
        return new Object[]{
                view.id(),
                view.firstName(),
                view.lastName(),
                view.age(),
                view.departmentName(),
                view.metadata()
        };
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 1. a row array becomes a read view ==");

        // The ordinal contract: values[field.ordinal()] is that field, so the array can be handed
        // straight to the generated META, which builds the view through its own create().
        PersonSummary view = PersonSummary_.META.create(ROW);
        System.out.println("  read id=" + view.id()
                + " firstName=" + view.firstName()
                + " lastName=" + view.lastName()
                + " age=" + view.age()
                + " departmentName=" + view.departmentName());
        System.out.println("  fieldCount=" + PersonSummary_.META.fieldCount()
                + ", values.length=" + ROW.length
                + " -> equal: " + (PersonSummary_.META.fieldCount() == ROW.length));

        System.out.println();
        System.out.println("== 2. the view serializes to JSON ==");
        // PersonSummary is at BUILDER_ALL, so META.create() returns the concrete record rather than
        // an array-backed proxy. A record is not a ViewReader, so the view-based overload supplies
        // the positional array; the ordinal contract is the same one META.create() consumes.
        StringWriter full = new StringWriter();
        EntityJacksonMapper.toJson(PersonSummary_.META, view, PersonDemo::positional, full);
        System.out.println("  " + full);

        System.out.println();
        System.out.println("== 3. a tracking builder records one change ==");
        PersonSummaryBuilderTracking tracked = new PersonSummaryBuilderTracking(view);
        System.out.println("  fresh: isChanged=" + tracked.isChanged() + ", changes=" + tracked.changes());

        // One real change. The generated setter compares the value the field holds with the one being
        // assigned and marks the ordinal only when the two differ — the change set itself keeps no
        // values at all.
        tracked.firstName("Grace");
        System.out.println("  after firstName(\"Grace\"): isChanged=" + tracked.isChanged()
                + ", changes=" + tracked.changes());

        System.out.println();
        System.out.println("== 4. the changed fields, and the caller's own comparison ==");
        // changedValues() is one pair per changed field: the field and the value it holds now. There
        // is no "previous" half, deliberately: the old value belongs to the baseline instance the
        // caller built this mutable from, and the caller still holds it. That is the whole contract —
        // the tracker says *which* fields changed, the caller compares *what* changed.
        tracked.changedValues().forEach(change -> System.out.println("  " + change.fieldName()
                + " is now " + change.current()));
        System.out.println("  caller's comparison: firstName " + view.firstName() + " -> "
                + tracked.firstName() + "   (the baseline view is still the caller's object)");

        System.out.println();
        System.out.println("== 5. the change set as JSON ==");
        StringWriter patch = new StringWriter();
        EntityJacksonMapper.toJsonChanges(PersonSummary_.META, tracked, patch);
        System.out.println("  patch            : " + patch + "   (JSON Merge Patch: current values only)");

        // An audit-style old -> new document is a comparison of TWO instances, so the caller builds it
        // from the baseline it holds and the tracking view it just changed. No library state is
        // involved, which is exactly why the tracker does not need to keep the old value.
        System.out.println("  audit pair       : {\"firstName\":{\"previous\":\"" + view.firstName()
                + "\",\"current\":\"" + tracked.firstName() + "\"}}   (built by the caller)");

        // The change set is also the answer to "which columns would a partial UPDATE touch?" — one
        // marked ordinal per changed column, with no SQL involved. Turning those columns into SQL is
        // the tooling's draft exploration (`--adapters`), not this example's job.
        StringBuilder changedColumns = new StringBuilder();
        tracked.changesBuilder().forEach((field, index) -> {
            if (changedColumns.length() > 0) {
                changedColumns.append(", ");
            }
            changedColumns.append(field.name());
        });
        System.out.println("  changed columns  : " + changedColumns + "   (a partial write would touch these)");

        System.out.println();
        System.out.println("== 6. a no-op write changes nothing (DEC-012) ==");
        PersonSummaryBuilderTracking untouched = new PersonSummaryBuilderTracking(view);
        untouched.firstName("Ada"); // the baseline value
        System.out.println("  writing the same value: isChanged=" + untouched.isChanged()
                + ", patch=" + changeSetOf(untouched) + "   (nothing to send)");
    }

    private static String changeSetOf(PersonSummaryBuilderTracking tracked) {
        StringWriter out = new StringWriter();
        EntityJacksonMapper.toJsonChanges(PersonSummary_.META, tracked, out);
        return out.toString();
    }

    private PersonDemo() {
    }
}
