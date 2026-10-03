package hr.hrg.jcodebuddy.engine.index;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * DEC-040's D4, as one mechanical test rather than a habit: **every field the format declares is either emitted
 * for an empty row, or it is one of the two documented omissions.**
 *
 * <p>The rule it enforces is the one this model has now stated three times (3.0b's relations, the annotations
 * field, 3.0r's members): *not recorded* must not read as *none*. A field that a writer emits only when it has
 * something to say turns "this table was written before the field existed" into "the source declares none of
 * it" — a silent lie that no round-trip test catches, because a round trip through one version agrees with
 * itself.</p>
 *
 * <p>So this test reads the <strong>records</strong>, not a list somebody maintains by hand: it walks the
 * components of {@link ClassRecord}, {@link MemberRecord}, {@link MemberParameter} and {@link TypeRelation} and
 * asserts each name appears in the written JSON for a row that is deliberately empty. Adding a component
 * without deciding that question fails here, which is the only way the rule survives contact with the next
 * step.</p>
 */
class IndexFormatContractTest {

    /**
     * The two components a row may omit, both from DEC-029 § 3 and both because they carry no information for
     * the common case: {@code generated} is written only when true, {@code enclosing} only when non-null.
     * Listed here rather than inferred, so a third one has to be argued for in a diff.
     */
    private static final Set<String> ROW_FIELDS_THAT_MAY_BE_OMITTED = Set.of("generated", "enclosing");

    /** {@code fqn} is the row's key, so it is written as a JSON property name rather than as a field. */
    private static final Set<String> ROW_FIELDS_WRITTEN_AS_A_KEY = Set.of("fqn");

    private static ClassIndex indexOf(Path tree) {
        return ClassIndex.forPass(tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
    }

    @Test
    void everyRowFieldIsEmittedOrDeliberatelyConditional(@TempDir Path tree) throws IOException {
        String written = writtenEmptyRow(tree, true);

        List<String> problems = new ArrayList<>();
        for (RecordComponent component : ClassRecord.class.getRecordComponents()) {
            String field = component.getName();
            if (ROW_FIELDS_WRITTEN_AS_A_KEY.contains(field)) {
                Assertions.assertTrue(written.contains("\"a.b.Empty\""),
                        "the key this component is written as must be present: " + field);
                continue;
            }
            boolean emitted = written.contains("\"" + field + "\"");
            if (ROW_FIELDS_THAT_MAY_BE_OMITTED.contains(field)) {
                if (emitted) {
                    problems.add(field + " is in the omit-when-empty list but was emitted for an empty row;"
                            + " either it is not conditional or the list is wrong");
                }
                continue;
            }
            if (!emitted) {
                problems.add(field + " is a field of ClassRecord and is NOT emitted for an empty row, so a table"
                        + " without it reads as 'none' rather than 'not recorded' (DEC-040 D4). Decide: always"
                        + " emit it, or add it to the documented omissions with a reason");
            }
        }
        Assertions.assertTrue(problems.isEmpty(), String.join("\n  ", problems) + "\n\nrow written: " + written);
    }

    @Test
    void everyMemberFieldIsEmitted(@TempDir Path tree) throws IOException {
        String written = writtenEmptyRow(tree, false);
        int memberStart = written.indexOf("\"members\"");
        Assertions.assertTrue(memberStart > 0, "the fixture must write a member: " + written);
        String member = written.substring(memberStart);

        List<String> problems = new ArrayList<>();
        for (RecordComponent component : MemberRecord.class.getRecordComponents()) {
            if (!member.contains("\"" + component.getName() + "\"")) {
                problems.add("MemberRecord." + component.getName() + " is not emitted for a member, so a table"
                        + " without it reads as 'none' (DEC-040 D4)");
            }
        }
        for (RecordComponent component : MemberParameter.class.getRecordComponents()) {
            if (!member.contains("\"" + component.getName() + "\"")) {
                problems.add("MemberParameter." + component.getName() + " is not emitted for a parameter"
                        + " (DEC-040 D4)");
            }
        }
        for (RecordComponent component : TypeRelation.class.getRecordComponents()) {
            if (!written.contains("\"" + component.getName() + "\"")) {
                problems.add("TypeRelation." + component.getName() + " is not emitted for a relation"
                        + " (DEC-040 D4)");
            }
        }
        Assertions.assertTrue(problems.isEmpty(), String.join("\n  ", problems));
    }

    /**
     * Writes one index whose row (and, unless {@code emptyMember} is false, whose single member) says as little
     * as the format allows, and returns the file's text.
     */
    private static String writtenEmptyRow(Path tree, boolean noMember) throws IOException {
        Path file = tree.resolve("module/a/b/Empty.java");
        Files.createDirectories(file.getParent());
        String source = "package a.b;\n\npublic class Empty {\n}\n";
        Files.writeString(file, source, StandardCharsets.UTF_8);

        List<MemberRecord> members = noMember ? List.of()
                : List.of(new MemberRecord("only", MemberRecord.Kind.FIELD, "", List.of(), List.of(), List.of()));
        ClassIndex index = indexOf(tree);
        index.addTypes("a/b/Empty.java", List.of(new TypeFacts("a.b.Empty", "class", List.of(), null, 3, 0,
                List.of(TypeRelation.extendsType("Base")), List.of(), members)), false);
        index.write();
        return Files.readString(index.indexFile(), StandardCharsets.UTF_8);
    }
}
