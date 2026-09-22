package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Phase 7 exit gate's dependency half (plan.dsflash § 12.5): <em>"no library module gained a hard
 * JDBC or validation dependency"</em>.
 *
 * <p>That sentence is easy to satisfy by accident and easy to break by accident, which is why it is
 * asserted rather than asserted-to. Phase 7 added two things that reach for third-party or
 * platform-adjacent APIs:</p>
 *
 * <ul>
 *   <li><strong>JDBC</strong> — the generated adapters use {@code java.sql.ResultSet} and
 *       {@code java.sql.PreparedStatement}. Those are in the JDK, so no JDBC dependency should exist
 *       anywhere, and a driver must never enter a library POM. The check is a repository-wide one:
 *       {@code java.sql} may appear in <em>generated</em> source and in tests that fake the
 *       interfaces, but not in a library module's own main sources.</li>
 *   <li><strong>Bean Validation</strong> — the tooling declares
 *       {@code jakarta.validation:jakarta.validation-api} as {@code provided}. A consuming project adds
 *       it only if it wants validation; a library module must not gain it at all, and even the tooling
 *       must not gain it at {@code compile} scope, because that would make it transitive.</li>
 * </ul>
 *
 * <p>The test reads the POMs and the sources as text. That is deliberate: a dependency's <em>scope</em>
 * is what matters here, and no compiler or classpath check can tell you that a {@code provided}
 * dependency did not become a {@code compile} one.</p>
 */
class DependencyBoundaryTest {

    /** The modules that may never carry a JDBC or validation dependency. */
    private static final List<String> LIBRARY_MODULES = List.of(
            "hipster-entity-api", "hipster-entity-core", "hipster-entity-jackson",
            "hipster-entity-tooling");

    private static Path repoRoot() {
        return CompileHarness.findRepoRoot();
    }

    /**
     * Reads a file as UTF-8, naming it in the failure.
     *
     * <p>The name matters more than it looks. This class scans every library source, and a
     * {@link java.nio.charset.MalformedInputException} from a bare {@code Files.readString} names only
     * "Input length = 1" — no file, no line. That happened while writing the round-4 changes: a
     * PowerShell edit had written two sources as cp1252 bytes, the scan failed, and the message gave
     * nothing to act on. Now the offender is in the exception.</p>
     */
    private static String read(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString()
                    .replace("\r\n", "\n");
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new AssertionError("not valid UTF-8: " + path, e);
        }
    }

    @Test
    void noModuleDeclaresAValidationDependencyExceptTheToolingWhichDeclaresItProvided() throws Exception {
        Path root = repoRoot();
        for (String module : LIBRARY_MODULES) {
            String pom = read(root.resolve(module).resolve("pom.xml"));

            if (!"hipster-entity-tooling".equals(module)) {
                Assertions.assertFalse(pom.contains("jakarta.validation"),
                        module + " must not gain a validation dependency: the generated record and "
                                + "builders carry the author's annotations, and a project that wants a "
                                + "provider adds it itself");
                continue;
            }

            // The one sanctioned exception. Both halves are asserted, because `jakarta.validation-api`
            // at compile scope would be transitive and would put a validation dependency on every
            // project that merely uses the generator.
            Assertions.assertTrue(pom.contains("jakarta.validation-api"),
                    "the tooling does declare it — it has to read the annotation names: " + pom);
            int dependency = pom.indexOf("jakarta.validation-api");
            String around = pom.substring(Math.max(0, dependency - 300), dependency + 300);
            Assertions.assertTrue(around.contains("<scope>provided</scope>"),
                    "and only at provided scope, so it is never transitive: " + around);
            Assertions.assertTrue(pom.contains("<version>3.0.2</version>"),
                    "pinned to a version the local repository holds, so the offline build keeps "
                            + "working (plan.dsflash 12.3/7.10)");
        }
    }

    @Test
    void noModuleDeclaresAJacksonOrJdbcDependencyItShouldNot() throws Exception {
        Path root = repoRoot();
        for (String module : LIBRARY_MODULES) {
            String pom = read(root.resolve(module).resolve("pom.xml"));
            for (String forbidden : List.of("postgresql", "mysql", "h2database", "mssql-jdbc",
                    "hsqldb", "sqlite-jdbc", "hikari", "c3p0")) {
                Assertions.assertFalse(pom.toLowerCase().contains(forbidden),
                        module + " must not depend on a database or a connection pool, found '"
                                + forbidden + "'");
            }
        }
    }

    @Test
    void theOnlyModulesWithJacksonOrJavaSqlOnTheirMainCompilePathAreTheOnesThatNeedThem() throws Exception {
        Path root = repoRoot();

        // `java.sql` is a JDK package, so this is not about a dependency: it is about *where* the
        // adapters live. The reference adapter is generated into the consuming project, and the
        // library modules expose the positional contract without ever touching a ResultSet.
        List<String> offenders = new ArrayList<>();
        for (String module : List.of("hipster-entity-api", "hipster-entity-core",
                "hipster-entity-jackson")) {
            Path mainSources = root.resolve(module).resolve("src/main/java");
            try (var walk = Files.walk(mainSources)) {
                for (Path source : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                    if (read(source).contains("java.sql.")) {
                        offenders.add(module + "/" + mainSources.relativize(source));
                    }
                }
            }
        }
        Assertions.assertTrue(offenders.isEmpty(),
                "no library module's own source may reach for JDBC; the generated adapter is where "
                        + "java.sql belongs. Found: " + offenders);

        // And the generator itself must not either — it emits java.sql into generated text, which is a
        // string literal, not an import of its own. The check is anchored at the start of a line, so
        // `sb.append("import java.sql.ResultSet;\n")` is not mistaken for an import statement.
        Path toolingSources = root.resolve("hipster-entity-tooling/src/main/java");
        try (var walk = Files.walk(toolingSources)) {
            for (Path source : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = read(source);
                Assertions.assertFalse(
                        java.util.regex.Pattern.compile("(?m)^\\s*import\\s+java\\.sql\\.")
                                .matcher(text).find(),
                        "the tooling must not import java.sql; it writes it as text: " + source);
            }
        }
    }

    /**
     * <strong>Retired</strong> with the dependency it guarded (Phase 6, 2026-09-22).
     *
     * <p>{@code javaParserIsPinnedOnceInTheRootPom} asserted that the tooling POM declared
     * {@code javaparser-core} without pinning a version of its own, so that the single version property
     * in the root POM was the only place the number appeared. That property is what F-23 needed while
     * JavaParser was in use: the local repository holds eleven JavaParser versions, an ad-hoc classpath
     * built by globbing it picked {@code 3.25.1}, that version cannot parse {@code sealed}, and the
     * generator therefore produced <strong>nothing</strong> for five example files with no error at
     * all — the only symptom was files missing from a diff.</p>
     *
     * <p>Phase 6 removed {@code javaparser-core} from every POM in the tree, so there is no dependency
     * left to single-source and the assertion would be vacuous. What remains of F-23's lesson is the
     * part that still has a subject: the reader refuses source it cannot parse
     * ({@code SourceReaderTest.aCleanFileReadsAndARecoveredSyntaxErrorDoesNot}) and the parser is
     * pinned to the project's language level in {@code SourceReader}.</p>
     */

    /**
     * DEC-021's pinned JSON5 feature table and the code that implements it name the same features
     * (follow-up plan § 5.1; notes D-6).
     *
     * <p>D-6 found the normative table using Jackson 2 spellings while the code used Jackson 3
     * (`ALLOW_UNQUOTED_PROPERTY_NAMES`, features enabled straight on the mapper builder). A decision
     * record that disagrees with the tree is worse than no record, so both directions are asserted:
     * every feature in the code appears in the table, and the table names nothing the code does not
     * enable. The subset itself is pinned by {@code EnumConstantOrderChecker}'s own list — this test
     * only stops the <em>document</em> from drifting away from it.</p>
     */
    @Test
    void theDec021FeatureTableAndTheCodeAgree() throws Exception {
        Path root = repoRoot();
        String decision = read(root.resolve("doc-hipster-entity/architecture/decisions/DEC-021.md"));
        String checker = read(root.resolve("hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/"
                + "tooling/validation/EnumConstantOrderChecker.java"));

        // Only TABLE ROWS count on the DEC-021 side: the surrounding prose deliberately quotes the
        // Jackson 2 spelling that was wrong, and a scan of the whole file would read that as a feature
        // the code is missing. The feature is backticked in the table's SECOND column, so the pattern
        // anchors on a row (leading `|`) and finds the first backticked token after the first cell.
        java.util.Set<String> documented = new java.util.TreeSet<>();
        for (String line : decision.split("\n")) {
            java.util.regex.Matcher row = java.util.regex.Pattern
                    .compile("^\\|[^|]*\\|\\s*`([A-Z_]+)`").matcher(line);
            if (row.find()) {
                documented.add(row.group(1));
            }
        }

        // And only CODE counts on the implementation side: the checker's javadoc names the Jackson 2
        // feature it renamed, which is documentation, not an enabled feature. Comments are stripped
        // before the scan so the two sides are compared like for like.
        String checkerCode = checker
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        java.util.Set<String> implemented = new java.util.TreeSet<>();
        java.util.regex.Matcher used = java.util.regex.Pattern
                .compile("JsonReadFeature\\.([A-Z_]+)").matcher(checkerCode);
        while (used.find()) {
            implemented.add(used.group(1));
        }

        Assertions.assertFalse(documented.isEmpty(), "DEC-021 must contain the feature table");
        Assertions.assertFalse(implemented.isEmpty(), "the checker must enable features");
        Assertions.assertEquals(implemented, documented,
                "the normative table and EnumConstantOrderChecker must name the same Jackson 3 "
                        + "features: documented-but-not-implemented="
                        + difference(documented, implemented)
                        + ", implemented-but-not-documented="
                        + difference(implemented, documented));
    }

    /** The elements of {@code from} that {@code remove} does not contain, for a readable diff. */
    private static java.util.Set<String> difference(java.util.Set<String> from,
                                                    java.util.Set<String> remove) {
        java.util.Set<String> left = new java.util.TreeSet<>(from);
        left.removeAll(remove);
        return left;
    }

    /**
     * Every emitter that maps a field to an ordinal is driven by the ledger-ordered list — the
     * follow-up plan's § 2.4, guarding notes F-35.
     *
     * <p>F-35 is the most serious defect that execution found: every emitter computed a field's ordinal
     * from the <em>declaration-ordered</em> property list while the enum defined it by the ledger, so
     * retiring a middle accessor made the JDBC binder write the retired slot into another field's
     * column, and the tracking builder record changes against the tombstone's own ordinal. The fix
     * introduced {@code ledgerOrderedProperties} as the single source of the ordinal space, and every
     * emitter call site in the pass reads that one list.</p>
     *
     * <p>The behaviour is covered by {@code TombstoneLedgerTest}. What no behaviour test can cover is a
     * <strong>future</strong> emitter wired to the declaration-ordered list instead — that is a new call
     * site, not a new behaviour. So the invariant asserted here is the structural one: the pass hands
     * the emitters the ledger-ordered variable and nothing else. A new emitter that reaches for
     * {@code fullProperties} fails this test, which is the cheapest possible way to keep F-35 closed.</p>
     */
    @Test
    void everyOrdinalConsumingEmitterIsDrivenByTheLedgerOrderedPropertyList() throws Exception {
        Path root = repoRoot();
        String pass = read(root.resolve("hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/"
                + "tooling/EntityMetadataGenerator.java"));
        String passCode = pass.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");

        // The one place the ledger order is computed, and the one variable every emitter receives.
        Assertions.assertTrue(passCode.contains("ledgerOrderedProperties("),
                "the pass must build the ordinal space once (F-35)");

        // Emitters that consume a property list. Each name is a method that maps fields to positions or
        // ordinals; a new emitter belongs in this list with an `ordinalProperties` argument.
        List<String> emitters = List.of(
                "ViewBuilderGenerator.generate(",
                "ViewAdapterGenerator.generate(",
                "ViewRecordGenerator",
                "ViewMapperGenerator.ViewRef(",
                "ValidationGenerator.generate(");

        List<String> offenders = new ArrayList<>();
        for (String emitter : emitters) {
            for (String line : passCode.split("\n")) {
                if (line.contains(emitter) && line.contains("fullProperties")) {
                    offenders.add(emitter + " <- " + line.trim());
                }
            }
        }
        Assertions.assertTrue(offenders.isEmpty(),
                "an emitter must never receive the declaration-ordered list: that is F-35 exactly. "
                        + "Found: " + offenders);

        // And the positive half: the ordinal-consuming emitters are actually reached with it.
        Assertions.assertTrue(passCode.contains("ViewBuilderGenerator.generate(javaOutputRoot, viewPackage, view, ordinalProperties"),
                "the builder is handed the ledger-ordered list");
        Assertions.assertTrue(passCode.contains("ViewAdapterGenerator.generate(javaOutputRoot, viewPackage, view, ordinalProperties)"),
                "and so is the (draft) adapter generator");
    }
}
