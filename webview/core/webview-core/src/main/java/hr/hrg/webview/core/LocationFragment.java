package hr.hrg.webview.core;

import java.util.List;
import java.util.Objects;

/**
 * Where a link points, INSIDE a file — the host half of the location grammar.
 *
 * <p>A generated page links to source, and a link that can only say "line 42" cannot say what the documentation
 * means. The repository's own docs are written with {@code @hrg/inject-examples}, whose markers name a real file and
 * a location inside it — a region, or a declaration by name, with a scope modifier. This reads those spellings, and
 * the ones that are only a location (a method name, a line range), so a host can be told what the page meant instead
 * of being handed a guessed line number.</p>
 *
 * <h3>One grammar, two languages</h3>
 * <p>The page half is {@code scripts/webview-location} (JavaScript, for the renderers). Both read the same claims —
 * {@code webview/conformance/location-fragments.json} — and neither generates them, per
 * {@code webview/conformance/README.md}: a rule that lives in two languages is only one rule while both are checked
 * against the same table. <strong>Change the vectors first, then this class and the module beside it.</strong></p>
 *
 * <h3>The spellings</h3>
 * <ul>
 *   <li>{@code L42} and {@code L42-L58} — an explicit position, which always wins.</li>
 *   <li>{@code add}, {@code -add}, {@code +add}, {@code ++add} — a region or a declaration, with the scope
 *       modifiers {@code -} (the body only), {@code +} (with the annotations) and {@code ++} (with the annotations
 *       and the doc comment): given a name the host resolves it as a declaration or as a region, because a link
 *       should not have to know which one the author wrote.</li>
 *   <li>In a {@code .json} file the reference is dotted key paths, because JSON has no declarations
 *       and no comments to hang a region on.</li>
 *   <li>Anything else is not a location: a fragment carrying a colon (a scheme-like one), a malformed position, an
 *       empty fragment, or a bare name in a document — where the page scrolls to its own heading first, and only
 *       asks a host when the name is not one.</li>
 * </ul>
 */
public final class LocationFragment {

    /** What a fragment can be. */
    public enum Kind {
        /** One line: {@code #L42}. */
        LINE,
        /** A selection: {@code #L42-L58}. */
        RANGE,
        /** A region or declaration, by name and optional scope: {@code #++add}, {@code #add-}. */
        REGION,
        /** Dotted key paths in a {@code .json} file: {@code #name,scripts.test}. */
        JSON,
        /** A name that is a declaration or a region: {@code #someMethod}, {@code #regionName}. */
        MEMBER
    }

    private final Kind kind;
    private final int line;
    private final int from;
    private final int to;
    private final String name;
    private final String scope;
    private final List<String> keys;

    private LocationFragment(Kind kind, int line, int from, int to, String name, String scope,
                             List<String> keys) {
        this.kind = kind;
        this.line = line;
        this.from = from;
        this.to = to;
        this.name = name;
        this.scope = scope;
        this.keys = keys == null ? List.of() : List.copyOf(keys);
    }

    private static LocationFragment line(int at) {
        return new LocationFragment(Kind.LINE, at, 0, 0, null, null, null);
    }

    private static LocationFragment range(int from, int to) {
        return new LocationFragment(Kind.RANGE, 0, from, to, null, null, null);
    }

    private static LocationFragment region(String name, String scope) {
        return new LocationFragment(Kind.REGION, 0, 0, 0, name, scope, null);
    }

    private static LocationFragment member(String name) {
        return new LocationFragment(Kind.MEMBER, 0, 0, 0, name, null, null);
    }

    private static LocationFragment json(List<String> keys) {
        return new LocationFragment(Kind.JSON, 0, 0, 0, null, null, keys);
    }

    /**
     * Parse a fragment into a location, or {@code null} when it is not one.
     *
     * @param path     the file the link points at; its type decides which rule a reference follows, exactly as it
     *                 does for inject-examples
     * @param fragment the part after {@code #}; one leading {@code #} is tolerated, and a missing or non-string
     *                 fragment is not a location
     */
    public static LocationFragment parse(String path, String fragment) {
        if (fragment == null) {
            return null;
        }
        // A page may hand over the fragment with its '#' or without; both mean the same thing.
        String text = fragment.startsWith("#") ? fragment.substring(1) : fragment;
        if (text.isEmpty()) {
            return null;
        }

        // An explicit position always wins: a page that wrote a line number meant that line.
        var range = java.util.regex.Pattern.compile("^L(\\d+)-L(\\d+)$").matcher(text);
        if (range.matches()) {
            return range(Integer.parseInt(range.group(1)), Integer.parseInt(range.group(2)));
        }
        var single = java.util.regex.Pattern.compile("^L(\\d+)$").matcher(text);
        if (single.matches()) {
            return line(Integer.parseInt(single.group(1)));
        }
        // A fragment that starts like a position and is not one ('L42-') is a page's mistake, not a declaration
        // called 'L42-' - the same reasoning that makes '+++add' malformed rather than a region called '+add'.
        if (text.matches("^L\\d.*")) {
            return null;
        }

        // A colon is never part of a reference: a fragment that carries one is scheme-like, and is reported as an
        // ordinary link rather than as a member nobody named that.
        if (text.indexOf(':') >= 0) {
            return null;
        }

        // In .json there are no declarations to find, so any bare reference is the JSON rule's key paths - which is
        // checked before the scope modifiers, because a leading '-' is a legal key character and not a modifier here.
        if (isJson(path)) {
            return jsonKeys(text);
        }

        // The spellings are bare. No language this repository reads has a declaration whose name starts with '-' or
        // '+', so reading a leading modifier as a modifier is unambiguous.
        if (text.startsWith("+") || text.startsWith("-")) {
            Scoped scoped = scopedName(text);
            return scoped == null ? null : region(scoped.name, scoped.scope);
        }

        // A bare name is a declaration OR a '#region <name>' directive: the resolver tries the declaration and then
        // the region, because a link should not have to know which one the author wrote. A name that is neither is
        // caught there, where the file's text is available.
        //
        // In a document it is a heading anchor as well, and the page's own scroll wins: a renderer knows its headings,
        // so it only asks a host when the name is not one.
        if (path != null && !path.isEmpty()) {
            return member(text);
        }

        return null;
    }

    private static boolean isJson(String path) {
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".json");
    }

    /** Dotted key paths, comma-separated. Shared by the prefixed and unprefixed spellings. */
    private static LocationFragment jsonKeys(String reference) {
        List<String> keys = new java.util.ArrayList<>();
        for (String key : reference.split(",", -1)) {
            String trimmed = key.trim();
            if (!trimmed.isEmpty()) {
                keys.add(trimmed);
            }
        }
        return keys.isEmpty() ? null : json(keys);
    }

    /** A scope modifier with the name it applies to, or {@code null} when what is left cannot be a name. */
    private static Scoped scopedName(String reference) {
        String scope = "";
        String name = reference;
        if (reference.startsWith("++")) {
            scope = "++";
            name = reference.substring(2);
        } else if (reference.startsWith("+")) {
            scope = "+";
            name = reference.substring(1);
        } else if (reference.startsWith("-")) {
            scope = "-";
            name = reference.substring(1);
        }
        if (name.isEmpty() || name.startsWith("+") || name.startsWith("-")) {
            return null;
        }
        return new Scoped(scope, name);
    }

    private record Scoped(String scope, String name) {
    }

    public Kind kind() {
        return kind;
    }

    /** The line, for {@link Kind#LINE}. */
    public int line() {
        return line;
    }

    /** The first line of a {@link Kind#RANGE}, as written — a reversed range is the page's mistake to fix. */
    public int from() {
        return from;
    }

    /** The last line of a {@link Kind#RANGE}, as written. */
    public int to() {
        return to;
    }

    /** The region or declaration name, for {@link Kind#REGION} and {@link Kind#MEMBER}. */
    public String name() {
        return name;
    }

    /** The scope modifier for a {@link Kind#REGION}: empty, {@code -}, {@code +} or {@code ++}. */
    public String scope() {
        return scope;
    }

    /** The dotted key paths, for {@link Kind#JSON}. */
    public List<String> keys() {
        return keys;
    }

    /** A short human description, for a link's tooltip and for a host's log line. */
    public String summary() {
        return switch (kind) {
            case LINE -> "line " + line;
            case RANGE -> "lines " + from + "\u2013" + to;
            case MEMBER -> "member " + name;
            case REGION -> "region " + name + switch (scope) {
                case "-" -> " (body only)";
                case "+" -> " (with annotations)";
                case "++" -> " (with annotations and doc comment)";
                default -> "";
            };
            case JSON -> "keys " + String.join(", ", keys);
        };
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof LocationFragment that)) {
            return false;
        }
        return kind == that.kind && line == that.line && from == that.from && to == that.to
            && Objects.equals(name, that.name) && Objects.equals(scope, that.scope)
            && keys.equals(that.keys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, line, from, to, name, scope, keys);
    }

    @Override
    public String toString() {
        return kind + ": " + summary();
    }
}
