// {@link com.codebuddy.merge.DeclarationScanner} Formatting-tolerant declaration extraction.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts method declarations from source text in a way that survives ordinary
 * formatting variation.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Detection used to scan line by line. That silently missed real conflicts on
 * perfectly ordinary code, which is the worst failure mode a merge tool can have
 * because the conflict is not merely mis-classified - it disappears:
 *
 * <ul>
 *   <li>a comment between two declarations hid both;</li>
 *   <li>an opening brace on the following line hid the declaration, because the
 *       pattern required {@code {} on the same line;</li>
 *   <li>an annotation line between declarations had the same effect.</li>
 * </ul>
 *
 * <p>Probing confirmed all three produced "nothing detected" rather than an
 * error, so this class reconstructs a <em>declaration view</em> of the source:
 * comment and annotation lines are dropped, and a declaration header that is
 * followed by its opening brace on the next line is joined onto one line. Only
 * then is the method pattern applied, and every consumer
 * ({@link OverloadAddConflictResolver}, {@link ConflictDetectionService}) reads
 * declarations through here so they cannot disagree about what is declared.
 *
 * <p>This is a normalisation, not a parser: it removes formatting noise so the
 * existing patterns can work, and it deliberately keeps the fallback behaviour for
 * text it cannot make sense of.
 */
final class DeclarationScanner {

    /**
     * A method declaration: its name and its normalised parameter list.
     */
    record MethodDeclaration(String name, String parameters) {

        /**
         * Full identity, used to tell a genuinely added overload from one both
         * sides already had.
         */
        String signature() {
            return name + "(" + parameters + ")";
        }
    }

    /**
     * Matches a method declaration, capturing its name and parameter list.
     *
     * <p>The return-type group contains no whitespace so the lazy quantifier
     * cannot backtrack and let the method-name group swallow part of the return
     * type - without that, {@code void process()} parses as a method named
     * {@code oid}. The brace is optional in the pattern because
     * {@link #declarationView} may have joined it on, and because an abstract or
     * interface method has no body at all.
     */
    private static final Pattern METHOD = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\\s+)*"
            + "(?:<[^>]+>\\s*)?"
            + "(?<returnType>[A-Za-z_$][\\w$.]*(?:\\s*<[^<>]*(?:<[^<>]*>[^<>]*)*>)?(?:\\s*\\[\\s*\\])*)"
            + "\\s+"
            + "(?<methodName>[A-Za-z_$][\\w$]*)\\s*"
            + "\\((?<params>[^)]*)\\)\\s*"
            + "(?:throws\\s+[\\w.,\\s]+?)?"
            + "(?:\\{|;|$)");

    /**
     * Lines that are never part of a declaration and only serve to break up a
     * scan.
     */
    private static final Pattern IGNORABLE_LINE = Pattern.compile(
        "^\\s*(?://.*|/\\*.*|\\*.*|@[\\w.]+(?:\\(.*\\))?)\\s*$");

    private DeclarationScanner() {
    }

    /**
     * Every method declared in the given source, in order.
     */
    static List<MethodDeclaration> declarationsIn(String code) {
        List<MethodDeclaration> declarations = new ArrayList<>();
        if (code == null) {
            return declarations;
        }
        for (String line : declarationView(code)) {
            Matcher matcher = METHOD.matcher(line);
            if (matcher.find()) {
                declarations.add(new MethodDeclaration(
                    matcher.group("methodName"),
                    normalise(matcher.group("params"))));
            }
        }
        return declarations;
    }

    /**
     * The method signatures declared in the source, as {@code name(params)}.
     */
    static Set<String> memberSignatures(String code) {
        Set<String> signatures = new LinkedHashSet<>();
        for (MethodDeclaration declaration : declarationsIn(code)) {
            signatures.add(declaration.signature());
        }
        return signatures;
    }

    /**
     * Reconstruct the source as one declaration per line:
     *
     * <ul>
     *   <li>comment-only and annotation-only lines are dropped, so they cannot
     *       interrupt a scan;</li>
     *   <li>a line that ends without an opening brace but whose next code line
     *       starts with one is joined, so the allman brace style is recognised;</li>
     *   <li>a closing brace on its own is kept as a terminator, so a joined
     *       declaration does not run into the next one.</li>
     * </ul>
     */
    static List<String> declarationView(String code) {
        List<String> result = new ArrayList<>();
        String[] lines = code.split("\n");

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank() || IGNORABLE_LINE.matcher(line).matches()) {
                continue;
            }

            String trimmed = line.stripTrailing();
            // Join a header with an opening brace that sits on the next line.
            if (!trimmed.endsWith(";") && !trimmed.endsWith("}") && !trimmed.endsWith("{")) {
                int next = nextSignificantLine(lines, index + 1);
                if (next >= 0 && lines[next].trim().startsWith("{")) {
                    result.add(trimmed + " {");
                    index = next;
                    continue;
                }
            }
            result.add(trimmed);
        }
        return result;
    }

    private static int nextSignificantLine(String[] lines, int from) {
        for (int index = from; index < lines.length; index++) {
            if (!lines[index].isBlank()
                && !IGNORABLE_LINE.matcher(lines[index]).matches()) {
                return index;
            }
        }
        return -1;
    }

    private static String normalise(String parameters) {
        return parameters.replaceAll("\\s+", " ").trim();
    }
}
