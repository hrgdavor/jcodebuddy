package hr.hrg.jcodebuddy.automation;

import hr.hrg.hipster.entity.tooling.SourceReader;
import hr.hrg.hipster.entity.tooling.TreeQueries;

import org.openrewrite.java.tree.J;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one source text says about itself: its structural facts, and anything wrong with it that can be
 * established without a classpath.
 *
 * <h3>Structure from the tree, line classification from the text</h3>
 * <p>Both halves matter and they are deliberately different sources. Types, methods, imports and the
 * package come from OpenRewrite's LST — the same reader the rest of this repository uses, so the
 * automation layer cannot disagree with the generators about what a file declares. Line classification
 * (blank / comment / code) comes from a small scanner over the text, because no tree carries it: comments
 * are whitespace trivia in the LST and the parser discards them entirely, and the line map a tree does
 * not have is exactly the gap Phase 6 documented ({@code MIGRATION-CAVEATS.md} § 4.5).</p>
 *
 * <p>The scanner is a state machine, not a regular expression, and that is the point: a {@code //} inside
 * a string literal is not a comment, a {@code /*} inside one does not open a block, and a text block may
 * contain both. The plan this phase implements counted methods and classes with regular expressions over
 * the source; that is the technique Phase 6 spent a whole migration removing, so the replacement counts
 * nothing from the text that the tree can answer.</p>
 *
 * <h3>Validation is bounded on purpose</h3>
 * <p>Three checks, all of which a compiler would agree with and none of which needs a classpath: the text
 * is readable Java, a public top-level type is named after its file, and the file declares a type. Two
 * more are reported as warnings because they are conventions rather than errors ({@code package-info.java}
 * legitimately declares nothing, and a file in the default package is legal though unwise). A validator
 * that guessed further would be inventing rules the project has not agreed to.</p>
 */
final class SourceFacts {

    private SourceFacts() {
    }

    /**
     * The facts of one file.
     *
     * @param readable  whether the text is readable Java (see {@link SourceReader#readText(String)})
     * @param analysis  the facts a report prints, keyed by the constants on {@link AnalysisResult}
     * @param errors    reasons the file is not acceptable
     * @param warnings  facts worth printing that do not fail the file
     */
    record Facts(boolean readable, Map<String, Object> analysis, List<String> errors, List<String> warnings) {
    }

    /**
     * Reads {@code source} as {@code fileName}.
     *
     * @param fileName the file's name, used for the public-type-name check; may be {@code null} when the
     *                 caller has only text, in which case that check is skipped rather than guessed at
     */
    static Facts of(String fileName, String source) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> analysis = new LinkedHashMap<>();

        LineCounts lines = countLines(source);
        analysis.put(AnalysisResult.TOTAL_LINES, lines.total());
        analysis.put(AnalysisResult.CODE_LINES, lines.code());
        analysis.put(AnalysisResult.COMMENT_LINES, lines.comment());
        analysis.put(AnalysisResult.BLANK_LINES, lines.blank());

        if (source == null || source.isBlank()) {
            errors.add("the file is empty");
            return new Facts(false, analysis, errors, warnings);
        }

        J.CompilationUnit unit = SourceReader.readSourceText(source);
        if (unit == null) {
            errors.add(describeUnreadable(source));
            return new Facts(false, analysis, errors, warnings);
        }

        String packageName = TreeQueries.packageName(unit);
        analysis.put(AnalysisResult.PACKAGE, packageName);
        analysis.put(AnalysisResult.TYPE_COUNT, TreeQueries.typeDeclarations(unit).size());
        analysis.put(AnalysisResult.CLASS_COUNT, TreeQueries.classes(unit).size());
        analysis.put(AnalysisResult.INTERFACE_COUNT, TreeQueries.interfaces(unit).size());
        analysis.put(AnalysisResult.ENUM_COUNT, TreeQueries.enums(unit).size());
        analysis.put(AnalysisResult.RECORD_COUNT, TreeQueries.records(unit).size());
        analysis.put(AnalysisResult.ANNOTATION_COUNT, TreeQueries.annotations(unit).size());
        analysis.put(AnalysisResult.METHOD_COUNT, methodsIn(unit));
        analysis.put(AnalysisResult.IMPORT_COUNT, unit.getImports() == null ? 0 : unit.getImports().size());

        if (packageName.isEmpty()) {
            warnings.add("the file declares no package");
        }

        List<J.ClassDeclaration> topLevel = TreeQueries.topLevelTypes(unit);
        if (topLevel.isEmpty()) {
            // `package-info.java` is the one file in a Java tree that is *supposed* to declare nothing;
            // any other name declaring nothing is a file the compiler will accept and nobody can use.
            if (fileName == null || !"package-info.java".equals(fileName)) {
                warnings.add("the file declares no top-level type");
            }
        } else if (fileName != null) {
            for (J.ClassDeclaration declaration : topLevel) {
                if (declaration.hasModifier(J.Modifier.Type.Public)
                        && !(declaration.getSimpleName() + ".java").equals(fileName)) {
                    errors.add("the public type " + declaration.getSimpleName()
                            + " does not match the file name " + fileName
                            + "; javac refuses a public type whose name differs from its file");
                }
            }
        }
        return new Facts(true, analysis, errors, warnings);
    }

    /** Methods declared anywhere in the unit, constructors excluded because they are not methods. */
    private static int methodsIn(J.CompilationUnit unit) {
        int count = 0;
        for (J.MethodDeclaration method : TreeQueries.findAll(unit, J.MethodDeclaration.class)) {
            if (!method.isConstructor()) {
                count++;
            }
        }
        return count;
    }

    /**
     * A sentence naming the parser's own complaint, or the honest absence of one.
     *
     * <p>{@link SourceReader#problemsIn(String)} can be empty for text that is rejected, because the
     * parser recovers from a syntax error and only javac sees it. Saying so beats an empty reason: a
     * reader who is told "no detail was recorded" looks at the file, while a reader told nothing may
     * assume the tool is broken.</p>
     */
    private static String describeUnreadable(String source) {
        List<String> problems = SourceReader.problemsIn(source);
        if (problems.isEmpty()) {
            return "the text is not readable Java; the parser recovered from a syntax error without "
                    + "recording a reason (javac is the only witness to that class of breakage)";
        }
        return "the text is not readable Java: " + String.join("; ", problems);
    }

    /** The three line counts plus the total. */
    record LineCounts(int total, int code, int comment, int blank) {
    }

    /**
     * Classifies every line of {@code source} as blank, comment or code.
     *
     * <p>A line counts as <strong>code</strong> if it contains any character outside whitespace, comments
     * and literals — so {@code String s = "// not a comment";} is one code line and {@code /** doc *}{@code /}
     * is comment lines. State carries across lines, which is what the per-line alternatives get wrong:</p>
     * <ul>
     *   <li>a block comment opened on one line and closed on another must not leave the lines between them
     *       looking like code;</li>
     *   <li>an unterminated string or text block is treated as running to the end of the file rather than
     *       making the following lines look like code — the fail-safe direction, and the same choice
     *       {@code SourceReader} makes about unreadable input;</li>
     *   <li>{@code //} and {@code /*} inside a literal do not start a comment.</li>
     * </ul>
     */
    static LineCounts countLines(String source) {
        if (source == null || source.isEmpty()) {
            return new LineCounts(0, 0, 0, 0);
        }
        int total = 0;
        int code = 0;
        int comment = 0;
        int blank = 0;
        boolean inBlockComment = false;
        boolean inTextBlock = false;
        String[] rawLines = source.split("\n", -1);
        // The text after a final newline is not a line of the file, so exactly one trailing empty element
        // is dropped. A file ending in two newlines keeps its one genuinely blank last line.
        int lineCount = rawLines.length > 0 && rawLines[rawLines.length - 1].isEmpty()
                ? rawLines.length - 1 : rawLines.length;
        for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
            String line = rawLines[lineIndex];
            total++;
            boolean sawCode = false;
            boolean sawComment = false;
            int index = 0;
            while (index < line.length()) {
                if (inBlockComment) {
                    sawComment = true;
                    int close = line.indexOf("*/", index);
                    if (close < 0) {
                        index = line.length();
                    } else {
                        inBlockComment = false;
                        index = close + 2;
                    }
                    continue;
                }
                if (inTextBlock) {
                    sawCode = true;
                    int close = line.indexOf("\"\"\"", index);
                    if (close < 0) {
                        index = line.length();
                    } else {
                        inTextBlock = false;
                        index = close + 3;
                    }
                    continue;
                }
                char character = line.charAt(index);
                if (character == ' ' || character == '\t' || character == '\r') {
                    index++;
                    continue;
                }
                if (character == '/' && index + 1 < line.length() && line.charAt(index + 1) == '/') {
                    sawComment = true;
                    index = line.length();
                    continue;
                }
                if (character == '/' && index + 1 < line.length() && line.charAt(index + 1) == '*') {
                    sawComment = true;
                    inBlockComment = true;
                    index += 2;
                    continue;
                }
                if (character == '"') {
                    if (line.startsWith("\"\"\"", index)) {
                        sawCode = true;
                        inTextBlock = true;
                        index += 3;
                        continue;
                    }
                    sawCode = true;
                    index = skipLiteral(line, index, '"');
                    continue;
                }
                if (character == '\'') {
                    sawCode = true;
                    index = skipLiteral(line, index, '\'');
                    continue;
                }
                sawCode = true;
                index++;
            }
            if (sawCode) {
                code++;
            } else if (sawComment) {
                comment++;
            } else {
                blank++;
            }
        }
        return new LineCounts(total, code, comment, blank);
    }

    /**
     * The offset just past a string or character literal that starts at {@code start} on this line, or the
     * end of the line when it does not close on it.
     *
     * <p>An unescaped quote ends the literal; a backslash escapes the next character. A literal that does
     * not close on its line is left open by returning the line's end — the caller's classification then
     * treats the rest of the line as literal content, and the following lines start clean, which is the
     * least surprising reading of a file that does not compile anyway.</p>
     */
    private static int skipLiteral(String line, int start, char quote) {
        int index = start + 1;
        while (index < line.length()) {
            char character = line.charAt(index);
            if (character == '\\') {
                index += 2;
                continue;
            }
            if (character == quote) {
                return index + 1;
            }
            index++;
        }
        return line.length();
    }
}
