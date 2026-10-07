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

    /**
     * A field declaration: a type, a name, then {@code =} or {@code ;}.
     *
     * <p>The trailing {@code =}/{@code ;} is what keeps a method out: {@code int charge() {}} fails it
     * because a parenthesis follows the name. A statement such as {@code total = computeTotal();}
     * fails it too — for the pattern to match, a <em>type</em> would have to precede the name, and
     * {@code total} is followed by {@code =} rather than by a name.
     */
    private static final Pattern FIELD = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|static|final|transient|volatile)\\s+)*"
            + "(?:[A-Za-z_$][\\w$.]*(?:\\s*<[^<>]*(?:<[^<>]*>[^<>]*)*>)?(?:\\s*\\[\\s*\\])*)\\s+"
            + "(?<name>[A-Za-z_$][\\w$]*)\\s*(?:=|;)");

    /** A type declaration, which like a method establishes where members are declared. */
    private static final Pattern TYPE = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*"
            + "(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");

    /**
     * The same field shape, but with an access modifier required — which is what makes it provably not
     * a local variable, and therefore usable where no declaration level can be established.
     */
    private static final Pattern ACCESSIBLE_FIELD = Pattern.compile(
        "^\\s*(?:public|protected|private)\\s+"
            + "(?:(?:static|final|transient|volatile)\\s+)*"
            + "(?:[A-Za-z_$][\\w$.]*(?:\\s*<[^<>]*(?:<[^<>]*>[^<>]*)*>)?(?:\\s*\\[\\s*\\])*)\\s+"
            + "(?<name>[A-Za-z_$][\\w$]*)\\s*(?:=|;)");

    /**
     * The fields declared at the type's own level, by name, in order.
     *
     * <p><b>Depth, not just shape.</b> A local variable and a field are the same text
     * ({@code int retries = 0;}), so matching a line would call every local a field — and a resolver
     * that then "kept both" would silently combine two competing method bodies as though they were two
     * members. The member level is therefore established first, from the depth at which this code
     * declares a method or a type, and only declarations at <em>that</em> depth are fields.
     *
     * <p><b>When no level can be established, nothing is returned.</b> A fragment that declares
     * neither a method nor a type — a bare run of field lines, or a run of statements from inside a
     * body — gives no way to tell the two apart. Guessing would be the expensive mistake here, so the
     * answer is an empty list and the caller declines.
     *
     * <p>The brace counting is line-based, like the rest of this class: a brace inside a string literal
     * or a comment can shift it. Both directions of that error are safe — a level that comes out too
     * deep hides a field, and one that comes out too shallow exposes a local, which the caller then
     * refuses because the member level it established was a method's.
     */
    static List<String> fieldNames(String code) {
        List<String> names = new ArrayList<>();
        if (code == null) {
            return names;
        }

        List<String> view = declarationView(code);
        int memberLevel = memberLevel(view);
        if (memberLevel < 0) {
            return names;
        }

        int depth = 0;
        for (String line : view) {
            if (depth == memberLevel) {
                Matcher matcher = FIELD.matcher(line);
                if (matcher.find()) {
                    names.add(matcher.group("name"));
                }
            }
            depth += depthChange(line);
        }
        return names;
    }

    /**
     * Every member this class can see: methods as {@code name(parameters)} and fields by name.
     *
     * <p>The two cannot be confused for one another — a method's signature always carries parentheses
     * and a field's name never does — so one set is enough to compare members with, and a field and a
     * method that share a name are two different members, as they are in Java.
     */
    static Set<String> membersOf(String code) {
        return membersOf(code, false);
    }

    /**
     * Every member this class can see, optionally accepting a field that <em>declares its access</em>
     * even where no declaration level could be established.
     *
     * <p>{@code allowAccessibleFields} exists for one shape and is sound only there: a pure insertion,
     * where the fragment is a bare run of the inserted lines and there is no method or type in it to
     * establish what a member is. There the modifier is the whole proof —
     * <b>a local variable cannot be declared {@code private}</b>, so a line that is must be a member,
     * while {@code int retries = 1;} alone would have been a guess either way. It is opt-in rather
     * than always-on because it is the modifier that carries the argument, not the shape.
     */
    static Set<String> membersOf(String code, boolean allowAccessibleFields) {
        Set<String> members = new LinkedHashSet<>(memberSignatures(code));
        members.addAll(fieldNames(code));
        if (allowAccessibleFields) {
            members.addAll(accessibleFieldNames(code));
        }
        return members;
    }

    /**
     * Field-shaped declarations that spell out {@code public}, {@code protected} or {@code private},
     * wherever they appear.
     *
     * <p>No declaration level is needed here and none is inferred: the modifier is the evidence. A
     * local variable cannot carry one, so this cannot mistake a statement for a member — which is the
     * one mistake that would matter, because the caller would then "keep both" two competing bodies.
     * The cost is the other direction: a package-private field in a fragment with no method or type in
     * it is not recognised, and the block stays a human's decision.
     */
    static List<String> accessibleFieldNames(String code) {
        List<String> names = new ArrayList<>();
        if (code == null) {
            return names;
        }
        for (String line : declarationView(code)) {
            Matcher matcher = ACCESSIBLE_FIELD.matcher(line);
            if (matcher.find()) {
                names.add(matcher.group("name"));
            }
        }
        return names;
    }

    /**
     * The depth at which this code declares its members: where its first method or type declaration
     * sits, or {@code -1} when it declares neither.
     */
    private static int memberLevel(List<String> view) {
        int depth = 0;
        for (String line : view) {
            if (METHOD.matcher(line).find() || TYPE.matcher(line).find()) {
                return depth;
            }
            depth += depthChange(line);
        }
        return -1;
    }

    /** The net brace change of one line, which is how the declaration depth is tracked. */
    private static int depthChange(String line) {
        int change = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '{') {
                change++;
            } else if (c == '}') {
                change--;
            }
        }
        return change;
    }

    private static String normalise(String parameters) {
        return parameters.replaceAll("\\s+", " ").trim();
    }
}
