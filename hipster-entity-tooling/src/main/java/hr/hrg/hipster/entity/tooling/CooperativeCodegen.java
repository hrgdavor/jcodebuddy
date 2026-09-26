package hr.hrg.hipster.entity.tooling;

import org.openrewrite.java.tree.J;

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
 * declaration's character span rather than re-printing the tree. That distinction is not cosmetic: a
 * print round-trip normalises the member's own formatting — and on this migration it even changes the
 * separator inside a generic type — so a re-printed extension point is not the text the developer
 * wrote. The slice is extended backwards to include the comment attached directly above the
 * declaration, so a documented {@code TrackingStrict} survives with its documentation intact.</p>
 *
 * <p>Phase 6: the span comes from javac, because the LST has no positions at all. JavaParser answered
 * {@code node.getRange()} directly; the substitute is {@link TreeQueries#memberText}, which slices the
 * same text by the offsets javac recorded and applies the same attached-comment rule. Nothing else in
 * this class changed — the mechanism was never about which parser produced the tree.</p>
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
        J.CompilationUnit cu = SourceReader.readSourceText(text);
        if (cu == null) {
            return List.of();
        }

        J.ClassDeclaration topLevel = topLevelType(cu, topLevelName);
        if (topLevel == null) {
            return List.of();
        }
        List<Preserved> preserved = new ArrayList<>();
        // No policy question arises here: only nested types are read, so a policy that disowns fields and
        // constants is exactly right — and it keeps this listing identical to the reconciler's for the
        // one member kind both look at.
        for (Member member : directMembers(topLevel, Reconciliation.METHODS_AND_TYPES_ONLY)) {
            // Only nested types: this method's contract is the extension point a developer nests inside
            // a generated class, and nothing else (reconcileMembers owns the rest).
            if (!"type".equals(member.kind()) || generatedNames.contains(member.name())) {
                continue;
            }
            String verbatim = TreeQueries.memberText(topLevelName, "type", member.name(), 0, text);
            if (verbatim != null) {
                preserved.add(new Preserved(member.name(), verbatim));
            }
        }
        return preserved;
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

    /**
     * Whether reconciliation is skipped and the canonical text is emitted as-is.
     *
     * <p><strong>Force mode: the escape hatch from cooperation, and the reason it is needed.</strong> The
     * default contract is that a generated member belonging to an <em>earlier revision</em> is carried
     * through verbatim, which is what lets a developer edit generated code and keep the edit. The cost is
     * that a fix to the generator cannot reach a file that already contains the member it fixes: the old
     * text is compared, found to differ from the canonical one, reported as
     * {@code generated_member_diverged}, and the canonical body emitted — but a member whose <em>identity</em>
     * changed (a different arity, or a name an earlier revision spelled differently) does not match at all,
     * so it is read as the developer's own and preserved. Deleting it by hand then regenerating is the
     * documented remedy, and it works; this flag is for when the change is large, or touches many files, and
     * deleting by hand is the risky part.
     *
     * <p>It is also how a fix gets <em>tested before the generator is changed</em>, which is the better
     * order: edit the generated output, see the result compile and its tests pass, and only then teach the
     * emitter to produce it. Force mode is what makes that loop possible without hand-deleting between
     * iterations — the generator writes what it would write today, whatever is in the file now.
     *
     * <p>Process-global rather than per-file, matching {@code generationPackages} and the rest of this
     * generator's flag surface: one pass has one answer to "am I the authority on this file". It is
     * deliberately not the default, because losing a developer's edit to a generated block is silent.
     */
    private static boolean force = false;

    /**
     * Set force mode: skip reconciliation and emit the canonical text.
     *
     * @param value true to stop preserving previous members
     */
    public static void setForce(boolean value) {
        force = value;
    }

    /** Whether force mode is on. */
    public static boolean isForce() {
        return force;
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
        if (force) {
            return forceReplace(previousFile, topLevelName, canonical, policy);
        }
        String previousText = Files.readString(previousFile);
        J.CompilationUnit previousCu = SourceReader.readSourceText(previousText);
        J.CompilationUnit canonicalCu = SourceReader.readSourceText(canonical);
        if (previousCu == null || canonicalCu == null) {
            // Unreadable on either side: the safe answer is the canonical text plus nothing preserved,
            // and the caller's read guard has already reported why.
            return new Reconciled(canonical, List.of());
        }

        Map<String, String> canonicalMembers = membersByName(canonicalCu, topLevelName, canonical, policy);
        Map<String, String> previousMembers = membersByName(previousCu, topLevelName, previousText, policy);

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
     * Force mode: emit the canonical text, and <b>report every member that goes with it</b>.
     *
     * <p>The first version of this returned the canonical text and no divergences, on the reasoning that
     * nothing had been compared so nothing had diverged. That reasoning was wrong in the way that matters,
     * and it cost a real edit: a force pass over the example deleted the hand-written
     * {@code PersonSummaryBuilderTracking.TrackingStrict} — the nested type whose whole documented purpose
     * is to survive regeneration — and the pass reported success with nothing about it. "Nothing diverged"
     * described the code path, not what happened to the file, and the file is what the developer has.
     *
     * <p>So the previous file is still read, and every member present in it that the canonical text does
     * not contain is reported as {@code force_discarded_member}. That is the cost of the flag stated at the
     * moment it is paid, in the same diagnostic format as every other divergence, so a force pass over a
     * file with hand-written members is loud rather than silent. The caller has already chosen to force;
     * this does not second-guess the choice, it just refuses to make it quietly.
     */
    private static Reconciled forceReplace(Path previousFile, String topLevelName, String canonical,
                                           Reconciliation policy) throws IOException {
        List<String> discarded = new ArrayList<>();
        String previousText = Files.readString(previousFile);
        J.CompilationUnit previousCu = SourceReader.readSourceText(previousText);
        J.CompilationUnit canonicalCu = SourceReader.readSourceText(canonical);
        if (previousCu != null && canonicalCu != null) {
            Map<String, String> canonicalMembers = membersByName(canonicalCu, topLevelName, canonical, policy);
            for (Map.Entry<String, String> member : membersByName(previousCu, topLevelName, previousText, policy)
                    .entrySet()) {
                if (canonicalMembers.containsKey(member.getKey())) {
                    continue;
                }
                discarded.add("kind=force_discarded_member, location=" + topLevelName + "."
                        + groupName(member.getKey())
                        + ", cause=force mode replaced the file, and this member is not in the canonical text"
                        + ", current=the member that was in the file"
                        + ", canonical=absent"
                        + ", action=if this member was yours, copy it out before forcing again: a normal "
                        + "pass would have preserved it. Pass --force only when every member this file "
                        + "holds belongs to the generator");
            }
        }
        return new Reconciled(canonical, discarded);
    }

    /**
     * One direct member of a generated type: its identity, and what has to be looked up to slice its
     * text out of the file.
     *
     * @param key            the namespaced shape identity — {@code t:} type, {@code m:} method,
     *                       {@code f:} field, {@code c:} constructor, {@code e:} enum constant
     * @param kind           the javac-side span kind: {@code type}, {@code method}, {@code constructor},
     *                       {@code field} or {@code enum-constant}
     * @param parameterCount the arity, for a method or constructor
     */
    private record Member(String key, String kind, String name, int parameterCount) {
    }

    /** The top-level type named {@code topLevelName}, or {@code null}. */
    private static J.ClassDeclaration topLevelType(J.CompilationUnit cu, String topLevelName) {
        for (J.ClassDeclaration declaration : TreeQueries.topLevelTypes(cu)) {
            if (declaration.getSimpleName().equals(topLevelName)) {
                return declaration;
            }
        }
        return null;
    }

    /**
     * A member's shape identity and its verbatim text, for the top-level type named
     * {@code topLevelName}.
     *
     * <p>Only <em>direct</em> members are considered: a nested type is a member of the generated type,
     * and its own members belong to it, not to the file's contract. Direct members are the body's own
     * statements — never a recursive walk, which would pull in a nested type's members
     * (MIGRATION-CAVEATS.md § 1.10).</p>
     *
     * <p>A member whose span cannot be located is skipped rather than given a re-printed body: the
     * whole point of the comparison is that it is made on the developer's own text, and a printed
     * substitute would report a spurious edit for every member.</p>
     */
    private static Map<String, String> membersByName(J.CompilationUnit cu, String topLevelName, String text,
                                                     Reconciliation policy) {
        Map<String, String> members = new LinkedHashMap<>();
        J.ClassDeclaration type = topLevelType(cu, topLevelName);
        if (type == null) {
            return members;
        }
        for (Member member : directMembers(type, policy)) {
            String verbatim = TreeQueries.memberText(topLevelName, member.kind(), member.name(),
                    member.parameterCount(), text);
            if (verbatim != null) {
                // First wins, which is what a map keyed on this identity has always done: two members of
                // one name and arity are one identity here.
                members.putIfAbsent(member.key(), verbatim);
            }
        }
        return members;
    }

    /**
     * The direct members of a generated type, in declaration order, as this reconciliation sees them.
     *
     * <p>Namespaced by kind ({@code t:} type, {@code m:} method, {@code f:} field, {@code c:}
     * constructor, {@code e:} enum constant) so a field and a method that share a name cannot collapse
     * into one identity and let the field's presence hide the method's absence.</p>
     *
     * <p>Three shapes carry the ported detail. A constructor is a {@link J.MethodDeclaration} with no
     * return type — the LST has no separate constructor node — so it is recognised by
     * {@link J.MethodDeclaration#isConstructor()} and keyed by the class name, which is what JavaParser's
     * {@code ConstructorDeclaration.getNameAsString()} returned. A multi-name field declaration is one
     * {@link J.VariableDeclarations} holding N declarators where JavaParser held one
     * {@code FieldDeclaration} holding N variables; both read the <em>first</em> name as the identity, so
     * the two agree. And an enum's constants are one {@link J.EnumValueSet} statement rather than N
     * member declarations, so the set is expanded here.</p>
     */
    private static List<Member> directMembers(J.ClassDeclaration type, Reconciliation policy) {
        List<Member> members = new ArrayList<>();
        if (type.getBody() == null || type.getBody().getStatements() == null) {
            return members;
        }
        for (org.openrewrite.java.tree.Statement statement : type.getBody().getStatements()) {
            if (statement instanceof J.ClassDeclaration nested) {
                members.add(new Member("t:" + nested.getSimpleName(), "type", nested.getSimpleName(), 0));
                continue;
            }
            if (statement instanceof J.MethodDeclaration method) {
                int arity = arityOf(method);
                String name = method.getSimpleName();
                members.add(method.isConstructor()
                        ? new Member("c:" + name + "/" + arity, "constructor", name, arity)
                        : new Member("m:" + name + "/" + arity, "method", name, arity));
                continue;
            }
            if (statement instanceof J.VariableDeclarations fields) {
                if (!policy.fields() || fields.getVariables() == null || fields.getVariables().isEmpty()) {
                    continue;
                }
                String name = fields.getVariables().get(0).getSimpleName();
                members.add(new Member("f:" + name, "field", name, 0));
                continue;
            }
            if (statement instanceof J.EnumValueSet values) {
                if (!policy.constants()) {
                    continue;
                }
                for (J.EnumValue constant : values.getEnums()) {
                    String name = constant.getName().getSimpleName();
                    members.add(new Member("e:" + name, "enum-constant", name, 0));
                }
            }
        }
        return members;
    }

    /** A method's declared arity, counting the LST's `J.Empty` placeholder for "none" as zero. */
    private static int arityOf(J.MethodDeclaration method) {
        return TreeQueries.hasNoParameters(method) ? 0 : method.getParameters().size();
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
}
