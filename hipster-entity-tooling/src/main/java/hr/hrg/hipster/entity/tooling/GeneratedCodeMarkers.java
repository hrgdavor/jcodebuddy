package hr.hrg.hipster.entity.tooling;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The generated-code marker vocabulary: the shapes a generator emits, and the shapes a parser recognises
 * without knowing anything about this generator.
 *
 * <p>DEC-035 defines four markers and one rule that makes the set extensible. This class is the single
 * place those shapes are written down, for two callers with opposite needs:
 *
 * <ul>
 *   <li><strong>a generator</strong> builds a marker with {@link #fileHeader} or one of the {@code member}
 *       / {@code region} / {@code block} helpers, so every emitter produces the same spelling and a
 *       parser has one shape to match rather than eleven;</li>
 *   <li><strong>a parser or an agent</strong> asks {@link #recognise} what a line is, and gets a
 *       {@link Found} that says whether the marker is one this vocabulary {@linkplain Found#supported()
 *       supports} or one it can only name. An unknown marker is <em>reported</em>, never treated as
 *       hand-written: DEC-035's rule, and the reason the vocabulary can grow without making an
 *       out-of-date parser silently wrong.</li>
 * </ul>
 *
 * <h3>What a marker is, and what it is not</h3>
 *
 * <p>A marker is a Java line comment whose text begins with {@code @generated}. Nothing else about the
 * comment makes it a marker, and no marker tells a parser which generator wrote it in any way the parser
 * needs: the generator's fully qualified name is present, but only so a human can jump to it. A parser
 * that implements all four markers implements them for every generator in this repository and for any
 * future one that follows the rules.
 *
 * <p>The {@code @generated} keyword was chosen over this project's earlier {@code {@link <fqn>}} spelling
 * because {@code @generated} is the token tooling, IDEs and reviewers already look for, and because it
 * reads as a marker rather than as a javadoc tag in a place javadoc does not process. The {@code {@link}}
 * form is retained only where javadoc actually renders it.
 *
 * <h3>Why the grammar is deliberately loose after the keyword</h3>
 *
 * <p>{@link #recognise} accepts any word after {@code @generated} as a marker, whether or not this class
 * implements it. That is what lets a parser refuse rather than guess: a future
 * {@code // @generated patchwork} is recognisable as <em>a marker</em> by a parser that has never heard of
 * patchwork, so it can say so instead of falling through to "hand-written code, safe to edit" — the one
 * outcome DEC-035 forbids.
 */
public final class GeneratedCodeMarkers {

    /** The keyword that makes a line comment a marker, and the only one. */
    public static final String KEYWORD = "@generated";

    /**
     * A marker line: the keyword, then a scope word, then a free-text payload.
     *
     * <p>Anchored on the keyword at the start of the comment text, which is what keeps prose about markers
     * from being a marker — including this class's own javadoc. The scope word is whatever follows, so an
     * unknown scope parses as an unknown marker rather than as no marker.
     */
    private static final Pattern MARKER = Pattern.compile(
            "^\\s*//\\s*" + Pattern.quote(KEYWORD) + "(?:\\s+(\\S+)(.*))?\\s*$");

    /** The one scope word every generated file carries, and the only one most parsers need. */
    public static final String SCOPE_FILE = "file";

    /** A member — one declaration and its body — emitted into a file that is not wholly generated. */
    public static final String SCOPE_MEMBER = "member";

    /** A line range, paired by id. */
    public static final String SCOPE_REGION = "region";

    /**
     * A statement or block, bounded by the language's own braces rather than by a closing marker.
     *
     * <p>This is the marker for the case DEC-035 names: a generated {@code switch} over routes, an
     * {@code if/else} chain, a {@code try}. The marker says the construct that starts here is generated;
     * the construct's braces say where it ends. A generator MUST NOT mark each {@code case} arm.
     */
    public static final String SCOPE_BLOCK = "block";

    /** The four scopes this vocabulary defines. A marker with another word is recognised, not supported. */
    public static final List<String> KNOWN_SCOPES =
            List.of(SCOPE_FILE, SCOPE_MEMBER, SCOPE_REGION, SCOPE_BLOCK);

    /** The word that begins the closing half of a region pair. */
    public static final String REGION_END = "end";

    private GeneratedCodeMarkers() {
    }

    // ---- emission: what a generator writes ----

    /**
     * The two-line class-file header, with the file marker on the first line.
     *
     * <p>The second line is DEC-021's JSON5 config and is deliberately unchanged: a parser must never have
     * to parse JSON5 to find a boundary, so the boundary lives on the first line and the config stays the
     * generator's private business.
     *
     * @param generatorFqn the generator's fully qualified name, so a human can jump to it
     * @param description one line describing the file, for a human
     */
    public static String fileHeader(String generatorFqn, String description) {
        return "// " + KEYWORD + " " + SCOPE_FILE + " " + generatorFqn + " — " + description + "\n"
                + "// {enabled:true, blockMarker: \"implicit\"}\n";
    }

    /**
     * The two-line header for a field enum, which carries one extra DEC-021 option.
     *
     * @param generatorFqn the generator's fully qualified name
     * @param description one line describing the file
     */
    public static String fieldEnumFileHeader(String generatorFqn, String description) {
        return "// " + KEYWORD + " " + SCOPE_FILE + " " + generatorFqn + " — " + description + "\n"
                + "// {enabled:true, entityFieldEnum:true, blockMarker: \"implicit\"}\n";
    }

    /** A member marker: the declaration that follows it is generated, and so is its body. */
    public static String member(String generatorFqn) {
        return "// " + KEYWORD + " " + SCOPE_MEMBER + " " + generatorFqn;
    }

    /** A block marker: the statement or block that follows is generated, and its braces bound it. */
    public static String block(String generatorFqn) {
        return "// " + KEYWORD + " " + SCOPE_BLOCK + " " + generatorFqn;
    }

    /** The opening half of a region pair. */
    public static String regionBegin(String id, String generatorFqn) {
        return "// " + KEYWORD + " " + SCOPE_REGION + " begin " + id + " " + generatorFqn;
    }

    /** The closing half of a region pair, with the same id. */
    public static String regionEnd(String id) {
        return "// " + KEYWORD + " " + SCOPE_REGION + " " + REGION_END + " " + id;
    }

    // ---- recognition: what a parser reads ----

    /**
     * What a line turned out to be.
     *
     * @param scope     the scope word, or {@code null} for a bare {@code @generated} with no scope
     * @param payload   everything after the scope word, trimmed; the generator FQN and its description,
     *                  or a region id, depending on the scope
     * @param line      the 1-based line number the marker was found on
     * @param text      the line as written, so a report can quote it
     */
    public record Found(String scope, String payload, int line, String text) {

        /** True when this vocabulary defines the marker's scope and placement. */
        public boolean supported() {
            return scope != null && KNOWN_SCOPES.contains(scope);
        }

        /**
         * A one-line report naming an unsupported marker.
         *
         * <p>The shape a parser should print. It names the marker, its scope and its location, and it does
         * not describe what the marker means — because the point is that the parser does not know.
         */
        public String unsupportedReport(String file) {
            String named = scope == null ? KEYWORD + " (no scope word)" : KEYWORD + " " + scope;
            return "unsupported generated-code marker: " + named + " at " + file + ":" + line
                    + " — this code is generated by a rule this parser does not implement, so it must not "
                    + "be treated as hand-written";
        }

        /** A short label, for a listing. */
        public String label() {
            return scope == null ? KEYWORD : KEYWORD + " " + scope;
        }
    }

    /**
     * Recognise a marker on one line.
     *
     * <p>Accepts any scope word, so an unsupported marker comes back as a {@link Found} whose
     * {@link Found#supported()} is false rather than as "not a marker". That distinction is the whole
     * contract: a parser must be able to refuse.
     *
     * @param line     the 1-based line number, for the report
     * @param text     the line
     */
    public static Optional<Found> recognise(int line, String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = MARKER.matcher(text);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String scope = matcher.group(1);
        String payload = matcher.group(2) == null ? "" : matcher.group(2).trim();
        return Optional.of(new Found(scope, payload, line, text.strip()));
    }

    /**
     * Whether a line is a marker of any kind, supported or not.
     *
     * <p>The cheap question, for a caller that only needs to skip marker lines while scanning.
     */
    public static boolean isMarker(String text) {
        return text != null && MARKER.matcher(text).matches();
    }

    /**
     * Whether a file is wholly generated, judged from its first non-blank line.
     *
     * <p>A file marker covers every line below it, so a parser that finds one needs no further scanning.
     */
    public static boolean isWhollyGenerated(List<String> lines) {
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            return recognise(1, line)
                    .map(found -> SCOPE_FILE.equals(found.scope()))
                    .orElse(false);
        }
        return false;
    }

    /**
     * Every marker in a file, in order, including the unsupported ones.
     *
     * <p>The caller decides what to do about an unsupported marker; this reports and does not judge, so
     * that "list what is here" and "refuse what I cannot read" stay separate decisions.
     */
    public static List<Found> scan(List<String> lines) {
        List<Found> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            recognise(i + 1, lines.get(i)).ifPresent(found::add);
        }
        return List.copyOf(found);
    }

    /**
     * Every unsupported marker in a file, in order — the list a parser must report before it edits anything.
     */
    public static List<Found> unsupported(List<String> lines) {
        return scan(lines).stream().filter(found -> !found.supported()).toList();
    }

    /**
     * The generator FQN on a file marker, when there is one.
     *
     * <p>For a tool that wants to say which generator produced a file. A parser that only needs the
     * boundary never calls this.
     */
    public static Optional<String> generatorOfFileMarker(List<String> lines) {
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            return recognise(1, line)
                    .filter(found -> SCOPE_FILE.equals(found.scope()))
                    .map(found -> {
                        int space = found.payload().indexOf(' ');
                        String first = space < 0 ? found.payload() : found.payload().substring(0, space);
                        // The description is separated by an em dash; take the token before it.
                        int dash = first.indexOf('—');
                        return dash < 0 ? first : first.substring(0, dash).trim();
                    })
                    .filter(fqn -> !fqn.isEmpty());
        }
        return Optional.empty();
    }

    /** Whether the given word is a scope this vocabulary defines, case-insensitively. */
    public static boolean isKnownScope(String scope) {
        return scope != null && KNOWN_SCOPES.contains(scope.toLowerCase(Locale.ROOT));
    }
}
