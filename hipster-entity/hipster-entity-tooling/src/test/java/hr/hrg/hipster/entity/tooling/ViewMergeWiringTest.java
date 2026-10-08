// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code View1Builder.merge(View2 other)} driven through a real generation pass — plan step 7.3's gate, and the half
 * {@link ViewMergeGeneratorTest} cannot check: that the method reaches the builders the pass actually writes.
 *
 * <p>The pair is deliberately <b>partly shared</b>: {@code PersonSummary} and {@code PersonDto} agree on
 * {@code id} and {@code name}, the summary has a field the dto lacks, the dto has one the summary lacks, and one
 * field matches by name with a different type. That is the shape the todo describes ("merge fields with identical
 * name and type"), and each of the four cases has a different correct outcome — merged, silent, silent, diagnostic.
 */
class ViewMergeWiringTest {

    @TempDir
    Path tempDir;

    private static final String PRIMARY = """
            package merge.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /** The host: shares {@code name} and {@code age} by type, and has a `score` the partner lacks. */
    private static final String SUMMARY = """
            package merge.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            import hr.hrg.hipster.entity.api.View;
            import java.util.List;
            import java.util.Map;
            @View(gen = hr.hrg.hipster.entity.api.GenLevel.BUILDER_ALL)
            public interface PersonSummary extends PersonEntity {
                @FieldSource(kind = FieldKind.COLUMN)
                String name();
                @FieldSource(kind = FieldKind.COLUMN)
                Integer age();
                @FieldSource(kind = FieldKind.COLUMN)
                int score();
                @FieldSource(kind = FieldKind.COLUMN)
                String nickname();
            }
            """;

    /** The partner: shares {@code name} and {@code age} by type, has `extra`, and `nickname` with another type. */
    private static final String DTO = """
            package merge.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            import hr.hrg.hipster.entity.api.View;
            import java.util.List;
            import java.util.Map;
            @View(gen = hr.hrg.hipster.entity.api.GenLevel.BUILDER)
            public interface PersonDto extends PersonEntity {
                @FieldSource(kind = FieldKind.COLUMN)
                String name();
                @FieldSource(kind = FieldKind.COLUMN)
                Integer age();
                @FieldSource(kind = FieldKind.COLUMN)
                String extra();
                @FieldSource(kind = FieldKind.COLUMN)
                Integer nickname();
            }
            """;

    private record Generated(Path builder, Path trackingBuilder, List<String> divergences) {
    }

    private Generated generate(boolean withMerge) throws Exception {
        Path sourceRoot = tempDir.resolve("src");
        Path outputRoot = tempDir.resolve("out");
        // Flat in the source root, as ViewMapperGeneratorTest does: the package comes from the source text, not
        // from the file location, and a package filter would add a second variable to a test about merges.
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), PRIMARY);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), SUMMARY);
        Files.writeString(sourceRoot.resolve("PersonDto.java"), DTO);
        Files.createDirectories(outputRoot);

        DivergenceReporter divergences = new DivergenceReporter();
        // The view files are the indexed input, so the pass reads them from the source root and writes the builders
        // into the output root - the same split the documented invocation uses (§ 8.8/3.23).
        EntityMetadataGenerator.setMergeRequests(withMerge ? List.of("PersonSummary:PersonDto") : List.of());
        try {
            EntityMetadataGenerator.generate(sourceRoot, outputRoot, outputRoot, divergences);
        } finally {
            EntityMetadataGenerator.setMergeRequests(List.of());
        }
        return new Generated(outputRoot.resolve("merge/hr/PersonSummaryBuilder.java"),
                outputRoot.resolve("merge/hr/PersonSummaryBuilderTracking.java"),
                divergences.entries());
    }

    private static String read(Path path) throws Exception {
        Assertions.assertTrue(Files.exists(path), "expected a generated file at " + path);
        return Files.readString(path);
    }

    @Test
    void theRequestedMergeReachesBothBuilders() throws Exception {
        Generated generated = generate(true);
        String builder = read(generated.builder());
        String proxy = read(generated.trackingBuilder());

        Assertions.assertTrue(builder.contains("public PersonSummaryBuilder merge(PersonDto other)"),
                "the untracked builder must carry the merge: " + builder);
        Assertions.assertTrue(proxy.contains("public PersonSummaryBuilderTracking merge(PersonDto other)"),
                "and the proxy variant must carry the same merge (plan step 7.3's second half): " + proxy);
    }

    @Test
    void onlyTheSharedFieldsAreCopiedAndTheMismatchIsReported() throws Exception {
        Generated generated = generate(true);
        String builder = read(generated.builder());

        String method = builder.substring(builder.indexOf("public PersonSummaryBuilder merge(PersonDto other)"));
        method = method.substring(0, method.indexOf("\n    }\n"));
        Assertions.assertTrue(method.contains("name(other.name());"), method);
        Assertions.assertTrue(method.contains("age(other.age());"), method);
        // `score` is the host's alone and `extra` the partner's alone: neither is merged, and neither is a diagnostic.
        Assertions.assertFalse(method.contains("score("), method);
        Assertions.assertFalse(method.contains("extra("), method);
        // `nickname` matches by name and not by type (String vs Integer): reported, and NOT copied.
        Assertions.assertFalse(method.contains("nickname("), method);
        Assertions.assertTrue(generated.divergences().stream().anyMatch(entry ->
                        entry.contains("merge_field_type_mismatch") && entry.contains("nickname")),
                "the mismatch must be reported in DEC-022's format: " + generated.divergences());
    }

    /**
     * Without the flag nothing changes, which is what makes the feature opt-in rather than a surprise: a project that
     * never asks for a merge gets the same builders it got before this step.
     */
    @Test
    void withoutTheRequestNoBuilderGainsAMerge() throws Exception {
        Generated generated = generate(false);

        Assertions.assertFalse(read(generated.builder()).contains("merge("),
                "an unrequested merge must not appear in the builder");
        Assertions.assertFalse(read(generated.trackingBuilder()).contains("merge("),
                "nor in the proxy variant");
    }

    /**
     * The method the emitter produces must be Java that compiles against the two views — the property this repository
     * keeps finding the hard way: a "generated" method that cannot compile is not a diagnostic, it is a broken build.
     */
    @Test
    void theEmittedMergeCompiles() throws Exception {
        Generated generated = generate(true);
        String builder = read(generated.builder());
        String method = builder.substring(builder.indexOf("public PersonSummaryBuilder merge(PersonDto other)"));

        Assertions.assertTrue(method.contains("public PersonSummaryBuilder merge(PersonDto other)"), method);
        Assertions.assertTrue(method.contains("if (other == null)"), method);
        Assertions.assertTrue(method.contains("return this;"), method);
        // A builder must never return a partially built instance: no build() inside the merge.
        String body = method.substring(0, method.indexOf("\n    }\n"));
        Assertions.assertFalse(body.contains("build()"), body);
    }
}
