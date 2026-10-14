package hr.hrg.webview.core;

import java.util.List;
import java.util.Objects;

import hr.hrg.inject.section.Scope;
import hr.hrg.inject.section.SectionError;
import hr.hrg.inject.section.SectionReference;
import hr.hrg.inject.section.SectionReferenceException;

/**
 * Where a link points, INSIDE a file — the host half of the location grammar.
 *
 * <p>A generated page links to source, and a link that can only say "line 42" cannot say what the documentation
 * means. The repository's own docs are written with {@code @hrg/inject-examples}, whose markers name a real file and
 * a location inside it — a region, or a declaration by name, with a scope modifier. This reads those spellings, and
 * the ones that are only a location (a method name, a line range), so a host can be told what the page meant instead
 * of being handed a guessed line number.</p>
 *
 * <h3>The reference grammar is the reference implementation's</h3>
 * <p>The spelling of a reference — its path segments, the {@code -}/{@code +}/{@code ++} scope as a whole trailing
 * segment, its line ranges and columns, its normalisation and the exact errors — belongs to
 * {@code @hrg/inject-examples}, the reference implementation of section searching, whose Java port this module
 * consumes as {@code hr.hrg.inject:inject-examples}: {@link SectionReference#parse} reads the whole reference, an
 * explicit position included, so nothing here decides what a reference looks like. The JavaScript half
 * ({@code scripts/webview-location}) asks the same implementation through its {@code lib/section.mjs}, so the two
 * languages cannot spell a reference differently. What is local here is only the classification around the
 * reference: a {@code .json} key path, and which of the implementation's readings this contract can name.</p>
 *
 * <p>Both readers assert the same claims — {@code webview/conformance/location-fragments.json} — and neither
 * generates them, per {@code webview/conformance/README.md}: a rule that lives in two languages is only one rule
 * while both are checked against the same table. <strong>Change the vectors first, then this class and the module
 * beside it.</strong></p>
 *
 * <h3>The spellings</h3>
 * <ul>
 *   <li>{@code L42}, {@code L42:12} and {@code L42-L58} — an explicit position, which always wins, and a position may
 *       name the column it starts at after a colon. Lines and columns count from 1 and a range may not end before it
 *       starts, as the reference implementation reads them.</li>
 *   <li>{@code add}, {@code add/-}, {@code add/+}, {@code add/++}, {@code Cart/add/-} — a region or a declaration,
 *       with the scope modifiers {@code -} (the body only), {@code +} (with the annotations) and {@code ++} (with the
 *       annotations and the doc comment). <strong>A modifier is a whole trailing segment</strong> in the reference
 *       grammar (2026-10-13): the older leading forms ({@code -add}, {@code +add}, {@code ++add}) are a name and a
 *       refusal respectively, because {@code -} may appear in a name and {@code +} may not.</li>
 *   <li>In a {@code .json} file the reference is dotted key paths, comma-separated, because JSON has no declarations
 *       and no comments to hang a region on — a rule this contract keeps, checked before the reference grammar.</li>
 *   <li>Anything else is not a location, and a link carrying it falls back to the file: a fragment with a colon
 *       that is not a position (scheme-like), a malformed position, an empty fragment, a reference the implementation
 *       refuses, a bare name in a document without a path to anchor it — and the reference's own spellings that name
 *       more than one place: a selector list ({@code add,other}) and an envelope pair ({@code Cart/^,Cart/$}).</li>
 * </ul>
 */
public final class LocationFragment {

    /** What a fragment can be. */
    public enum Kind {
        /** One line: {@code #L42}. */
        LINE,
        /** A selection: {@code #L42-L58}. */
        RANGE,
        /** A region or declaration, by name and optional trailing scope segment: {@code #add/++}, {@code #add/-}. */
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
    /** The column of an explicit position, 1-based; 1 when the page gave none. */
    private final int column;
    private final String name;
    private final String scope;
    private final List<String> keys;

    private LocationFragment(Kind kind, int line, int from, int to, int column, String name, String scope,
                             List<String> keys) {
        this.kind = kind;
        this.line = line;
        this.from = from;
        this.to = to;
        this.column = Math.max(1, column);
        this.name = name;
        this.scope = scope;
        this.keys = keys == null ? List.of() : List.copyOf(keys);
    }

    private static LocationFragment line(int at, int column) {
        return new LocationFragment(Kind.LINE, at, 0, 0, column, null, null, null);
    }

    private static LocationFragment range(int from, int to, int column) {
        return new LocationFragment(Kind.RANGE, 0, from, to, column, null, null, null);
    }

    private static LocationFragment region(String name, String scope) {
        return new LocationFragment(Kind.REGION, 0, 0, 0, 1, name, scope, null);
    }

    private static LocationFragment member(String name) {
        return new LocationFragment(Kind.MEMBER, 0, 0, 0, 1, name, null, null);
    }

    private static LocationFragment json(List<String> keys) {
        return new LocationFragment(Kind.JSON, 0, 0, 0, 1, null, null, keys);
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

        // The reference itself — its path segments, its scope modifier, its line ranges and columns, its
        // normalisation and its errors — is the Java implementation's to read, not a second copy here: it is the
        // counterpart of the parser the JavaScript half asks, so every spelling reads the same in both languages and
        // a reference it refuses (`+add`, `L0`, `L58-L42`, `L42-`) is refused here too. This used to carry its own
        // 'L<line>'-shaped regexes, which is the drift the reference exists to end (2026-10-13).
        SectionReference reference;
        try {
            reference = SectionReference.parse(text);
        } catch (SectionReferenceException | SectionError refused) {
            reference = null;
        }
        var selectors = reference == null
            ? List.<SectionReference.Selector>of()
            : reference.selectors();

        // An explicit position always wins: a page that wrote a line number meant that line. The implementation read
        // it, so this only carries its answer into this contract's kinds — and a position may name a column after a
        // colon (`#L42:12`), which the implementation also reads and this contract carries, because a caret is
        // positioned with a line and a column. Nothing here splits the text on ':': the line points are its answer,
        // and reading them is what keeps `#L42:12` a position rather than a scheme-like fragment (2026-10-13).
        if (selectors.size() == 1 && selectors.get(0).isLines()) {
            SectionReference.Selector lines = selectors.get(0);
            SectionReference.LinePoint from = lines.from();
            if (from == null) {
                return null;
            }
            int column = from.column() == null ? 1 : from.column();
            // A range carries the column it starts at. Its END column is not carried: this contract's hosts place one
            // caret and select a line span, so `#L42:12-L44:3` lands at (42, 12) and runs to the end of line 44 as
            // far as the editor can - the same shape a `#L42-L58` range has always had.
            return lines.to() == null
                ? line(from.line(), column)
                : range(from.line(), lines.to().line(), column);
        }

        // A colon is never part of a reference: a fragment that carries one is scheme-like, and is reported as an
        // ordinary link rather than as a member nobody named that. Only reached when the implementation read no
        // position above, which is why `#L42:12` is a location and `mailto:dev@example.com` is not.
        if (text.indexOf(':') >= 0) {
            return null;
        }

        // In .json there are no declarations to find, so any bare reference is the JSON rule's key paths - checked
        // before the reference's path grammar, because a leading '-' is a legal key character and not a modifier
        // there, and because a comma is a key list here while the reference reads it as a list of selectors.
        if (isJson(path)) {
            return jsonKeys(text);
        }
        if (reference == null) {
            return null;
        }

        // What is left is what this contract can name: ONE path selector. A list names several places and an envelope
        // is a property of a pair - neither is one location here, so a link carrying one falls back to the file
        // (rule 2) rather than being guessed at.
        if (selectors.size() != 1) {
            return null;
        }
        SectionReference.Selector selector = selectors.get(0);
        String name = String.join("/", selector.segments());
        if (name.isEmpty()) {
            return null;
        }
        if (selector.scope() != Scope.DECLARATION) {
            return region(name, modifier(selector.scope()));
        }

        // A bare name is a declaration OR a '#region <name>' directive: the resolver tries the declaration and then
        // the region, because a link should not have to know which one the author wrote. A name that is neither is
        // caught there, where the file's text is available.
        //
        // In a document it is a heading anchor as well, and the page's own scroll wins: a renderer knows its headings,
        // so it only asks a host when the name is not one - which is why a bare name needs a file type at all.
        if (path != null && !path.isEmpty()) {
            return member(name);
        }

        return null;
    }

    /** The `-`/`+`/`++` spelling this contract uses for a scope the reference implementation parsed. */
    private static String modifier(Scope scope) {
        return switch (scope) {
            case BODY -> "-";
            case ANNOTATED -> "+";
            case DOCUMENTED -> "++";
            case DECLARATION -> "";
        };
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

    /**
     * The column of an explicit position, 1-based, for {@link Kind#LINE} and {@link Kind#RANGE}; 1 when none was
     * written. A position is one line and one column, and a host places its caret with both.
     */
    public int column() {
        return column;
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
            case LINE -> "line " + line + atColumn();
            case RANGE -> "lines " + from + "\u2013" + to + atColumn();
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

    /** {@code , column 12} when a column was written, empty otherwise - so every summary stays a sentence. */
    private String atColumn() {
        return column > 1 ? ", column " + column : "";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof LocationFragment that)) {
            return false;
        }
        return kind == that.kind && line == that.line && from == that.from && to == that.to
            && column == that.column && Objects.equals(name, that.name) && Objects.equals(scope, that.scope)
            && keys.equals(that.keys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, line, from, to, column, name, scope, keys);
    }

    @Override
    public String toString() {
        return kind + ": " + summary();
    }
}
