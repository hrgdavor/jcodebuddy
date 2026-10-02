package hr.hrg.jcodebuddy.generated;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The generated-code marker vocabulary: the shapes a generator writes, and the shapes a parser recognises
 * without knowing anything about the generator that wrote them.
 *
 * <p>DEC-035 defines four markers and one rule that makes the set extensible. This class is the single
 * place those shapes are written down, for two callers with opposite needs:
 *
 * <ul>
 *   <li><strong>a generator</strong> builds a marker with {@link #fileHeader} or one of the {@code member}
 *       / {@code region} / {@code block} helpers, so every emitter produces the same spelling and a parser
 *       has one shape to match rather than eleven;</li>
 *   <li><strong>a parser</strong> asks {@link #recognise} what a line is, and gets a {@link Found} that
 *       says whether the marker is one this vocabulary {@linkplain Found#supported() supports} or one it
 *       can only name. An unknown marker is <em>reported</em>, never treated as hand-written: that is
 *       DEC-035's rule, and the reason the vocabulary can grow without making an out-of-date parser
 *       silently wrong.</li>
 * </ul>
 *
 * <p>Finding the <em>extent</em> of a marker's region is {@link GeneratedCodeParser}'s job. This class
 * answers "is this line a marker, and which one"; that one answers "where does it end".
 *
 * <h3>What a marker is, and what it is not</h3>
 *
 * <p>A marker is a Java line comment whose text begins with {@code @generated}. Nothing else about the
 * comment makes it a marker, and no marker tells a parser which generator wrote it in any way the parser
 * needs: the generator's fully qualified name is present, but only so a human can jump to it. A parser
 * that implements all four markers implements them for every generator that follows the rules, including
 * generators written later.
 *
 * <p>{@code @generated} was chosen over an earlier {@code {@link <fqn>}} spelling because it is the token
 * tooling, IDEs and reviewers already look for, and because it reads as a marker rather than as a javadoc
 * tag in a place javadoc does not process. {@code {@link}} is retained only where javadoc renders it.
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

    /** The word that begins the opening half of a region pair. */
    public static final String REGION_BEGIN = "begin";

    /**
     * The separator between the generator's name and its description on a file marker.
     *
     * <p>An em dash, and the parser is the thing that has to agree with the emitter about it, so it lives
     * here rather than being typed twice.
     */
    public static final char DESCRIPTION_SEPARATOR = '\u2014';

    private GeneratedCodeMarkers() {
    }

    // ---- emission: what a generator writes ----

    /**
     * The two-line class-file header, with the file marker on the first line.
     *
     * <p>The second line is the JSON5 config and is deliberately opaque to a parser: a parser must never
     * have to parse JSON5 to find a boundary, so the boundary lives on the first line and the options stay
     * the generator's own business.
     *
     * @param generatorFqn the generator's fully qualified name, so a human can jump to it
     * @param description  one line describing the file, for a human
     */
    public static String fileHeader(String generatorFqn, String description) {
        return "// " + KEYWORD + " " + SCOPE_FILE + " " + generatorFqn
                + " " + DESCRIPTION_SEPARATOR + " " + description + "\n"
                + "// {enabled:true, blockMarker: \"implicit\"}\n";
    }

    /**
     * The two-line header for a field enum, which carries one extra config option.
     *
     * @param generatorFqn the generator's fully qualified name
     * @param description  one line describing the file
     */
    public static String fieldEnumFileHeader(String generatorFqn, String description) {
        return "// " + KEYWORD + " " + SCOPE_FILE + " " + generatorFqn
                + " " + DESCRIPTION_SEPARATOR + " " + description + "\n"
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
        return "// " + KEYWORD + " " + SCOPE_REGION + " " + REGION_BEGIN + " " + id + " " + generatorFqn;
    }

    /** The closing half of a region pair, with the same id. */
    public static String regionEnd(String id) {
        return "// " + KEYWORD + " " + SCOPE_REGION + " " + REGION_END + " " + id;
    }

    // ---- recognition: what a parser reads ----

    /**
     * What a line turned out to be.
     *
     * @param scope   the scope word, or {@code null} for a bare {@code @generated} with no scope
     * @param payload everything after the scope word, trimmed; the generator FQN and its description, or a
     *                region id, depending on the scope
     * @param line    the 1-based line number the marker was found on
     * @param text    the line as written, so a report can quote it
     */
    public record Found(String scope, String payload, int line, String text) {

        /** True when this vocabulary defines the marker's scope and placement. */
        public boolean supported() {
            return scope != null && KNOWN_SCOPES.contains(scope);
        }

        /** Whether this marker is the file marker, which covers everything below it. */
        public boolean isFile() {
            return SCOPE_FILE.equals(scope);
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

        /**
         * The generator's name, when the payload starts with something that looks like a class name.
         *
         * <p>Advisory, and the only thing a parser is allowed to read out of the payload. A caller that
         * needs to know which generator wrote a file can ask; a caller that only needs the boundary never
         * touches this, which is why the vocabulary works for generators the parser has never seen.
         */
        public Optional<String> generator() {
            if (payload == null || payload.isBlank()) {
                return Optional.empty();
            }
            String head = payload;
            int dash = head.indexOf(DESCRIPTION_SEPARATOR);
            if (dash >= 0) {
                head = head.substring(0, dash);
            }
            // A region payload is `begin <id> <generator>`: skip the direction word and the id.
            String[] parts = head.trim().split("\\s+");
            int index = 0;
            if (parts.length > 0 && (REGION_BEGIN.equals(parts[0]) || REGION_END.equals(parts[0]))) {
                index = 2;
            }
            if (index >= parts.length) {
                return Optional.empty();
            }
            String candidate = parts[index];
            if (candidate.isEmpty() || !Character.isJavaIdentifierStart(candidate.charAt(0))) {
                return Optional.empty();
            }
            return Optional.of(candidate);
        }

        /**
         * The region pair id on a region marker, or empty for any other marker.
         *
         * <p>{@code begin routes</p> yields {@code routes}. This is what pairs a region's two halves.
         */
        public Optional<String> regionId() {
            if (!SCOPE_REGION.equals(scope) || payload == null) {
                return Optional.empty();
            }
            String[] parts = payload.trim().split("\\s+");
            if (parts.length < 2 || !(REGION_BEGIN.equals(parts[0]) || REGION_END.equals(parts[0]))) {
                return Optional.empty();
            }
            return Optional.of(parts[1]);
        }

        /** Whether this is the closing half of a region pair. */
        public boolean isRegionEnd() {
            return SCOPE_REGION.equals(scope)
                    && payload != null
                    && payload.trim().startsWith(REGION_END + " ");
        }
    }

    /**
     * Recognise a marker on one line.
     *
     * <p>Accepts any scope word, so an unsupported marker comes back as a {@link Found} whose
     * {@link Found#supported()} is false rather than as "not a marker". That distinction is the whole
     * contract: a parser must be able to refuse.
     *
     * @param line the 1-based line number, for the report
     * @param text the line
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
            return recognise(1, line).map(Found::isFile).orElse(false);
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
        List<Found> found = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            recognise(i + 1, lines.get(i)).ifPresent(found::add);
        }
        return List.copyOf(found);
    }

    /**
     * Every unsupported marker in a file, in order — the list a parser must report before it edits
     * anything.
     */
    public static List<Found> unsupported(List<String> lines) {
        return scan(lines).stream().filter(found -> !found.supported()).toList();
    }

    /** Whether the given word is a scope this vocabulary defines, case-insensitively. */
    public static boolean isKnownScope(String scope) {
        return scope != null && KNOWN_SCOPES.contains(scope.toLowerCase(Locale.ROOT));
    }
}
