package hr.hrg.jcodebuddy.engine.source;

import java.util.ArrayList;
import java.util.List;

/**
 * Where things are in one source text: the positions a row needs so a consumer can <em>point</em> at the code
 * (DEC-040 D6).
 *
 * <p><strong>One javac parse, many answers.</strong> {@link JavaSyntaxCheck#inspect(String)} is the engine's
 * javac-backed position source, and it answers everything for a file in one pass — so a caller that asks it once
 * per member pays a full parse per member. This holder wraps a single {@code FileCheck} and answers the
 * lookups the class index makes, which is the difference between a pass that costs one parse and one that costs
 * fifty.</p>
 *
 * <p><strong>What a position lookup is keyed by.</strong> {@code owner} is the <em>simple</em> name of the
 * innermost enclosing type, as javac's walk records it, and a callable is told apart by its arity: two overloads
 * of one name are two members, and a lookup that ignored the arity would point at whichever came first. A
 * position that cannot be found answers {@link #UNKNOWN_LINE} rather than a guess — a wrong line in generated
 * code or in a navigation page looks like it worked, which is exactly the failure DEC-028's verification exists
 * to prevent.</p>
 */
public final class SourcePositions {

    /** The line a lookup answers with when the declaration cannot be located — never a guess. */
    public static final int UNKNOWN_LINE = -1;

    private final List<JavaSyntaxCheck.MethodPosition> methods;
    private final List<JavaSyntaxCheck.MemberPosition> members;
    private final List<JavaSyntaxCheck.MemberSpan> spans;
    private final List<JavaSyntaxCheck.TypePosition> types;
    private final String source;

    private SourcePositions(JavaSyntaxCheck.FileCheck check, String source) {
        this.methods = check.methods();
        this.members = check.members();
        this.spans = check.spans();
        this.types = check.types();
        this.source = source;
    }

    /**
     * The positions of {@code source}, parsed once.
     *
     * <p>A text javac cannot parse yields a holder that answers {@link #UNKNOWN_LINE} for everything rather than
     * throwing: the caller's own read path already reports an unreadable file (F-34's rule), and a position
     * lookup is not the place to decide a file is broken.</p>
     */
    public static SourcePositions of(String source) {
        return new SourcePositions(JavaSyntaxCheck.inspect(source == null ? "" : source),
                source == null ? "" : source);
    }

    /** The line a type's <em>name</em> sits on, or {@link #UNKNOWN_LINE}. */
    public int typeLine(String simpleName, List<String> enclosingNames) {
        for (JavaSyntaxCheck.TypePosition type : types) {
            if (type.simpleName().equals(simpleName) && type.enclosingNames().equals(enclosingNames)) {
                return type.nameLine();
            }
        }
        return UNKNOWN_LINE;
    }

    /**
     * The line a member's name sits on, or {@link #UNKNOWN_LINE}.
     *
     * @param owner          the innermost enclosing type's simple name
     * @param kind           {@code method}, {@code constructor}, {@code field}, {@code type}
     * @param name           the member's name as written
     * @param parameterCount the callable's arity, {@code 0} for a field or a nested type
     */
    public int memberLine(String owner, String kind, String name, int parameterCount) {
        if ("method".equals(kind) || "constructor".equals(kind)) {
            for (JavaSyntaxCheck.MethodPosition method : methods) {
                if (!method.declaringType().equals(owner) || method.parameterCount() != parameterCount) {
                    continue;
                }
                if ("constructor".equals(kind)) {
                    // javac spells a constructor's name `<init>` in its method positions while the *span* walk
                    // records the declared name, so the two vocabularies differ on exactly this case. Matching on
                    // the owner and the arity is what identifies a constructor: its name is its type's, and two
                    // constructors differ by arity.
                    return method.nameLine();
                }
                if (method.simpleName().equals(name)) {
                    return method.nameLine();
                }
            }
            return UNKNOWN_LINE;
        }
        // A field, an enum constant or a record component: javac models all three as one member shape, and the
        // role is what tells them apart — so a lookup matches on the name and the owner, and takes the first.
        for (JavaSyntaxCheck.MemberPosition member : members) {
            if (member.simpleName().equals(name) && member.owner().equals(owner)) {
                return member.nameLine();
            }
        }
        // A nested type is not a member position at all: javac reports it as a type, whose name line is what a
        // jump wants.
        for (JavaSyntaxCheck.TypePosition type : types) {
            if (type.simpleName().equals(name)) {
                return type.nameLine();
            }
        }
        return UNKNOWN_LINE;
    }

    /**
     * The character span of a member declaration, or {@code null} when javac's walk did not record one.
     *
     * <p>Offsets, not lines, because a span is what a caller slices: the written form of a member — generic
     * arguments included — is recovered from the file at this range rather than stored in a table (DEC-040 D2).
     * The offsets index the text <em>as read</em>, line endings included: a caller that slices must slice the
     * same bytes, and a caller that verifies must hash the LF-normalised form the row's checksum is of.</p>
     */
    public TreeQueries.SourceSpan memberSpan(String owner, String kind, String name, int parameterCount) {
        for (JavaSyntaxCheck.MemberSpan span : spans) {
            if (span.owner().equals(owner) && span.kind().equals(kind) && span.name().equals(name)
                    && span.parameterCount() == parameterCount) {
                return new TreeQueries.SourceSpan(span.startOffset(), span.endOffset());
            }
        }
        return null;
    }

    /** Every member span recorded for one owner, for a caller that wants them all in source order. */
    public List<TreeQueries.SourceSpan> spansOf(String owner) {
        List<TreeQueries.SourceSpan> found = new ArrayList<>();
        for (JavaSyntaxCheck.MemberSpan span : spans) {
            if (span.owner().equals(owner)) {
                found.add(new TreeQueries.SourceSpan(span.startOffset(), span.endOffset()));
            }
        }
        return List.copyOf(found);
    }

    /** The text this holder's positions index. */
    public String source() {
        return source;
    }

    /** The slice {@code span} names, or {@code null} when the range is outside the text. */
    public String slice(TreeQueries.SourceSpan span) {
        if (span == null || span.start() < 0 || span.end() > source.length() || span.end() < span.start()) {
            return null;
        }
        return source.substring(span.start(), span.end());
    }
}
