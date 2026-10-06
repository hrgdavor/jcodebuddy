package hr.hrg.webview.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turn a {@link LocationFragment} into the position it names in a file's text — the host half of "a click lands
 * where the link said".
 *
 * <h3>Why a heuristic rather than a parser</h3>
 * <p>This module is the JDK and Gson, deliberately: it is what every host builds on, and a host must not drag a Java
 * parser (or OpenRewrite) into a webview plugin. So a name is found the way {@code @hrg/inject-examples} finds it,
 * and for the same reason — its own documentation says the match "is a heuristic, not a parser". The two rules that
 * keep that honest:</p>
 * <ol>
 *   <li><strong>Nothing is invented.</strong> When the name is not in the file, {@link #resolve} returns
 *       {@code null}, and the host says so. Landing on line 1 would cost a click to discover and would look like a
 *       working link.</li>
 *   <li><strong>Every answer says how it was found</strong> ({@link LocationResolution#how()}), because a heuristic
 *       that cannot be questioned is a guess with a straight face.</li>
 * </ol>
 *
 * <h3>What a name means</h3>
 * <p>A region directive wins over a declaration of the same name, exactly as in inject-examples' code rule ("an
 * explicit {@code #region <name>} directive always wins"), and that is what lets one link spelling name either. A
 * declaration is accepted only when it looks like one: the name followed by a parameter list <em>and</em> a brace
 * that opens and closes, which is what tells a method from a call to it.</p>
 *
 * <h3>Scope modifiers</h3>
 * <p>{@code -} (the body only) moves the landing line past the declaration's opening brace, so the caret is where the
 * body is. {@code +} and {@code ++} land on the declaration itself: they widen what an <em>include</em> copies
 * (annotations, doc comment), and a person following a link wants the code, not the prose about it.</p>
 */
public final class LocationResolver {

    /** The comment prefixes inject-examples accepts before a {@code #region} directive, in one alternation. */
    private static final String DIRECTIVE_PREFIX = "(?:#|//+|<!--|/\\*+|--|;|%|'|REM\\b)";

    private static final Pattern TYPE_DECLARATION = Pattern.compile(
        "\\b(?:class|interface|enum|record|struct|trait|object)\\s+%s\\b");

    private LocationResolver() {
    }

    /**
     * Resolve a location in a file's text.
     *
     * @param fragment the location, from {@link LocationFragment#parse}
     * @param fileText the file's content, or {@code null} when the caller has only an explicit position — a line, a
     *                 range or a JSON key path still needs the text, a line does not
     * @return the position, or {@code null} when the file does not contain what the fragment names
     */
    public static LocationResolution resolve(LocationFragment fragment, String fileText) {
        if (fragment == null) {
            return null;
        }
        switch (fragment.kind()) {
            case LINE:
                // An explicit position needs no file: the page said where to go, and the host clamps if it is past
                // the end (the frozen contract's rule 3).
                return new LocationResolution(fragment.line(), fragment.line(), fragment.kind(),
                    "the page's own line number");
            case RANGE:
                return new LocationResolution(fragment.from(), Math.max(fragment.from(), fragment.to()),
                    fragment.kind(), "the page's own line range");
            default:
                break;
        }
        if (fileText == null || fileText.isEmpty()) {
            return null;
        }
        List<String> lines = LocationResolution.lines(fileText);
        return switch (fragment.kind()) {
            case MEMBER, REGION -> byName(fragment, lines);
            case JSON -> byKeyPath(fragment, lines);
            default -> null;
        };
    }

    /** A region directive first, then a declaration of that name — the order inject-examples' code rule uses. */
    private static LocationResolution byName(LocationFragment fragment, List<String> lines) {
        LocationResolution region = regionDirective(fragment, lines);
        if (region != null) {
            return region;
        }
        return declaration(fragment, lines);
    }

    private static LocationResolution regionDirective(LocationFragment fragment, List<String> lines) {
        Pattern open = Pattern.compile("^\\s*" + DIRECTIVE_PREFIX + "\\s*#?region\\s+" + quote(fragment.name())
            + "\\s*(?:-->|\\*/)?\\s*$", Pattern.CASE_INSENSITIVE);
        Pattern close = Pattern.compile("^\\s*" + DIRECTIVE_PREFIX + "\\s*#?endregion\\b", Pattern.CASE_INSENSITIVE);
        for (int index = 0; index < lines.size(); index++) {
            if (!open.matcher(lines.get(index)).matches()) {
                continue;
            }
            int first = index + 1;
            int last = first;
            for (int scan = first; scan < lines.size(); scan++) {
                if (close.matcher(lines.get(scan)).matches()) {
                    last = scan;
                    break;
                }
                last = scan;
            }
            int from = shiftForBody(fragment, lines, first);
            return new LocationResolution(from + 1, Math.max(from, last - 1) + 1, LocationFragment.Kind.REGION,
                "a '#region " + fragment.name() + "' directive on line " + (index + 1));
        }
        return null;
    }

    private static LocationResolution declaration(LocationFragment fragment, List<String> lines) {
        String name = fragment.name();
        String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        Pattern type = Pattern.compile(String.format(TYPE_DECLARATION.pattern(), quote(simple)));
        Pattern callable = Pattern.compile("\\b" + quote(simple) + "\\s*\\(");
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            boolean isType = type.matcher(line).find();
            boolean isCallable = callable.matcher(line).find() && opensAndCloses(lines, index);
            if (!isType && !isCallable) {
                continue;
            }
            int from = shiftForBody(fragment, lines, index);
            return new LocationResolution(from + 1, from + 1, LocationFragment.Kind.MEMBER,
                (isType ? "a type declaration" : "a declaration") + " named '" + simple + "' on line " + (index + 1));
        }
        return null;
    }

    /**
     * Whether a brace opens at (or after) this line and closes again — which is what tells a declaration from a call
     * to it. A call ends its statement; a declaration has a body.
     */
    private static boolean opensAndCloses(List<String> lines, int from) {
        int depth = 0;
        boolean opened = false;
        for (int index = from; index < lines.size(); index++) {
            for (char character : lines.get(index).toCharArray()) {
                if (character == '{') {
                    depth++;
                    opened = true;
                } else if (character == '}') {
                    depth--;
                    if (opened && depth == 0) {
                        return true;
                    }
                }
            }
            if (opened && depth <= 0) {
                return false;
            }
        }
        return false;
    }

    /** {@code -} means the body only, so the landing line moves past the opening brace. */
    private static int shiftForBody(LocationFragment fragment, List<String> lines, int declarationLine) {
        if (!"-".equals(fragment.scope())) {
            return declarationLine;
        }
        for (int index = declarationLine; index < lines.size(); index++) {
            if (lines.get(index).indexOf('{') >= 0) {
                return Math.min(index + 1, lines.size() - 1);
            }
        }
        return declarationLine;
    }

    /** A dotted key path lands on the key's own line, found by its last segment as JSON spells it. */
    private static LocationResolution byKeyPath(LocationFragment fragment, List<String> lines) {
        String last = fragment.keys().get(fragment.keys().size() - 1);
        String segment = last.contains(".") ? last.substring(last.lastIndexOf('.') + 1) : last;
        Pattern quoted = Pattern.compile("\"" + quote(segment) + "\"\\s*:");
        for (int index = 0; index < lines.size(); index++) {
            if (quoted.matcher(lines.get(index)).find()) {
                return new LocationResolution(index + 1, index + 1, LocationFragment.Kind.JSON,
                    "the key '" + last + "' on line " + (index + 1));
            }
        }
        return null;
    }

    private static String quote(String text) {
        return Pattern.quote(text);
    }

    /** Case-insensitive lower-casing without the default locale, which would surprise a Turkish machine. */
    static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
