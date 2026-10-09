package hr.hrg.hipster.entity.tooling;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the <strong>class references inside an annotation argument's text</strong> against the
 * declaring file's imports, so the text a consumer receives does not depend on a file it may not have
 * (plan step 6.7, split out of 6.6; DEC-047 § 1 is the half that was already done).
 *
 * <h3>The gap this closes, exactly</h3>
 * <p>A constraint's <em>type</em> is already a fully qualified name ({@code FieldConstraint.qualifiedName()}),
 * while its <em>arguments</em> are carried as the author wrote them. That is right for the grammar — the
 * generator carries the declaration through rather than reimplementing Bean Validation's member grammar —
 * but it makes a class-valued argument depend on the declaring file: {@code @Size(min = 1)} needs
 * nothing, while {@code @Size(groups = Create.class)} reaches the metadata JSON, the generated
 * {@code annotations()} override and every report as the bare {@code Create.class}. A consumer holding
 * that text has no import table to resolve it with, so the fact is incomplete in exactly the place the
 * generator exists to make facts complete.</p>
 *
 * <h3>What is resolved, and what is deliberately left alone</h3>
 * <p>The reference form this reads is a class literal — {@code X.class}, and {@code Outer.Inner.class} or
 * its qualified spelling. A literal whose type is a <strong>single simple name the declaring file
 * resolves</strong> is rewritten to the qualified spelling; every other form is returned unchanged:</p>
 * <ul>
 *   <li><strong>an explicit import</strong> ({@code import fixture.other.Create;} + {@code Create.class})
 *       — qualified to {@code fixture.other.Create.class}, which is the whole point of the step;</li>
 *   <li><strong>a {@code java.lang} name</strong> ({@code String.class}) — qualified too, from
 *       {@link #JAVA_LANG_NAMES}, because the import table holds only the file's <em>explicit</em>
 *       imports and the language supplies this one. The qualified spelling is what a consumer with no
 *       import table can read, so it is information rather than noise;</li>
 *   <li><strong>already qualified</strong> ({@code a.b.Rect.class}) — nothing to add, and rewriting it
 *       would be a second guess about a name the author spelled out;</li>
 *   <li><strong>a nested type written {@code Outer.Inner.class}</strong> — the table maps {@code Outer},
 *       and qualifying the whole expression would be wrong for a type whose simple name is {@code Inner}
 *       in another package. It is left as written and <em>reported</em>;</li>
 *   <li><strong>a class literal inside a quoted argument</strong> — {@code @Pattern(regexp = "X.class")}
 *       is a regular expression whose text happens to look like a type, so it is not rewritten. That
 *       guard is measured, not assumed: two earlier versions of this class rewrote it, and the javadoc of
 *       {@link #insideString} records why both were wrong.</li>
 * </ul>
 *
 * <h3>Why text and not a tree walk</h3>
 * <p>The argument text is what the whole pipeline carries — {@code FieldConstraint.arguments} is a
 * string, the generated {@code annotations()} override is built from that string, and the metadata JSON
 * stores it — so rewriting the text at the one point where the import table is in scope keeps one
 * representation instead of introducing a parsed model nothing downstream reads.</p>
 *
 * <h3>Why this fires in practice, and where it does not</h3>
 * <p>It fires for the <strong>group and payload members every Bean Validation constraint declares</strong>:
 * {@code @Size(min = 1, groups = Create.class)} is ordinary usage, and {@code groups}/{@code payload} are
 * {@code Class<?>[]} on every one of them — so the class literal is a real argument of a recognised
 * constraint, not a hypothetical one. It does <em>not</em> fire for a project's own annotation outside the
 * validation namespace ({@code @ShapeOf(MyType.class)}), because such an annotation is not a constraint
 * this generator recognises and never reaches these artifacts at all; carrying one would be a different
 * feature, and this class does not pretend to cover it.</p>
 */
final class AnnotationArguments {

    /**
     * A class literal whose type is a single simple name: the capture is the name, and the match is
     * replaced by the resolved spelling. The leading group is `^` or a non-identifier character, so the
     * pattern matches a whole name — {@code com.example.X.class} has no match to make, because the
     * character before {@code X} is a dot.
     */
    private static final Pattern SIMPLE_CLASS_LITERAL =
            Pattern.compile("(^|[^\\w.$])([A-Z][\\w$]*)\\.class\\b");

    private static final String JAVA_LANG = "java.lang.";

    /**
     * The names {@code java.lang} provides, which no file needs to import.
     *
     * <p>The import table is the file's <strong>explicit</strong> imports, so a bare {@code String} is
     * not in it — and that is the commonest class-valued argument there is
     * ({@code @Size(groups = String.class)}, a payload, a custom group). Without this list the resolver
     * would report every one of them as unresolvable, which would be both noisy and wrong: the name is
     * not ambiguous, the language simply supplies it.</p>
     *
     * <p>The list is the {@code java.lang} types that can sensibly appear in an annotation argument, not
     * the whole package: a name this list omits is reported, which is the safe direction — a report is
     * honest, and a wrongly qualified name would not compile.</p>
     */
    private static final Set<String> JAVA_LANG_NAMES = Set.of(
            "Object", "String", "CharSequence", "StringBuilder", "StringBuffer",
            "Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double", "Character", "Number",
            "Void", "Class", "Enum", "Record", "Iterable", "Comparable", "Runnable", "Thread",
            "Math", "System", "StrictMath", "Process", "ProcessBuilder", "Runtime",
            "Throwable", "Error", "Exception", "RuntimeException", "ArithmeticException",
            "IllegalArgumentException", "IllegalStateException", "NullPointerException",
            "UnsupportedOperationException", "ClassCastException", "NumberFormatException",
            "IndexOutOfBoundsException", "Deprecated", "Override", "SafeVarargs", "SuppressWarnings",
            "FunctionalInterface", "AutoCloseable", "Cloneable", "StackTraceElement", "Package",
            "Module", "ClassLoader", "ThreadLocal", "StringJoiner", "Character.UnicodeBlock");

    /**
     * Whether the name at {@code nameStart} is the text of a string literal rather than a type reference.
     *
     * <p>A class literal is written {@code X.class}, so a quoted occurrence is always inside a quoted
     * span — and the span has to be computed, not guessed from the neighbouring character. Both simpler
     * attempts were wrong, and both are worth recording because they are the obvious ones:
     * counting quotes across the whole argument says "not inside a string" whenever an even number
     * precedes it (the rendered Java line carries an outer pair), and testing only the character
     * immediately before the name misses {@code "a X.class b"}, where a space sits between the quote and
     * the name.</p>
     *
     * <p>The scan finds spans delimited by unescaped {@code "}, which is the grammar an annotation
     * argument's strings are written in.</p>
     */
    private static boolean insideString(String text, int nameStart) {
        boolean inString = false;
        for (int i = 0; i < nameStart; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++; // an escaped character cannot open or close a string
            } else if (c == '"') {
                inString = !inString;
            }
        }
        return inString;
    }

    private AnnotationArguments() {
    }

    /**
     * The argument text with every resolvable class reference qualified.
     *
     * @param text         the argument text as written, or {@code ""}
     * @param importTable  the declaring unit's simple name to fully qualified name table
     *                     ({@code EntityMetadataGenerator.importTableOf}), or {@code null}
     * @param location     where the text came from, for the diagnostic — usually the view and accessor
     * @param divergences  where an unresolved reference is reported, or {@code null} to stay silent
     */
    static String qualify(String text, Map<String, String> importTable, String location,
                          DivergenceReporter divergences) {
        if (text == null || text.isEmpty() || importTable == null || importTable.isEmpty()) {
            return text == null ? "" : text;
        }
        Matcher matcher = SIMPLE_CLASS_LITERAL.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        while (matcher.find()) {
            String simple = matcher.group(2);
            // An explicit import wins; otherwise a java.lang name is already unambiguous.
            String qualified = importTable.get(simple);
            if (qualified == null && JAVA_LANG_NAMES.contains(simple)) {
                qualified = JAVA_LANG + simple;
            }
            // The guard asks about the NAME's own offset, not the match's: the pattern's leading group is
            // `^` or one separator character, so `matcher.start()` sits on the separating character (or on
            // the opening quote) and the check belongs one character further in.
            boolean quoted = insideString(text, matcher.start(2));
            if (qualified == null || quoted) {
                // Left exactly as written, and named when it is a real unresolved type. `String.class`
                // and a nested `Outer.Inner.class` land in the first arm, and a regexp that looks like a
                // type lands in the second — which is why this is a report and not a failure: the text is
                // still what the author wrote, and a reader can see the generator did not qualify it.
                if (qualified == null && !quoted && divergences != null) {
                    divergences.report("annotation_class_not_resolved", location,
                            "the class literal '" + simple + ".class' is not a single name the declaring "
                                    + "file imports, so it cannot be qualified without guessing",
                            simple + ".class",
                            "a fully qualified class literal, or an import for " + simple,
                            "import " + simple + ", or write the literal fully qualified");
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(matcher.group(1) + qualified + ".class"));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
