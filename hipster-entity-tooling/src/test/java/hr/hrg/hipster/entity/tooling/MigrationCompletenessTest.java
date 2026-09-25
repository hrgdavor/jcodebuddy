package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every committed source file in the reactor, put through the read path the migration installed.
 *
 * <p>This is the plan's {@code MigrationCompletenessTests} with a subject. The scale that matters for a
 * migration is not a synthetic file with four thousand members - it is the 283 main source files the
 * project actually compiles, read the way the generators read them. Three properties are checked over all
 * of them, and each is a property the port could silently break:</p>
 *
 * <ol>
 *   <li><strong>Nothing committed is silently unreadable.</strong> If javac parses a file,
 *       {@link SourceReader} must return a tree for it; if javac cannot, the read must say so rather than
 *       hand back a recovered partial tree. F-34 is the failure this guards, and it was found in a
 *       committed file once already.</li>
 *   <li><strong>Every position the reports link to lands on a line that declares what it names.</strong>
 *       DEC-028's rule is that a link is verified, not assumed, and a link to the wrong line looks exactly
 *       like a link to the right one. This checks every type, member and annotation position
 *       {@link JavaSyntaxCheck} records against the text it came from, and every span against the source
 *       it slices.</li>
 *   <li><strong>The two trees agree.</strong> Where the LST and javac both count something - declared
 *       types, declared methods - the counts must match for every file, because the switch-arm pairing is
 *       <em>positional</em> and a file where the two disagree is a file where the pairing yields nothing.</li>
 * </ol>
 *
 * <p>The sweep reads files and writes nothing. It is deliberately the one test in this module that walks
 * the repository: it is the phase's completeness evidence, and it fails with the offending paths named,
 * which is what makes it usable.</p>
 */
class MigrationCompletenessTest {

    /** Modules whose sources the migration ported, or whose generators read them. */
    private static final List<String> MODULES = List.of(
            "hipster-entity-api", "hipster-entity-core", "hipster-entity-tooling", "hipster-entity-jackson",
            "hipster-entity-test", "hipster-entity-example", "project-automation", "merge-java",
            "jwa-builder", "jwa-builder-api", "webview/jwa-sidecar", "java-watch-agent", "java-watch-core",
            "metadata-arena", "metadata-server", "hipster-ioc", "hipster-ioc-api");

    private static List<Path> committedSources() {
        Path root = CompileHarness.findRepoRoot();
        List<Path> files = new ArrayList<>();
        for (String module : MODULES) {
            Path sourceRoot = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Assertions.assertTrue(files.size() > 250,
                "the sweep is only evidence if it really walks the tree; found " + files.size() + " files");
        return files;
    }

    @Test
    void everyCommittedSourceIsEitherReadableOrHonestlyReportedAsNot() {
        List<String> unreadable = new ArrayList<>();
        List<String> silentlyRecovered = new ArrayList<>();
        int checked = 0;

        for (Path file : committedSources()) {
            String source = read(file);
            checked++;
            boolean javacParses = JavaSyntaxCheck.isSyntacticallyValid(source);
            SourceReader.Read read = SourceReader.readText(source);
            if (javacParses && !read.readable()) {
                unreadable.add(relativize(file));
            }
            // The other direction is the F-34 case: javac rejects the text and the read still hands back a
            // tree. SourceReader checks both channels, so this must never happen - and if the check is
            // ever removed, this is the assertion that notices.
            if (!javacParses && read.readable()) {
                silentlyRecovered.add(relativize(file));
            }
        }

        Assertions.assertEquals(List.of(), silentlyRecovered,
                "files javac rejects that SourceReader still reported as readable");
        Assertions.assertEquals(List.of(), unreadable,
                "files javac accepts that SourceReader could not read (" + checked + " checked)");
    }

    @Test
    void everyRecordedPositionLandsOnALineThatDeclaresWhatItNames() {
        List<String> wrongTypeLines = new ArrayList<>();
        List<String> wrongMemberLines = new ArrayList<>();
        List<String> wrongAnnotationLines = new ArrayList<>();
        List<String> negativeSpans = new ArrayList<>();
        int types = 0;
        int members = 0;
        int annotations = 0;

        for (Path file : committedSources()) {
            String source = read(file);
            JavaSyntaxCheck.FileCheck check = JavaSyntaxCheck.inspect(source);
            if (!check.syntacticallyValid()) {
                continue;
            }
            String[] lines = source.split("\\R", -1);
            String where = relativize(file);

            for (JavaSyntaxCheck.TypePosition type : check.types()) {
                types++;
                if (!declares(lines, type.nameLine(), type.simpleName())) {
                    wrongTypeLines.add(where + ":" + type.nameLine() + " names " + type.simpleName()
                            + " but reads: " + at(lines, type.nameLine()));
                }
                if (type.declarationLine() > type.nameLine()) {
                    wrongTypeLines.add(where + ": declaration line " + type.declarationLine()
                            + " is below the name line " + type.nameLine());
                }
            }
            for (JavaSyntaxCheck.MemberPosition member : check.members()) {
                members++;
                if (!declares(lines, member.nameLine(), member.simpleName())) {
                    wrongMemberLines.add(where + ":" + member.nameLine() + " names " + member.simpleName()
                            + " but reads: " + at(lines, member.nameLine()));
                }
            }
            for (JavaSyntaxCheck.AnnotationPosition annotation : check.annotations()) {
                annotations++;
                String line = at(lines, annotation.line());
                if (!line.contains("@" + annotation.simpleName())) {
                    wrongAnnotationLines.add(where + ":" + annotation.line() + " names @"
                            + annotation.simpleName() + " but reads: " + line);
                }
            }
            for (JavaSyntaxCheck.MemberSpan span : check.spans()) {
                if (span.startOffset() < 0 || span.endOffset() < span.startOffset()
                        || span.endOffset() > source.length()) {
                    negativeSpans.add(where + " " + span.kind() + " " + span.name() + " ["
                            + span.startOffset() + ", " + span.endOffset() + ") of " + source.length());
                }
            }
        }

        Assertions.assertTrue(types > 250 && members > 1_000,
                "the sweep must actually have seen the tree: " + types + " types, " + members + " members");
        Assertions.assertEquals(List.of(), wrongTypeLines, "type name lines that do not declare the type");
        Assertions.assertEquals(List.of(), wrongMemberLines,
                "member name lines that do not declare the member");
        Assertions.assertEquals(List.of(), wrongAnnotationLines,
                "annotation lines that do not carry the annotation");
        Assertions.assertEquals(List.of(), negativeSpans, "member spans that are not a real half-open range");
    }

    /**
     * The LST and javac counted the same declarations, file by file.
     *
     * <p>Not a claim that the two are <em>equal</em> in identity - that is what the positional zip in
     * {@link TreeQueries#caseSpans} does, and it refuses to guess when the counts disagree. This asserts
     * the counts agree for every file in the tree, so the disagreement path stays the rare thing it is
     * meant to be rather than the normal answer for half the repository.</p>
     */
    @Test
    void theLstAndJavacAgreeOnWhatEachFileDeclares() {
        List<String> typeMismatches = new ArrayList<>();
        List<String> methodMismatches = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();
        int files = 0;

        for (Path file : committedSources()) {
            String source = read(file);
            J.CompilationUnit cu = SourceReader.readSourceText(source);
            files++;
            if (cu == null) {
                // Not a mismatch: SourceReader refuses a file javac recovers from, and this sweep is not
                // where that decision is argued. It is counted, so a regression cannot hide by shrinking
                // the sample.
                unparsed.add(relativize(file));
                continue;
            }
            JavaSyntaxCheck.FileCheck check = JavaSyntaxCheck.inspect(source);
            if (!check.syntacticallyValid()) {
                continue;
            }
            String where = relativize(file);

            int lstTypes = TreeQueries.typeDeclarations(cu).size();
            if (lstTypes != check.types().size()) {
                typeMismatches.add(where + ": LST " + lstTypes + " types, javac " + check.types().size());
            }
            long lstMethods = TreeQueries.findAll(cu, J.MethodDeclaration.class)
                    .stream().filter(m -> !m.isConstructor()).count();
            long javacMethods = check.methods().stream()
                    .filter(m -> !m.simpleName().equals("<init>")).count();
            if (lstMethods != javacMethods) {
                methodMismatches.add(where + ": LST " + lstMethods + " methods, javac " + javacMethods);
            }
        }

        Assertions.assertTrue(files > 250, "the sweep covered " + files + " files");
        Assertions.assertEquals(List.of(), unparsed, "committed sources SourceReader could not read");
        Assertions.assertEquals(List.of(), typeMismatches, "files where the two trees disagree on types");
        Assertions.assertEquals(List.of(), methodMismatches, "files where the two trees disagree on methods");
    }

    // ------------------------------------------------------------------ helpers ---

    private static String relativize(Path file) {
        return CompileHarness.findRepoRoot().relativize(file).toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static String at(String[] lines, int oneBasedLine) {
        if (oneBasedLine < 1 || oneBasedLine > lines.length) {
            return "<out of range>";
        }
        return lines[oneBasedLine - 1].trim();
    }

    /** Whether the line carries {@code name} as a whole identifier, not as someone else's use of it. */
    private static boolean declares(String[] lines, int oneBasedLine, String name) {
        String line = at(lines, oneBasedLine);
        if (name == null || name.isEmpty() || "<out of range>".equals(line)) {
            return false;
        }
        for (int index = line.indexOf(name); index >= 0; index = line.indexOf(name, index + 1)) {
            boolean leftFree = index == 0 || !Character.isJavaIdentifierPart(line.charAt(index - 1));
            int after = index + name.length();
            boolean rightFree = after >= line.length() || !Character.isJavaIdentifierPart(line.charAt(after));
            boolean isUseOfSomethingElse = index > 0
                    && (line.charAt(index - 1) == '@' || line.charAt(index - 1) == '.');
            if (leftFree && rightFree && !isUseOfSomethingElse) {
                return true;
            }
        }
        return false;
    }
}
