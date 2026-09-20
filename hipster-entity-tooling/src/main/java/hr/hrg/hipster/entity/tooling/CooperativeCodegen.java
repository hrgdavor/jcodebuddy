package hr.hrg.hipster.entity.tooling;

import com.github.javaparser.JavaParser;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cooperative codegen: recognise a previous emission by <strong>shape</strong> and carry the user's
 * own additions through it (plan.dsflash § 8.7/3.19, DEC-020, DEC-019).
 *
 * <h3>What this class is for</h3>
 *
 * <p>Several of this generator's outputs are whole files a developer is expected to extend:
 * {@code <View>BuilderTracking} is the clearest case, because plan § 4.5/G3 fixes
 * {@code TrackingStrict} as a <em>user-authored</em> variant that the generator must never emit and
 * never delete. Before this class existed the emitters rewrote whole files, so any nested type a
 * developer had added inside a generated class disappeared on the next pass. The plan records that
 * loss explicitly (notes F-25).</p>
 *
 * <p>The same applies to every other whole-file emission — the record, the field enum, the mapper and
 * the validator included — because a nested extension point is the one thing the generator can never
 * have produced and therefore never needs to replace. User-added <em>non-type</em> members and edits to
 * a generated method body were the remaining gap (notes F-51): the generator does own those names, so
 * recognising them means comparing a previous revision against the canonical one, which
 * {@link #reconcileMembers} now does for every whole-file emitter — the same comparison the R1 ledger
 * has always done for the enum's constant list.</p>
 *
 * <p>The mechanism is the one DEC-020 prescribes. No {@code // generator:begin} pair and no registry
 * of marker comments: the generator reads the file it is about to replace, lists the types nested
 * directly inside the generated top-level type, subtracts the names it is about to emit, and treats
 * <strong>everything left over</strong> as the developer's. A member is opted back into
 * regeneration the only way DEC-020 allows — by deleting it.</p>
 *
 * <h3>Verbatim means verbatim</h3>
 *
 * <p>Preserved members are cut out of the original file as <strong>text</strong>, using the parsed
 * node's source range rather than re-printing the AST. That distinction is not cosmetic: an AST
 * round-trip silently drops the member's attached javadoc, which is exactly the part a user-written
 * extension point is made of. The slice is extended backwards to include the comment attached
 * directly above the declaration, so a documented {@code TrackingStrict} survives with its
 * documentation intact.</p>
 *
 * <p>No hint comment is emitted. DEC-020 permits one as a UX aid but makes it optional, and here it
 * would be actively harmful: the hint would sit outside every preserved member's range, so the next
 * pass would drop the hint the previous pass wrote and then re-add it — a comment that can never be
 * stable. The recognition is structural, so the aid buys nothing.</p>
 */
public final class CooperativeCodegen {

    private CooperativeCodegen() {
    }

    /**
     * A member the generator did not emit, carried over from the file's previous revision.
     *
     * @param name   the declared simple name, used only for reporting
     * @param source the member's source text, characters and comments exactly as the developer wrote
     *               them
     */
    public record Preserved(String name, String source) {
    }

    /**
     * Nested types declared directly inside {@code topLevelName} in {@code file}, minus the ones the
     * generator is about to emit.
     *
     * <p>The top-level type may be of any kind — the emitters that use this produce classes
     * ({@code <View>Builder}, {@code <View>BuilderTracking}), records ({@code <View>Record}) and enums
     * ({@code <View>_}), and a developer may nest an extension point in any of them. None of the
     * emitters emits a nested type of its own, so in practice everything found here is the
     * developer's; {@code generatedNames} exists for the case where that stops being true.</p>
     *
     * @param file           the file about to be overwritten; a missing file yields nothing
     * @param topLevelName   the simple name of the generated top-level type in that file
     * @param generatedNames nested type names the generator emits itself, so they are not duplicated
     * @return the members to re-emit, in their original declaration order
     */
    public static List<Preserved> preservedNestedTypes(Path file, String topLevelName,
                                                       Collection<String> generatedNames) throws IOException {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        String text = Files.readString(file);
        // The shared, fail-safe read (SourceReader): a partial compilation unit from an error-tolerant
        // parse must never be the basis for "nothing to preserve", because that is how a user's member
        // gets deleted. An unreadable file yields nothing preserved, which is the same as a fresh file
        // — and the pass that overwrites it is what the caller's own report covers.
        SourceReader.Read read = SourceReader.readText(text);
        if (!read.readable()) {
            return List.of();
        }
        CompilationUnit cu = read.unit();

        for (TypeDeclaration<?> type : cu.getTypes()) {
            if (!type.getNameAsString().equals(topLevelName)) {
                continue;
            }
            List<Preserved> preserved = new ArrayList<>();
            for (BodyDeclaration<?> member : type.getMembers()) {
                if (!(member instanceof TypeDeclaration<?> nested)
                        || generatedNames.contains(nested.getNameAsString())) {
                    continue;
                }
                preserved.add(new Preserved(nested.getNameAsString(), verbatim(text, nested)));
            }
            return preserved;
        }
        return List.of();
    }

    /**
     * Renders preserved members as a block to be inserted inside the generated type body.
     *
     * <p>Each member is wrapped in exactly one blank line, so the shape is deterministic and a
     * re-emission over a file that already contains the block reproduces it byte for byte.</p>
     */
    public static String render(List<Preserved> preserved) {
        if (preserved == null || preserved.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Preserved member : preserved) {
            sb.append('\n').append(member.source()).append('\n');
        }
        return sb.toString();
    }

    /** The text to write, plus the divergence entries the reconciliation produced (DEC-022 lines). */
    public record Reconciled(String source, List<String> divergences) {
    }

    /**
     * Which member kinds a reconciliation owns (follow-up plan § 1.1).
     *
     * <p>The two switches exist because the right answer differs per file, and the difference is not
     * cosmetic:</p>
     * <ul>
     *   <li><strong>{@code constants}</strong> — a field enum's constant list belongs to the R1 ledger,
     *       not to this class. The ledger decides where a constant sits, keeps a retired one in place as
     *       a tombstone, and owns the ordinals every other artifact is driven by
     *       ({@code ledgerOrderedProperties}, F-35). Preserving a "user-added" constant here would also
     *       preserve the pre-retirement constants a tombstoning pass deliberately removed — which is how
     *       this option was found: {@code TombstoneLedgerTest} failed on the ordinal contract the moment
     *       the enum's constants were reconciled. Field and method members are still reconciled, so a
     *       helper the developer added to the enum is preserved as before.</li>
     *   <li><strong>{@code fields}</strong> — a generated builder's and record's fields ARE the generator's:
     *       a user-added field has no setter, no constructor parameter and no positional slot, so carrying
     *       it through is not cooperation, it is the generator handing over a name it must also wire.
     *       The state-1/state-3 split still applies in full.</li>
     * </ul>
     */
    public record Reconciliation(boolean constants, boolean fields) {

        /** Everything reconciled: the shape used by the builders and the record. */
        public static final Reconciliation ALL = new Reconciliation(true, true);

        /** Constants and fields left to their owner — the shape used by a field enum. */
        public static final Reconciliation METHODS_AND_TYPES_ONLY = new Reconciliation(false, false);
    }

    /** As {@link #reconcileMembers(Path, String, String)} with {@link Reconciliation#ALL}. */
    public static Reconciled reconcileMembers(Path previousFile, String topLevelName, String canonical)
            throws IOException {
        return reconcileMembers(previousFile, topLevelName, canonical, Reconciliation.ALL, Set.of());
    }

    /** As the five-argument form, with nothing the generator has stopped emitting. */
    public static Reconciled reconcileMembers(Path previousFile, String topLevelName, String canonical,
                                              Reconciliation policy) throws IOException {
        return reconcileMembers(previousFile, topLevelName, canonical, policy, Set.of());
    }

    /**
     * As {@link #reconcileMembers(Path, String, String, Reconciliation)}, with member names the
     * generator deliberately no longer emits.
     *
     * <p>This is what makes the mechanism safe around R1's tombstones. A retired field's setter was
     * emitted by an earlier revision and is absent from the canonical one, so a reconciliation by shape
     * alone reads it as the developer's and carries it back — resurrecting exactly the member R1.4
     * promises is gone ("a retired field gets no setter, or a caller could write a value no column
     * accepts"). Measured: {@code TombstoneLedgerTest} failed on both the compile gate and that
     * assertion until the retired names were named here. A member the generator <em>stopped</em> emitting
     * is not a member the developer added, and only the caller knows which is which.</p>
     */
    public static Reconciled reconcileMembers(Path previousFile, String topLevelName, String canonical,
                                              Reconciliation policy, Set<String> noLongerEmitted)
            throws IOException {
        if (previousFile == null || !Files.exists(previousFile)) {
            return new Reconciled(canonical, List.of());
        }
        String previousText = Files.readString(previousFile);
        SourceReader.Read previousRead = SourceReader.readText(previousText);
        SourceReader.Read canonicalRead = SourceReader.readText(canonical);
        if (!previousRead.readable() || !canonicalRead.readable()) {
            // Unreadable on either side: the safe answer is the canonical text plus nothing preserved,
            // and the caller's read guard has already reported why.
            return new Reconciled(canonical, List.of());
        }

        Map<String, String> canonicalMembers = membersByName(canonicalRead.unit(), topLevelName, canonical, policy);
        Map<String, String> previousMembers = membersByName(previousRead.unit(), topLevelName, previousText, policy);

        List<Preserved> userOwned = new ArrayList<>();
        List<String> divergences = new ArrayList<>();
        for (Map.Entry<String, String> member : previousMembers.entrySet()) {
            if (noLongerEmitted.contains(groupName(member.getKey()))) {
                // The generator used to emit this member and deliberately stopped. Carrying it back
                // would resurrect a retired contract (see the parameter's javadoc).
                continue;
            }
            String canonicalMember = canonicalMembers.get(member.getKey());
            if (canonicalMember == null) {
                userOwned.add(new Preserved(member.getKey(), member.getValue()));
                continue;
            }
            if (isSameMemberText(groupName(member.getKey()), member.getValue(), canonicalMember)) {
                continue;
            }
            divergences.add("kind=generated_member_diverged, location=" + topLevelName + "."
                    + groupName(member.getKey())
                    + ", cause=the member the generator owns was edited in the previous revision"
                    + ", current=the edited body"
                    + ", canonical=the emitted body"
                    + ", action=the canonical body is emitted: move the edit into a method the generator "
                    + "does not own, or delete the member to regenerate it");
        }

        if (userOwned.isEmpty()) {
            return new Reconciled(canonical, divergences);
        }
        return new Reconciled(
                insertBeforeFinalBrace(canonical, render(userOwned)), divergences);
    }

    /** The report name of a member key ({@code m:build/0} becomes {@code build}). */
    private static String groupName(String key) {
        String withoutKind = key.length() > 2 && key.charAt(1) == ':' ? key.substring(2) : key;
        int slash = withoutKind.indexOf('/');
        return slash < 0 ? withoutKind : withoutKind.substring(0, slash);
    }

    /**
     * A member's shape identity and its verbatim text, for the top-level type named
     * {@code topLevelName}.
     *
     * <p>Only <em>direct</em> members are considered: a nested type is a member of the generated type,
     * and its own members belong to it, not to the file's contract.</p>
     */
    private static Map<String, String> membersByName(CompilationUnit cu, String topLevelName, String text,
                                                     Reconciliation policy) {
        Map<String, String> members = new LinkedHashMap<>();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            if (!type.getNameAsString().equals(topLevelName)) {
                continue;
            }
            for (BodyDeclaration<?> member : type.getMembers()) {
                String key = memberKey(member, policy);
                if (key != null) {
                    members.putIfAbsent(key, verbatim(text, member));
                }
            }
            break;
        }
        return members;
    }

    /**
     * The shape identity of a member, or {@code null} for a kind this reconciliation does not own.
     *
     * <p>Namespaced by kind ({@code t:} type, {@code m:} method, {@code f:} field, {@code c:}
     * constructor, {@code e:} enum constant) so a field and a method that share a name cannot collapse
     * into one identity and let the field's presence hide the method's absence.</p>
     */
    private static String memberKey(BodyDeclaration<?> member, Reconciliation policy) {
        if (member instanceof TypeDeclaration<?> nested) {
            return "t:" + nested.getNameAsString();
        }
        if (member instanceof MethodDeclaration method) {
            return "m:" + method.getNameAsString() + "/" + method.getParameters().size();
        }
        if (member instanceof FieldDeclaration field) {
            if (!policy.fields() || field.getVariables().isEmpty()) {
                return null;
            }
            return "f:" + field.getVariables().get(0).getNameAsString();
        }
        if (member instanceof ConstructorDeclaration constructor) {
            return "c:" + constructor.getNameAsString() + "/" + constructor.getParameters().size();
        }
        if (member instanceof EnumConstantDeclaration constant) {
            return policy.constants() ? "e:" + constant.getNameAsString() : null;
        }
        return null;
    }

    /**
     * Whether two members of the same name are the same member.
     *
     * <p>Line endings are normalised and surrounding whitespace trimmed: the printer uses the platform
     * separator, a file checked out on Windows carries CRLF, and a difference in either is not an edit.
     * Anything past that is treated as an edit, which is the direction that reports rather than one that
     * silently keeps a stale body.</p>
     */
    private static boolean isSameMemberText(String name, String previous, String canonical) {
        return normalizeMember(previous).equals(normalizeMember(canonical));
    }

    private static String normalizeMember(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n").strip();
    }

    /**
     * Inserts a fragment just before the final closing brace of a generated compilation unit.
     *
     * <p>The emitters build their output as text ending in the top-level type's {@code }}. Appending
     * after that brace would put the preserved member outside the class, so the fragment goes before
     * the <em>last</em> brace — which is the top-level type's own, because a generated file has no
     * trailing content after it.</p>
     */
    public static String insertBeforeFinalBrace(String source, String fragment) {
        if (fragment == null || fragment.isEmpty()) {
            return source;
        }
        int index = source.lastIndexOf('}');
        if (index < 0) {
            return source + fragment;
        }
        return source.substring(0, index) + fragment + source.substring(index);
    }

    /**
     * The exact original text of {@code node}, extended backwards over a comment attached directly
     * above it.
     *
     * <p>Falls back to the AST printer only when the node carries no source range, which happens for
     * a node built in memory rather than parsed — never for a member read out of a file.</p>
     */
    private static String verbatim(String text, Node node) {
        Range range = node.getRange().orElse(null);
        if (range == null) {
            return node.toString();
        }
        int start = offsetOf(text, range.begin.line, range.begin.column);
        int end = Math.min(offsetOf(text, range.end.line, range.end.column) + 1, text.length());

        Comment comment = node.getComment().orElse(null);
        if (comment != null && comment.getRange().isPresent()) {
            Range commentRange = comment.getRange().get();
            int commentStart = offsetOf(text, commentRange.begin.line, commentRange.begin.column);
            int commentEnd = Math.min(
                    offsetOf(text, commentRange.end.line, commentRange.end.column) + 1, text.length());
            // Only a comment that is the sole thing between itself and the declaration belongs to it.
            // A blank or whitespace run means "attached"; anything else means the comment documents
            // something earlier in the file and would be duplicated if it were carried along.
            if (commentEnd <= start && commentStart >= 0
                    && text.substring(commentEnd, start).isBlank()) {
                // Include the comment's own indentation: a javadoc's range starts at `/**`, so
                // slicing from there would re-emit the member flush against the left margin and the
                // next pass would see a diff that is only whitespace.
                int lineStart = text.lastIndexOf('\n', commentStart) + 1;
                start = text.substring(lineStart, commentStart).isBlank() ? lineStart : commentStart;
            }
        }
        return text.substring(start, end);
    }

    /** Char index of a 1-based (line, column) position, clamped to the text's length. */
    private static int offsetOf(String text, int line, int column) {
        int offset = 0;
        int currentLine = 1;
        while (currentLine < line) {
            int newline = text.indexOf('\n', offset);
            if (newline < 0) {
                return text.length();
            }
            offset = newline + 1;
            currentLine++;
        }
        return Math.min(offset + column - 1, text.length());
    }
}
