package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The user-facing documentation against the code it documents.
 *
 * <p>This class exists because the plan's own DoD #12 — <em>"Docs state reality. No doc claims a
 * materialization level that does not exist"</em> — was false when the execution round closed. A
 * documentation audit found roughly forty mismatches, among them two outright materialization-level
 * errors and several examples that could not compile: {@code @View(read = …, write = …)} (attributes
 * that do not exist), a field enum with a {@code column()} override on a {@code DERIVED} field (the
 * generator emits none), {@code meta.forName(name)} (the API is {@code meta.forName().forName(name)}),
 * {@code PersonBuilder.create()} (no such factory), and a tracking-array factory call that passes the
 * wrong type.</p>
 *
 * <p>Documentation cannot be compiled, so the tests below assert the <strong>specific claims that were
 * wrong</strong> rather than trying to verify prose. Each one is a shape a reader copies verbatim, and
 * each one is checked against the artifact that proves it: the generated example, the API source, the
 * launcher, or the POM. The pattern is the one this project keeps rediscovering (F-43, F-47): a rule
 * that is true but unasserted decays into a rule that is false.</p>
 */
class DocConformanceTest {

    private static Path repoRoot() {
        return CompileHarness.findRepoRoot();
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(repoRoot().resolve(relative)), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    /**
     * The {@code column()} rule, as the docs must state it and as the example must show it: the
     * generator emits a resolved column name for every {@code COLUMN} field, and emits
     * <strong>nothing</strong> for a {@code DERIVED}/{@code JOINED} field — which keeps the
     * {@link hr.hrg.hipster.entity.api.FieldDef} default of {@code null} and is what stops an adapter
     * from trying to write a column that cannot hold the value.
     *
     * <p>The audit found the getting-started guide showing the exact opposite (a {@code column()}
     * override returning the field name on {@code age} and {@code departmentName}), in the same
     * repository whose pattern page states the rule correctly.</p>
     */
    @Test
    void theGeneratedExampleEmitsColumnOnlyForColumnFields() throws Exception {
        String enumSource = read("hipster-entity-example/src/main/java/hr/hrg/hipster/entityexample/"
                + "person/entity/PersonSummary_.java");

        // The constant's body is extracted by brace matching, not by a regex: the previous version
        // anchored on the argument list `[^)]*`, which cannot match
        // `metadata(TypeUtils.parameterizedType(...))` because the nested parentheses end the match
        // early — and a `.*?\\{` relaxation instead ran *past* the constants and swallowed the one
        // after them. Both mistakes made the assertion fail on a file that is correct.
        for (String columnField : new String[] {"id", "firstName", "lastName", "metadata"}) {
            Assertions.assertTrue(constantBody(enumSource, columnField).contains("String column()"),
                    columnField + " is a COLUMN field, so its constant must override column() with a "
                            + "resolved name");
        }
        for (String nonColumn : new String[] {"age", "departmentName"}) {
            Assertions.assertFalse(constantBody(enumSource, nonColumn).contains("column()"),
                    nonColumn + " is not a COLUMN field: emitting a column() override would tell an "
                            + "adapter there is a column to write, which is the opposite of the rule");
        }
    }

    /**
     * The text of one enum constant's class body, found by locating {@code <name>(} at the start of a
     * line and matching braces from the first {@code &#123;}.
     */
    private static String constantBody(String enumSource, String constantName) {
        var start = Pattern.compile("(?m)^\\s*" + constantName + "\\(").matcher(enumSource);
        Assertions.assertTrue(start.find(), "the enum must declare a constant " + constantName);
        int open = enumSource.indexOf('{', start.end());
        Assertions.assertTrue(open > 0, constantName + " must have a class body");
        int depth = 0;
        for (int i = open; i < enumSource.length(); i++) {
            char c = enumSource.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return enumSource.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced braces in the constant " + constantName);
    }

    /** And the guide must not teach the inverse. */
    @Test
    void theGettingStartedGuideShowsNoColumnOverrideOnDerivedOrJoinedFields() throws Exception {
        String guide = read("doc-hipster-entity/user/getting-started-new-project.md");

        Assertions.assertTrue(guide.contains("is emitted for `COLUMN` fields only"),
                "the guide must state the column() rule; read " + guide.length() + " bytes");
        // The wrong shape, verbatim from the audited revision: a DERIVED/JOINED constant body whose
        // column() returns the accessor name. Java comments are stripped first, because the corrected
        // guide deliberately mentions the removed override *in a comment* to explain why it is absent.
        String codeOnly = guide
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        Assertions.assertFalse(
                Pattern.compile("(?s)FieldKind\\.(DERIVED|JOINED)[^}]*column\\(\\)").matcher(codeOnly).find(),
                "a DERIVED/JOINED constant must not be shown overriding column(); the generator emits "
                        + "no such override and FieldDef's default (null) is the correct answer");
    }

    /**
     * The documented header is the emitted header, and the emitted header is a DEC-035 marker.
     *
     * <p>DEC-021 fixed the two-line shape; DEC-035 put the file marker on line 1 so a parser or an agent
     * can recognise a wholly-generated file by its first line. Both halves are asserted here because the
     * guide prints this header verbatim, and a guide that shows a shape the generator no longer emits is
     * worse than no guide.
     */
    @Test
    void theDocumentedHeaderMatchesTheEmittedHeader() throws Exception {
        String emitted = read("hipster-entity-example/src/main/java/hr/hrg/hipster/entityexample/"
                + "person/entity/PersonSummary_.java");
        String[] lines = emitted.split("\n");
        Assertions.assertTrue(lines.length > 2, "the emitted enum has a header");
        Assertions.assertTrue(lines[0].startsWith("// @generated file "),
                "DEC-035 line 1 is the file marker, which is what makes the file recognisable: " + lines[0]);
        Assertions.assertTrue(lines[1].contains("entityFieldEnum:true"),
                "line 2 is the JSON5 config and carries the R1 marker: " + lines[1]);

        // The marker must be one this project's own vocabulary recognises, and it must resolve to the
        // generator — otherwise the documentation and the recogniser have drifted apart.
        GeneratedCodeMarkers.Found found = GeneratedCodeMarkers.recognise(1, lines[0]).orElseThrow();
        Assertions.assertEquals(GeneratedCodeMarkers.SCOPE_FILE, found.scope());
        Assertions.assertTrue(found.supported());
        Assertions.assertEquals("hr.hrg.hipster.entity.tooling.EntityMetadataGenerator",
                GeneratedCodeMarkers.generatorOfFileMarker(java.util.List.of(lines)).orElseThrow(),
                "the marker names the generator a reader jumps to, and a parser may ignore");

        String guide = read("doc-hipster-entity/user/getting-started-new-project.md");
        Assertions.assertTrue(guide.contains("// {enabled:true, entityFieldEnum:true, blockMarker: \"implicit\"}"),
                "the guide must show the config line it will actually get");
        Assertions.assertTrue(guide.contains("// @generated file "),
                "and the file marker, because that is the line a parser reads");
    }

    /**
     * The tracking-array factory's first parameter is a {@code ForNameOrdinal}, not the field enum.
     * The core README's snippet passed {@code PersonSummary_::forName}, which does not compile: the
     * enum's {@code forName} returns a constant while the factory needs
     * {@code int forNameOrdinal(String)}.
     */
    @Test
    void theCoreReadmeTrackingArrayExampleUsesTheRealFactorySignature() throws Exception {
        String readme = read("hipster-entity-core/README.md");
        Assertions.assertTrue(readme.contains("EntityUpdateTrackingArray.create("),
                "the README documents the factory");
        Assertions.assertFalse(readme.contains("EntityUpdateTrackingArray.create(PersonSummary_::forName,"),
                "the first argument is a ForNameOrdinal; `PersonSummary_::forName` returns the constant "
                        + "type and cannot be passed there. Pass META, which implements ForNameOrdinal.");
        Assertions.assertTrue(readme.contains("EntityUpdateTrackingArray.create(PersonSummary_.META,"),
                "and the corrected snippet must be the one a reader copies");

        // The signature itself, so the doc cannot drift from the code silently.
        String source = read("hipster-entity-core/src/main/java/hr/hrg/hipster/entity/core/"
                + "EntityUpdateTrackingArray.java");
        Assertions.assertTrue(
                Pattern.compile("create\\(ForNameOrdinal \\w+, F\\[\\] \\w+, Object\\.\\.\\. \\w+\\)")
                        .matcher(source).find(),
                "the factory takes (ForNameOrdinal, F[] universe, Object... values)");
    }

    /**
     * {@code @View} has exactly three attributes. The audited docs used a {@code read}/{@code write}
     * pair that never existed — the same phantom attributes the original validator string-matched for,
     * which is why it rejected every real {@code @View}.
     */
    @Test
    void noDocumentedViewAnnotationInventsReadOrWriteAttributes() throws Exception {
        Path docs = repoRoot().resolve("doc-hipster-entity");
        Pattern phantom = Pattern.compile("@View\\s*\\([^)]*\\b(read|write)\\s*=");
        StringBuilder offenders = new StringBuilder();
        try (var walk = Files.walk(docs)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".md")).toList()) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                // Quoted lines, fenced code blocks and inline-code mentions are all skipped: a
                // *correction* has to be able to say what the old text was, and every place that fixes
                // this defect does exactly that. Without these carve-outs the test reports its own
                // corrections as the defect — the fourth time in this project that a check was written
                // against the wrong thing (F-38, F-43, N-4, N-6).
                StringBuilder live = new StringBuilder();
                boolean inFence = false;
                for (String line : text.split("\n", -1)) {
                    String stripped = line.stripLeading();
                    if (stripped.startsWith("```")) {
                        inFence = !inFence;
                        continue;
                    }
                    if (inFence || stripped.startsWith(">") || stripped.contains("`@View")) {
                        continue;
                    }
                    live.append(line).append('\n');
                }
                if (phantom.matcher(live.toString()).find()) {
                    offenders.append(docs.relativize(file)).append(' ');
                }
            }
        }
        Assertions.assertEquals("", offenders.toString(),
                "`@View` declares only gen/discriminatorField/addons; docs naming read= or write= teach "
                        + "an annotation that does not exist. Offenders: " + offenders);

        String viewAnnotation = read("hipster-entity-api/src/main/java/hr/hrg/hipster/entity/api/View.java");
        Assertions.assertTrue(viewAnnotation.contains("GenLevel gen()"));
        Assertions.assertTrue(viewAnnotation.contains("String discriminatorField()"));
        Assertions.assertTrue(viewAnnotation.contains("Class<?>[] addons()"));
    }

    /**
     * The example's own invocation is part of the adoption story, so its flags are documented facts.
     *
     * <p>JCodeBuddy is a side-car — the goals carry no {@code <phase>} — so this asserts both halves
     * of that contract: the invocation passes the flags the module documents, and it is not bound to
     * any lifecycle phase that would make it run on an ordinary build.</p>
     *
     * <p>The phase check strips comments first, the way {@code DependencyBoundaryTest} does for its
     * POM scans. The POM deliberately <em>discusses</em> phases in the comment that explains why
     * there are none, and a raw text search reads that prose as a binding. This test caught exactly
     * that when it was written, which is the argument for stripping rather than for softening the
     * assertion.</p>
     */
    @Test
    void theExampleInvocationRunsValidationLeavesSqlGenerationOffAndIsNotBoundToAPhase() throws Exception {
        String pom = read("hipster-entity-example/pom.xml");
        Assertions.assertTrue(pom.contains("<argument>--validate</argument>"),
                "the example must run the entity rules on every pass (task 1.13)");
        Assertions.assertFalse(pom.contains("<argument>--adapters</argument>"),
                "and must not enable the draft SQL generator: it is strictly opt-in (D-17)");
        Assertions.assertTrue(pom.contains("<classpathScope>compile</classpathScope>"),
                "exec:java defaults to runtime scope, which excludes the provided tooling");

        String pomCode = pom.replaceAll("(?s)<!--.*?-->", "");
        Assertions.assertFalse(pomCode.contains("<phase>"),
                "nothing may bind JCodeBuddy to a lifecycle phase: this project uses no annotation "
                        + "processing and no compile hooks — a pass runs on the side, manually or in "
                        + "watch mode, so an ordinary `mvn compile` must never invoke the generator");
    }

    /**
     * Reviewers and adopters are told where the checklists and indexes live. Two pointers drifted to
     * files that do not exist ({@code doc/user/field-lookup-guide.md} from a decision record, and
     * {@code ADR-GUIDE.md} from inside {@code decisions/}), and the patterns index advertised rules
     * its own page does not contain.
     *
     * <p>The scan skips anchor-only links ({@code #section}) and requires the link target to look like
     * a path — an earlier version of this test matched the {@code .md} of a same-page anchor and
     * reported every one of them as broken, which is the "guard written against the wrong string"
     * failure this repository has recorded three times (F-38, F-43, N-4).</p>
     */
    @Test
    void theDocumentationIndexesPointAtFilesThatExist() throws Exception {
        Path repo = repoRoot();
        Path docs = repo.resolve("doc-hipster-entity");
        Pattern relativeLink = Pattern.compile("\\]\\((?!https?:|#)([^)#]*[^)#/]\\.md)");
        StringBuilder broken = new StringBuilder();
        try (var walk = Files.walk(docs)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".md")).toList()) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                var matcher = relativeLink.matcher(text);
                while (matcher.find()) {
                    // Resolved against the LINKING FILE's directory, which is what a markdown renderer
                    // does. Two files in `user/patterns/` linked `../architecture/decisions/…`, one level
                    // too shallow — the links looked plausible and went nowhere, which is exactly the
                    // kind of dead end this test exists to catch.
                    Path target = file.getParent().resolve(matcher.group(1)).normalize();
                    if (!Files.exists(target)) {
                        broken.append(docs.relativize(file)).append(" -> ")
                                .append(matcher.group(1)).append("; ");
                    }
                }
            }
        }
        Assertions.assertEquals("", broken.toString(),
                "a docs index that links to a missing file costs a reader a dead end: " + broken);
    }
}
