package hr.hrg.webview.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import hr.hrg.inject.section.SectionError;
import hr.hrg.inject.section.SectionReferenceException;
import hr.hrg.inject.section.SectionResolver;
import hr.hrg.inject.section.Syntaxes;

/**
 * Turn a {@link LocationFragment} into the position it names in a file's text — the host half of "a click lands
 * where the link said".
 *
 * <h3>Where the rules live</h3>
 * <p>The matching rules are <b>not here</b>. They belong to {@code @hrg/inject-examples} — the source of truth for
 * targeting beyond a line number — which this module consumes as {@code hr.hrg.inject:inject-examples}. Its
 * JavaScript implementation defines the grammar; this class asks its Java port where a reference lands, so a host and
 * a page cannot drift apart about what a link means. Two rules stay here, because that library deliberately does not
 * own them (its module boundary exports the matching algorithm only):
 *
 * <ul>
 *   <li>an explicit position — {@code L42}, {@code L42-L58} — which a page settles without any file;</li>
 *   <li>a {@code .json} key path, which is a data-format rule rather than a code section.</li>
 * </ul>
 *
 * <p>Two rules keep the answer honest:
 *
 * <ol>
 *   <li><strong>Nothing is invented.</strong> When the name is not in the file, {@link #resolve} returns
 *       {@code null}, and the host says so. Landing on line 1 would cost a click to discover and would look like a
 *       working link.</li>
 *   <li><strong>Every answer says how it was found</strong> ({@link LocationResolution#how()}), because an answer
 *       that cannot be questioned is a guess with a straight face.</li>
 * </ol>
 *
 * <h3>Where a modifier lands</h3>
 * <p>{@code -} (the body only) lands inside the body, where the code is. {@code +} and {@code ++} land on the
 * declaration itself: they widen what an <em>include</em> copies (annotations, doc comment), and a person following
 * a link wants the code rather than the prose about it — so for navigation the modifier is dropped and the
 * declaration's own span is the answer.
 */
public final class LocationResolver {

    private LocationResolver() {
    }

    /** Resolve a location in a file's text, with the file's type unknown — the library's default engine. */
    public static LocationResolution resolve(LocationFragment fragment, String fileText) {
        return resolve(fragment, fileText, null);
    }

    /**
     * Resolve a location in a file's text.
     *
     * @param fragment the location, from {@link LocationFragment#parse}
     * @param fileText the file's content, or {@code null} when the caller has only an explicit position — a line, a
     *                 range or a JSON key path still needs the text, a line does not
     * @param path     the file's path, whose extension selects the language engine; {@code null} when the caller has
     *                 none, which resolves through inject-examples' language-agnostic default engine
     * @return the position, or {@code null} when the file does not contain what the fragment names
     */
    public static LocationResolution resolve(LocationFragment fragment, String fileText, String path) {
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
        if (fragment.kind() == LocationFragment.Kind.JSON) {
            return byKeyPath(fragment, LocationResolution.lines(fileText));
        }
        return byReference(fragment, fileText, path);
    }

    /**
     * Where the reference lands, as {@code @hrg/inject-examples} resolves it. The fragment's scope becomes the
     * library's trailing modifier, except that {@code +} and {@code ++} are dropped for navigation (see the class
     * note): a reader wants the declaration, not the annotations above it.
     *
     * <p>A qualified name is tried as written first, and by its last segment second. The library matches a dotted
     * name as one token — that is its own rule for markers, where {@code Foo.Bar} really can be the name — while a
     * link that says {@code a.SomeFile} means the member {@code SomeFile}, so the fallback is the host's to make.
     */
    private static LocationResolution byReference(LocationFragment fragment, String fileText, String path) {
        String scope = "-".equals(fragment.scope()) ? "-" : "";
        LocationResolution exact = attempt(fragment, fragment.name() + scope, fileText, path);
        if (exact != null) {
            return exact;
        }
        String name = fragment.name();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return attempt(fragment, name.substring(dot + 1) + scope, fileText, path);
        }
        return null;
    }

    /** One resolution attempt; {@code null} when the file has no such section, which is a refusal, not a guess. */
    private static LocationResolution attempt(LocationFragment fragment, String reference, String fileText,
                                             String path) {
        try {
            SectionResolver.Plan plan = SectionResolver.planSection(fileText, reference,
                Syntaxes.lexerForPath(path));
            String where = plan.startLine() == plan.endLine()
                ? "line " + plan.startLine()
                : "lines " + plan.startLine() + "-" + plan.endLine();
            // The library says which matcher won, and that is what a reader is told: a region directive
            // that says so, or a declaration found by name.
            LocationFragment.Kind kind = "region".equals(plan.kind())
                ? LocationFragment.Kind.REGION
                : LocationFragment.Kind.MEMBER;
            return new LocationResolution(plan.startLine(), plan.endLine(), kind,
                "inject-examples resolved \"" + plan.reference().canonical() + "\" to " + where
                    + ("region".equals(plan.kind()) ? ", a '#region' directive"
                        : ", a " + plan.kind()));
        } catch (SectionReferenceException | SectionError notThere) {
            // Nothing is invented: a name the file does not have is a refusal, and the host reports it.
            return null;
        }
    }

    /** A dotted key path lands on the key's own line, found by its last segment as JSON spells it. */
    private static LocationResolution byKeyPath(LocationFragment fragment, List<String> lines) {
        String last = fragment.keys().get(fragment.keys().size() - 1);
        String segment = last.contains(".") ? last.substring(last.lastIndexOf('.') + 1) : last;
        Pattern quoted = Pattern.compile("\"" + Pattern.quote(segment) + "\"\\s*:");
        for (int index = 0; index < lines.size(); index++) {
            if (quoted.matcher(lines.get(index)).find()) {
                return new LocationResolution(index + 1, index + 1, LocationFragment.Kind.JSON,
                    "the key '" + last + "' on line " + (index + 1));
            }
        }
        return null;
    }

    /** Case-insensitive lower-casing without the default locale, which would surprise a Turkish machine. */
    static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
