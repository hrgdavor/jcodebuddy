// {@link com.codebuddy.merge.ConflictDetectionService} Detects conflicts between base and branch versions of a file.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the conflicts present in one file, given its base, branch-1 and
 * branch-2 versions.
 *
 * Detection is deliberately conservative and syntactic: it looks for the shapes
 * this module can act on (imports, constants, overloads, package declarations,
 * signature changes) and emits one {@link Conflict} per shape it recognises.
 * Anything else becomes a single catch-all {@link ConflictType#STRUCTURAL_CHANGE}
 * conflict, which the structural resolver hands to a human.
 *
 * <p>Every emitted conflict carries the file path, so resolutions can be stored
 * per file in the branch history.
 */
public class ConflictDetectionService {

    /**
     * Detect every conflict in a file.
     *
     * @param filePath      repository-relative path, recorded on each conflict
     * @param baseCode      the merge base version
     * @param branch1Code   "ours"
     * @param branch2Code   "theirs"
     * @return conflicts in a stable order, never {@code null}
     */
    public List<Conflict> detect(String filePath, String baseCode,
                                 String branch1Code, String branch2Code) {
        return detect(filePath, baseCode, branch1Code, branch2Code, null);
    }

    /**
     * Detect every conflict in a file, carrying a type context for the resolvers
     * that need one.
     *
     * <p>The context is attached to every conflict rather than threaded into the
     * individual resolvers, so a resolver that makes a judgement about types can
     * read it from the conflict while a resolver that works from text never has to
     * know it exists.
     *
     * @param typeContext context for resolving types, or {@code null} when the
     *                    caller needs none; a resolver that requires one reports
     *                    that rather than guessing
     */
    public List<Conflict> detect(String filePath, String baseCode,
                                 String branch1Code, String branch2Code,
                                 TypeContext typeContext) {
        // A caller who supplies text is assumed to have supplied the base it has: a non-blank base is
        // a known one. The distinction a blank base hides — "the base had nothing here" versus "there
        // is no base side at all" — is only knowable from the conflict markers, so a caller that has
        // them passes it through the overload below.
        return detect(filePath, baseCode, branch1Code, branch2Code, typeContext,
            baseCode != null && !baseCode.isBlank());
    }

    /**
     * Detect every conflict in a file, told whether the base side is <em>known</em>.
     *
     * <p>This is not a detail. A blank base means two different things, and one detector has to tell
     * them apart: a git {@code diff3} hunk whose base section is present but <b>empty</b> says the
     * base had no lines in that region — both branches inserted there — while a merge-style file with
     * no base section at all says the base is <b>unknown</b>, so "both branches added it" cannot be
     * distinguished from "one branch added it and the other deleted it". Only the marker parser knows
     * which of the two it is, so it is passed in rather than guessed.
     */
    public List<Conflict> detect(String filePath, String baseCode,
                                 String branch1Code, String branch2Code,
                                 TypeContext typeContext, boolean baseKnown) {
        return detect(filePath, baseCode, branch1Code, branch2Code, typeContext, baseKnown,
            ComparisonPolicy.TRIM_WHITESPACES);
    }

    /**
     * Detect every conflict in a file, under a whitespace policy.
     *
     * <p>This is the entry point step 4.10 adds, and the policy is the caller's choice for the same reason
     * it is a parameter everywhere below: <b>a branch that only re-indented a region has changed
     * nothing</b> under {@link ComparisonPolicy#IGNORE_WHITESPACES}, so the residual it would otherwise
     * produce is not a conflict. Selecting the policy here rather than deep inside means every detector
     * that asks a content question asks it the same way, which is what stops two of them disagreeing about
     * whether a line was kept.
     *
     * <p>The default is {@link ComparisonPolicy#TRIM_WHITESPACES}, so the overload above behaves exactly
     * as it did before this parameter existed.
     */
    public List<Conflict> detect(String filePath, String baseCode,
                                 String branch1Code, String branch2Code,
                                 TypeContext typeContext, boolean baseKnown,
                                 ComparisonPolicy policy) {
        ComparisonPolicy effective = policy == null
            ? ComparisonPolicy.TRIM_WHITESPACES
            : policy;
        List<Conflict> conflicts = new ArrayList<>();

        add(conflicts, detectImportConflicts(filePath, baseCode, branch1Code, branch2Code));
        add(conflicts, detectCommentConflicts(filePath, baseCode, branch1Code, branch2Code));
        add(conflicts, detectConstantConflicts(filePath, baseCode, branch1Code, branch2Code));
        add(conflicts, detectOverloadConflicts(filePath, baseCode, branch1Code, branch2Code));
        add(conflicts, detectPackageConflicts(filePath, baseCode, branch1Code, branch2Code));
        add(conflicts, detectTypeConflicts(filePath, baseCode, branch1Code, branch2Code));
        add(conflicts, detectRenameConflicts(filePath, baseCode, branch1Code, branch2Code));
        add(conflicts, detectApiConflicts(filePath, baseCode, branch1Code, branch2Code));
        add(conflicts, detectMethodBodyConflicts(filePath, baseCode, branch1Code, branch2Code));
        add(conflicts, detectMemberAddConflicts(filePath, baseCode, branch1Code, branch2Code,
            baseKnown));

        List<Conflict> recognised = new ArrayList<>(conflicts);
        Conflict structural = detectStructuralConflict(filePath, baseCode, branch1Code, branch2Code,
            recognised, effective);
        if (structural != null) {
            conflicts.add(structural);
        }

        // The regions are attributed under the same policy, because a region is a statement about which
        // base lines were kept — the same question the residual asks, and the answer has to match.
        return attributeRegions(baseCode, conflicts, typeContext, effective);
    }

    /**
     * The residual conflict: divergence that no targeted detector recognised.
     *
     * <p>This is emitted <b>alongside</b> the recognised conflicts, not instead of
     * them. Treating it as a substitute was a real defect: a file that added an
     * import <em>and</em> deleted an unrelated method reported only "structurally
     * changed", so the mechanical import addition was silently lost and the whole
     * file became manual. Merges are routinely heterogeneous, so that was the
     * common case rather than an edge case.
     *
     * <p>A structural conflict is always {@link ConflictType#STRUCTURAL_CHANGE},
     * which declares {@link ConflictType.Handling#MANUAL} - it is never resolved
     * automatically (see {@code DESIGN_NEVER_AUTO_RESOLVED.md}).
     *
     * <p>The two triggers are a differ in the set of declared members (one branch
     * added or removed a member) and residual changed content that no detector
     * claimed.
     *
     * @return the structural conflict, or {@code null} when the branches agree or
     *         the divergence is entirely explained by the recognised conflicts
     */
    public Conflict detectStructuralConflict(String filePath, String baseCode,
                                             String branch1Code, String branch2Code,
                                             List<Conflict> recognised) {
        return detectStructuralConflict(filePath, baseCode, branch1Code, branch2Code, recognised,
            ComparisonPolicy.TRIM_WHITESPACES);
    }

    /**
     * The residual conflict, under a whitespace policy.
     *
     * <p>The policy reaches the two questions this method asks about content — whether any base line is
     * left unkept ({@code residualLines}) and whether both branches changed content at all
     * ({@code bothBranchesChangedContent}) — because under {@link ComparisonPolicy#IGNORE_WHITESPACES} a
     * branch that only respaced has changed nothing. Asking those questions under a policy the caller did
     * not choose is how a formatting-only branch comes to be reported as one side of a conflict.
     */
    public Conflict detectStructuralConflict(String filePath, String baseCode,
                                             String branch1Code, String branch2Code,
                                             List<Conflict> recognised,
                                             ComparisonPolicy policy) {
        ComparisonPolicy effective = policy == null
            ? ComparisonPolicy.TRIM_WHITESPACES
            : policy;
        if (!differs(baseCode, branch1Code) || !differs(baseCode, branch2Code)) {
            // Both sides must have changed something for there to be a conflict.
            return null;
        }

        Set<String> baseMembers = memberSignatures(baseCode);
        Set<String> members1 = memberSignatures(branch1Code);
        Set<String> members2 = memberSignatures(branch2Code);

        // A member added or removed on either side is structural. Checking only
        // additions would miss a deletion, which is the more dangerous half: a
        // branch that removes a method while the other keeps calling it produces
        // code that compiles nowhere.
        boolean membersDiverged = !difference(members1, baseMembers).isEmpty()
            || !difference(members2, baseMembers).isEmpty()
            || !difference(baseMembers, members1).isEmpty()
            || !difference(baseMembers, members2).isEmpty();

        // ...unless a recognised conflict already describes exactly this divergence.
        // Two branches each adding a distinct member is that case: the additions are
        // recognised (MEMBER_ADD), nothing was removed, and a residual saying "the
        // member set differs" would only restate what the recognised conflict already
        // knows - while vetoing it, because a residual is always a human's. Measured
        // before this was added: a block whose two additions coexist came out
        // LEFT_MANUAL with the residual as its only claim, so no stronger answer
        // existed to be believed.
        boolean additionsExplained = recognised.stream()
            .anyMatch(conflict -> conflict.getType() == ConflictType.MEMBER_ADD)
            && difference(baseMembers, members1).isEmpty()
            && difference(baseMembers, members2).isEmpty();

        if (membersDiverged && !additionsExplained) {
            return new Conflict(ConflictType.STRUCTURAL_CHANGE, filePath,
                "The set of declared members differs between the branches",
                baseCode, branch1Code, branch2Code);
        }

        Set<String> residual = residualLines(baseCode, branch1Code, branch2Code, effective);
        if (residual.isEmpty()) {
            // A recognised conflict only accounts for the divergence if it can
            // actually be resolved. A REVIEW conflict is still a decision a human
            // has to make, so it must not be allowed to swallow the residual and
            // leave the file looking resolved.
            boolean accountedFor = recognised.stream()
                .anyMatch(conflict -> conflict.getType().isAutoResolvable());
            if (accountedFor) {
                return null;
            }

            // Content does not vanish just because the lines agree on both sides:
            // two branches can each add a statement to the same body, changing it
            // twice in ways neither the body detector nor any other claims.
            if (bothBranchesChangedContent(baseCode, branch1Code, branch2Code, effective)) {
                return new Conflict(ConflictType.STRUCTURAL_CHANGE, filePath,
                    "Both branches changed the same member in incompatible ways",
                    baseCode, branch1Code, branch2Code);
            }
            return null;
        }

        return new Conflict(ConflictType.STRUCTURAL_CHANGE, filePath,
            "Both branches changed the same region in incompatible ways",
            baseCode, branch1Code, branch2Code);
    }

    /**
     * True when both branches changed the file and neither change is a subset of
     * the other's - that is, when the two edits compete rather than compose.
     *
     * <p>Compared on content lines rather than raw text so that formatting does not
     * count as a change. A branch that only reformatted is not participating in a
     * conflict.
     */
    private static boolean bothBranchesChangedContent(String baseCode, String branch1Code,
                                                      String branch2Code) {
        return bothBranchesChangedContent(baseCode, branch1Code, branch2Code,
            ComparisonPolicy.TRIM_WHITESPACES);
    }

    private static boolean bothBranchesChangedContent(String baseCode, String branch1Code,
                                                      String branch2Code, ComparisonPolicy policy) {
        ComparisonPolicy effective = policy == null
            ? ComparisonPolicy.TRIM_WHITESPACES
            : policy;
        Set<String> base = normalisedLines(baseCode, effective);
        Set<String> changed1 = difference(normalisedLines(branch1Code, effective), base);
        Set<String> changed2 = difference(normalisedLines(branch2Code, effective), base);

        if (changed1.isEmpty() || changed2.isEmpty()) {
            return false;
        }
        // If one side's change is contained in the other's, the larger side is an
        // extension of the smaller and this is not a competing edit.
        return !changed1.containsAll(changed2) && !changed2.containsAll(changed1);
    }

    /**
     * The declarations present in a file, as {@code name(params)} signatures.
     *
     * <p>Comparing these sets is what separates "a member was added or removed" -
     * which is structural - from "a body was edited", and it also stops a newly
     * added method from being mistaken for a body change. Delegate to
     * {@link DeclarationScanner} so that detection and resolution agree, and so
     * that a comment or a brace on the next line cannot hide a declaration.
     */
    static Set<String> memberSignatures(String code) {
        return DeclarationScanner.memberSignatures(code);
    }

    /**
     * Lines of the base that neither branch preserved unchanged, i.e. the parts
     * of the file that were actually rewritten. Used only to decide whether a
     * residual conflict exists.
     */
    private static Set<String> residualLines(String baseCode, String branch1Code, String branch2Code) {
        return residualLines(baseCode, branch1Code, branch2Code, ComparisonPolicy.TRIM_WHITESPACES);
    }

    /**
     * The lines of the base that neither branch preserved, compared under a policy.
     *
     * <p>The policy reaches every comparison this method makes — the base's own normalisation and the two
     * branch lookups — because a residual computed under one policy and looked up under another would
     * report a line as lost when the other policy says it was kept. Under
     * {@link ComparisonPolicy#IGNORE_WHITESPACES} a base line that both branches merely respaced is not
     * residual, which is the point of step 4.10: whitespace churn stops looking like a rewrite.
     */
    private static Set<String> residualLines(String baseCode, String branch1Code, String branch2Code,
                                             ComparisonPolicy policy) {
        ComparisonPolicy effective = policy == null
            ? ComparisonPolicy.TRIM_WHITESPACES
            : policy;
        Set<String> branch1Lines = normalisedLines(branch1Code, effective);
        Set<String> branch2Lines = normalisedLines(branch2Code, effective);

        Set<String> residual = new LinkedHashSet<>();
        for (String line : normalisedLines(baseCode, effective)) {
            if (isDeclarative(line) || isMemberHeader(line)) {
                // Declarations and member headers are the business of the
                // targeted detectors; a member whose body changed is reported by
                // the body or structural detector, not as residual content.
                continue;
            }
            if (!branch1Lines.contains(line) && !branch2Lines.contains(line)) {
                residual.add(line);
            }
        }
        return residual;
    }

    private static boolean isDeclarative(String line) {
        return line.startsWith("import ") || line.startsWith("package ");
    }

    /**
     * True for a line that opens or closes a member or type, which the targeted
     * detectors model rather than treating as loose content.
     */
    private static boolean isMemberHeader(String line) {
        return line.endsWith("{") || line.equals("}") || line.equals("};");
    }

    /**
     * The lines of a text, normalised under a policy and stripped of blanks.
     *
     * <p><b>Why this takes the policy rather than calling {@code trim()}.</b> This primitive used to be
     * {@code line.trim()} unconditionally, which is a third thing that is neither of the two policies the
     * text tier distinguishes: trimming ignores a line's <em>edges</em>, while the set-membership shape
     * here also matches a line anywhere in the file, and neither of those is "ignore whitespace inside the
     * line". Threading the policy makes the operation the caller's choice, which is step 4.10.
     *
     * <p>The caller that passes nothing gets {@link ComparisonPolicy#TRIM_WHITESPACES}, because that is
     * exactly what {@code trim()} did — so this is a refactor with no behaviour change until a caller asks
     * for one, which is what makes it safe to land on its own.
     */
    static Set<String> normalisedLines(String code, ComparisonPolicy policy) {
        ComparisonPolicy effective = policy == null
            ? ComparisonPolicy.TRIM_WHITESPACES
            : policy;
        Set<String> lines = new LinkedHashSet<>();
        if (code == null) {
            return lines;
        }
        for (String line : code.split("\n")) {
            String normalised = effective.normaliseLine(line);
            if (!normalised.isEmpty()) {
                lines.add(normalised);
            }
        }
        return lines;
    }

    /** The lines of a text, normalised by trimming their edges. */
    static Set<String> normalisedLines(String code) {
        return normalisedLines(code, ComparisonPolicy.TRIM_WHITESPACES);
    }

    /**
     * Attach the base-file region each conflict covers, so a report can tell
     * which automatic resolutions are independent of the conflicts that still
     * need a human, and the type context a resolver may need.
     */
    private List<Conflict> attributeRegions(String baseCode, List<Conflict> conflicts,
                                            TypeContext typeContext, ComparisonPolicy policy) {
        List<Conflict> attributed = new ArrayList<>(conflicts.size());
        for (Conflict conflict : conflicts) {
            Conflict withRegion = conflict.withRegion(regionFor(baseCode, conflict, policy));
            attributed.add(typeContext == null ? withRegion : withRegion.withTypeContext(typeContext));
        }
        return attributed;
    }

    /**
     * The region of a conflict, located via the part of the base file that the
     * conflict is about.
     *
     * <p>When the anchor cannot be found the region is {@link Region#unknown()},
     * which is the conservative answer: an unattributable conflict is never
     * treated as independent of anything.
     */
    private Region regionFor(String baseCode, Conflict conflict, ComparisonPolicy policy) {
        if (conflict.getType() == ConflictType.STRUCTURAL_CHANGE
            || conflict.getType() == ConflictType.METHOD_BODY_CHANGE
            || conflict.getType() == ConflictType.API_INCOMPATIBILITY) {
            // These types have no single anchor declaration: what changed is the
            // content of a member, so the region is the span of base lines the
            // branches did not both preserve.
            return structuralRegion(baseCode, conflict.getBranch1Code(), conflict.getBranch2Code(),
                policy);
        }
        String anchor = anchorFor(baseCode, conflict);
        return anchor == null ? Region.unknown() : Region.of(baseCode, anchor);
    }

    /**
     * The piece of base code a conflict is about, chosen per type so the region
     * points at the relevant declaration rather than at the whole hunk.
     *
     * <p>An addition has no counterpart in the base, so each case falls back to
     * the part of the base the addition attaches to - the import block, the
     * constant block - because that is where a merge tool would place the new
     * line. When no anchor exists at all the region is unknown, which is the
     * conservative answer.
     */
    private String anchorFor(String baseCode, Conflict conflict) {
        String base = baseCode == null ? "" : baseCode;
        Set<String> baseImports = ImportConflictResolver.extractImports(base);
        Set<String> baseComments = new LinkedHashSet<>(
            CommentAddConflictResolver.commentsIn(base));
        Set<String> baseConstants = ConstantAddConflictResolver.constantsIn(base).keySet();

        switch (conflict.getType()) {
            case IMPORT_ADD -> {
                Set<String> added = difference(
                    ImportConflictResolver.extractImports(conflict.getBranch1Code()), baseImports);
                added.addAll(difference(
                    ImportConflictResolver.extractImports(conflict.getBranch2Code()), baseImports));
                if (!added.isEmpty()) {
                    String first = added.iterator().next();
                    if (base.contains(first)) {
                        return "import " + first + ";";
                    }
                }
                return lastOrFirst(baseImports, value -> "import " + value + ";");
            }
            case COMMENT_ADD -> {
                Set<String> added = difference(new LinkedHashSet<>(
                    CommentAddConflictResolver.commentsIn(conflict.getBranch1Code())), baseComments);
                added.addAll(difference(new LinkedHashSet<>(
                    CommentAddConflictResolver.commentsIn(conflict.getBranch2Code())), baseComments));
                for (String candidate : added) {
                    if (base.contains(candidate)) {
                        return candidate;
                    }
                }
                return firstOrNull(baseComments);
            }
            case CONSTANT_ADD -> {
                Set<String> added = difference(
                    ConstantAddConflictResolver.constantsIn(conflict.getBranch1Code()).keySet(),
                    baseConstants);
                added.addAll(difference(
                    ConstantAddConflictResolver.constantsIn(conflict.getBranch2Code()).keySet(),
                    baseConstants));
                for (String candidate : added) {
                    if (base.contains(candidate)) {
                        return candidate;
                    }
                }
                return firstOrNull(baseConstants);
            }
            case OVERLOAD_ADD -> {
                return ResolvedTypeReader.methodNamesOnly(conflict.getBranch1Code())
                    .stream()
                    .findFirst()
                    .map(name -> name + "(")
                    .orElse(null);
            }
            case PACKAGE_CHANGE -> {
                return PackageChangeConflictResolver.firstPackage(base)
                    .map(name -> "package " + name + ";")
                    .orElse(null);
            }
            case VARIABLE_RENAME -> {
                return RenameConflictResolver.firstDeclaredName(base).orElse(null);
            }
            case TYPE_CHANGE -> {
                TypeChangeConflictResolver.TypeAndName parsed =
                    TypeChangeConflictResolver.parse(base);
                return parsed == null ? null : parsed.name();
            }
            default -> {
                // METHOD_BODY_CHANGE, API_INCOMPATIBILITY and STRUCTURAL_CHANGE are
                // resolved to a span by regionFor, not to a single anchor.
                return null;
            }
        }
    }

    private static String firstOrNull(Set<String> values) {
        return values.isEmpty() ? null : values.iterator().next();
    }

    /**
     * The last value when available, otherwise the first. An insertion into an
     * ordered block happens at the end, so the last existing member is the
     * closest thing the base has to where the new line will go.
     */
    private static String lastOrFirst(Set<String> values,
                                      java.util.function.Function<String, String> render) {
        if (values.isEmpty()) {
            return null;
        }
        String chosen = null;
        for (String value : values) {
            chosen = value;
        }
        return render.apply(chosen);
    }

    /**
     * The region a structural conflict covers: the span of base lines that the
     * two branches did not both keep.
     *
     * <p>Deliberately conservative. When no such span can be established the whole
     * file is claimed, which means nothing else may be treated as independent of
     * it - correct, if unhelpful, because the tool does not know where the
     * structural change is.
     */
    /**
     * The region a structural conflict covers: the span of base lines that the
     * two branches did not both keep.
     *
     * <p>One honest limitation, deliberately left in rather than papered over: a
     * conflict caused purely by <em>insertion</em> - both branches appended a new
     * member and dropped no base line - has no base span to measure, so the region
     * is {@link Region#unknown()}. That means such a conflict blocks independent
     * application for the whole file. Claiming a region there would require
     * modelling the class body, which this layer does not do; guessing the
     * insertion point by comparing the branches would understate the blast radius
     * of the added code. Unknown is the safe answer.
     */
    private Region structuralRegion(String baseCode, String branch1Code, String branch2Code,
                                     ComparisonPolicy policy) {
        String[] baseLines = baseCode == null || baseCode.isBlank()
            ? new String[0]
            : baseCode.split("\n", -1);
        return spanNotKeptByBoth(baseLines, branch1Code, branch2Code, policy);
    }

    /**
     * The 1-based span of the first to the last entry that both branches did not
     * keep unchanged, or {@link Region#unknown()} when every entry was preserved.
     *
     * <p>Extracted from {@link #structuralRegion} so the arithmetic is directly
     * testable: this computation is what decides whether a file's automatic part
     * can be applied independently, so it is worth being able to assert on it
     * without going through detection.
     */
    static Region spanNotKeptByBoth(String[] baseLines, String branch1Code, String branch2Code) {
        return spanNotKeptByBoth(baseLines, branch1Code, branch2Code,
            ComparisonPolicy.TRIM_WHITESPACES);
    }

    /**
     * The 1-based span of the first to the last entry that both branches did not keep, under a policy.
     *
     * <p>The policy decides what "kept" means, and it therefore decides the <b>size of this span</b> —
     * which is not a cosmetic difference, because the span is what says whether a file's automatic part
     * can be applied independently of the parts that need a human. Under
     * {@link ComparisonPolicy#IGNORE_WHITESPACES} a region whose only change was respacing is kept by
     * both branches, so the span narrows and more of the file becomes independently applicable; under
     * {@code TRIM} it narrows less. Reporting the span under a policy the caller did not choose would
     * overstate the blast radius of a conflict, which is the safe direction to be wrong in but still
     * wrong.
     */
    static Region spanNotKeptByBoth(String[] baseLines, String branch1Code, String branch2Code,
                                    ComparisonPolicy policy) {
        int first = -1;
        int last = -1;
        for (int index = 0; index < baseLines.length; index++) {
            if (keptByBothBranches(baseLines[index], branch1Code, branch2Code, policy)) {
                continue;
            }
            if (first < 0) {
                first = index + 1;
            }
            last = index + 1;
        }
        return first < 0 ? Region.unknown() : Region.spanning(first, last);
    }

    /**
     * True when the line survives unchanged in both branches, compared under a policy.
     *
     * <p>A blank line counts as kept, because whitespace churn is not a structural change — that much was
     * already true and stays true under every policy.
     *
     * <p><b>What the policy changes.</b> Under {@link ComparisonPolicy#TRIM_WHITESPACES} a re-indented
     * line still counts as kept, which is the behaviour this method had when it called {@code trim()}
     * unconditionally and is therefore the default. Under
     * {@link ComparisonPolicy#IGNORE_WHITESPACES} a line whose interior whitespace was respaced also
     * counts as kept, which matters for a region question like this one: the caller is asking "did both
     * branches keep this line", and under that policy a respaced line *is* the line. The two differ
     * exactly where the policies do.
     */
    static boolean keptByBothBranches(String line, String branch1Code, String branch2Code,
                                      ComparisonPolicy policy) {
        ComparisonPolicy effective = policy == null
            ? ComparisonPolicy.TRIM_WHITESPACES
            : policy;
        String normalised = line == null ? "" : effective.normaliseLine(line);
        if (normalised.isEmpty()) {
            return true;
        }
        return containsLine(branch1Code, normalised, effective)
            && containsLine(branch2Code, normalised, effective);
    }

    /** True when the line survives unchanged in both branches, compared by trimming its edges. */
    static boolean keptByBothBranches(String line, String branch1Code, String branch2Code) {
        return keptByBothBranches(line, branch1Code, branch2Code,
            ComparisonPolicy.TRIM_WHITESPACES);
    }

    private static boolean containsLine(String code, String normalisedLine, ComparisonPolicy policy) {
        if (code == null) {
            return false;
        }
        for (String line : code.split("\n")) {
            if (policy.normaliseLine(line).equals(normalisedLine)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convenience overload for callers that only have the three versions and no
     * meaningful path.
     */
    public List<Conflict> detect(String baseCode, String branch1Code, String branch2Code) {
        return detect("<unknown>", baseCode, branch1Code, branch2Code);
    }

    /**
     * Imports added on both branches.
     *
     * <p>Note that the two sides need not add the <em>same</em> import. Two
     * branches each adding a different import next to the same neighbourhood is
     * precisely the false conflict this module exists to remove, so any pair of
     * additions is reported and handed to the resolver, which keeps the union.
     */
    public List<Conflict> detectImportConflicts(String filePath, String baseCode,
                                                String branch1Code, String branch2Code) {
        List<Conflict> conflicts = new ArrayList<>();

        Set<String> baseImports = ImportConflictResolver.extractImports(baseCode);
        Set<String> added1 = difference(ImportConflictResolver.extractImports(branch1Code), baseImports);
        Set<String> added2 = difference(ImportConflictResolver.extractImports(branch2Code), baseImports);

        if (isAdditive(added1, added2)) {
            Set<String> overlap = intersection(added1, added2);
            conflicts.add(new Conflict(ConflictType.IMPORT_ADD, filePath,
                "Both branches added imports: " + added1 + " and " + added2
                    + (overlap.isEmpty() ? "" : " (shared: " + overlap + ")"),
                baseCode, branch1Code, branch2Code));
        }
        return conflicts;
    }

    /**
     * Comments added on both branches.
     *
     * <p>Any pair of additions is a false conflict: documentation from two
     * branches does not collide.
     */
    public List<Conflict> detectCommentConflicts(String filePath, String baseCode,
                                                 String branch1Code, String branch2Code) {
        List<Conflict> conflicts = new ArrayList<>();

        Set<String> baseComments = new LinkedHashSet<>(CommentAddConflictResolver.commentsIn(baseCode));
        Set<String> added1 = difference(
            new LinkedHashSet<>(CommentAddConflictResolver.commentsIn(branch1Code)), baseComments);
        Set<String> added2 = difference(
            new LinkedHashSet<>(CommentAddConflictResolver.commentsIn(branch2Code)), baseComments);

        if (isAdditive(added1, added2)) {
            conflicts.add(new Conflict(ConflictType.COMMENT_ADD, filePath,
                "Both branches added documentation: " + added1.size() + " and "
                    + added2.size() + " comment(s)",
                baseCode, branch1Code, branch2Code));
        }
        return conflicts;
    }

    /**
     * Constants added on both branches. Differing names are still one conflict,
     * because the same region of the file grew on both sides.
     */
    public List<Conflict> detectConstantConflicts(String filePath, String baseCode,
                                                  String branch1Code, String branch2Code) {
        List<Conflict> conflicts = new ArrayList<>();

        Set<String> baseConstants = ConstantAddConflictResolver.constantsIn(baseCode).keySet();
        Set<String> added1 = difference(
            ConstantAddConflictResolver.constantsIn(branch1Code).keySet(), baseConstants);
        Set<String> added2 = difference(
            ConstantAddConflictResolver.constantsIn(branch2Code).keySet(), baseConstants);

        if (isAdditive(added1, added2)) {
            conflicts.add(new Conflict(ConflictType.CONSTANT_ADD, filePath,
                "Both branches added constants: " + added1 + " and " + added2,
                baseCode, branch1Code, branch2Code));
        }
        return conflicts;
    }

    /**
     * Both branches added an overload of the same method.
     *
     * <p>Newness is judged per {@code name(parameters)} pair, not per name: adding
     * an overload deliberately reuses the existing method name, so a name-only
     * comparison would miss the very case this conflict type exists for - two
     * branches each adding a distinct overload of {@code process}.
     */
    public List<Conflict> detectOverloadConflicts(String filePath, String baseCode,
                                                  String branch1Code, String branch2Code) {
        List<Conflict> conflicts = new ArrayList<>();

        Set<String> baseMembers = memberSignatures(baseCode);
        Set<String> added1 = difference(memberSignatures(branch1Code), baseMembers);
        Set<String> added2 = difference(memberSignatures(branch2Code), baseMembers);

        Set<String> addedNames1 = namesOf(added1);
        Set<String> addedNames2 = namesOf(added2);
        Set<String> sharedNames = intersection(addedNames1, addedNames2);

        if (isAdditive(addedNames1, addedNames2) && !sharedNames.isEmpty()) {
            conflicts.add(new Conflict(ConflictType.OVERLOAD_ADD, filePath,
                "Both branches added an overload of '" + sharedNames.iterator().next()
                    + "' (" + added1 + " and " + added2 + ")",
                baseCode, branch1Code, branch2Code));
        }
        return conflicts;
    }

    /**
     * The method names present in a set of {@code name(parameters)} signatures.
     */
    private static Set<String> namesOf(Set<String> signatures) {
        Set<String> names = new LinkedHashSet<>();
        for (String signature : signatures) {
            int parenthesis = signature.indexOf('(');
            names.add(parenthesis < 0 ? signature : signature.substring(0, parenthesis));
        }
        return names;
    }

    /**
     * The class moved to different packages on each branch.
     */
    public List<Conflict> detectPackageConflicts(String filePath, String baseCode,
                                                 String branch1Code, String branch2Code) {
        List<Conflict> conflicts = new ArrayList<>();

        String basePackage = PackageChangeConflictResolver.firstPackage(baseCode).orElse(null);
        String package1 = PackageChangeConflictResolver.firstPackage(branch1Code).orElse(null);
        String package2 = PackageChangeConflictResolver.firstPackage(branch2Code).orElse(null);

        boolean moved1 = package1 != null && !package1.equals(basePackage);
        boolean moved2 = package2 != null && !package2.equals(basePackage);

        if (moved1 || moved2) {
            if (package1 != null && package2 != null && !package1.equals(package2)) {
                conflicts.add(new Conflict(ConflictType.PACKAGE_CHANGE, filePath,
                    "Package differs between branches: '" + package1 + "' vs '" + package2 + "'",
                    baseCode, branch1Code, branch2Code));
            }
        }
        return conflicts;
    }

    /**
     * The declared type of the same declaration differs.
     */
    public List<Conflict> detectTypeConflicts(String filePath, String baseCode,
                                              String branch1Code, String branch2Code) {
        List<Conflict> conflicts = new ArrayList<>();

        String type1 = declaredType(branch1Code);
        String type2 = declaredType(branch2Code);

        if (type1 != null && type2 != null && type1.equals(type2)) {
            String baseType = declaredType(baseCode);
            if (baseType != null && !baseType.equals(type1)) {
                conflicts.add(new Conflict(ConflictType.TYPE_CHANGE, filePath,
                    "Both branches changed the declared type to '" + type1 + "'"
                        + " (base was '" + baseType + "')",
                    baseCode, branch1Code, branch2Code));
            }
            return conflicts;
        }

        if (type1 != null && type2 != null) {
            conflicts.add(new Conflict(ConflictType.TYPE_CHANGE, filePath,
                "Declared types differ: '" + type1 + "' vs '" + type2 + "'",
                baseCode, branch1Code, branch2Code));
        }
        return conflicts;
    }

    /**
     * A declaration renamed differently on each branch.
     */
    public List<Conflict> detectRenameConflicts(String filePath, String baseCode,
                                                String branch1Code, String branch2Code) {
        List<Conflict> conflicts = new ArrayList<>();

        String baseName = RenameConflictResolver.firstDeclaredName(baseCode).orElse(null);
        String name1 = RenameConflictResolver.firstDeclaredName(branch1Code).orElse(null);
        String name2 = RenameConflictResolver.firstDeclaredName(branch2Code).orElse(null);

        if (baseName != null && name1 != null && name2 != null
            && !name1.equals(name2)
            && (!name1.equals(baseName) || !name2.equals(baseName))) {
            conflicts.add(new Conflict(ConflictType.VARIABLE_RENAME, filePath,
                "Declaration renamed differently: '" + name1 + "' vs '" + name2 + "'"
                    + " (base was '" + baseName + "')",
                baseCode, branch1Code, branch2Code));
        }
        return conflicts;
    }

    /**
     * A visible declaration whose contract differs between branches.
     */
    public List<Conflict> detectApiConflicts(String filePath, String baseCode,
                                             String branch1Code, String branch2Code) {
        List<Conflict> conflicts = new ArrayList<>();

        boolean visible1 = hasVisibleDeclaration(branch1Code);
        boolean visible2 = hasVisibleDeclaration(branch2Code);

        if (visible1 && visible2 && !signatureOf(branch1Code).equals(signatureOf(branch2Code))) {
            conflicts.add(new Conflict(ConflictType.API_INCOMPATIBILITY, filePath,
                "Public declaration differs between branches",
                baseCode, branch1Code, branch2Code));
        }
        return conflicts;
    }

    /**
     * Both branches edited the body of a method, at least one of them purely
     * additively.
     *
     * <p>Deliberately conservative, and narrowly scoped:
     *
     * <ul>
     *   <li>Every statement of each branch must either come from the base or be an
     *       addition to it. A removal or rewrite makes the change structural, not
     *       a body edit.</li>
     *   <li>At least one side must be <em>purely</em> additive. When both sides
     *       add different statements, neither is an extension of the other.</li>
     *   <li>The two sides must not have changed the <em>same</em> statement
     *       differently. That is a genuine disagreement about one line and belongs
     *       to the structural (manual) path.</li>
     * </ul>
     */
    public List<Conflict> detectMethodBodyConflicts(String filePath, String baseCode,
                                                    String branch1Code, String branch2Code) {
        List<Conflict> conflicts = new ArrayList<>();

        Set<String> baseStatements = new LinkedHashSet<>(
            MethodBodyChangeConflictResolver.statementsIn(baseCode));
        Set<String> statements1 = new LinkedHashSet<>(
            MethodBodyChangeConflictResolver.statementsIn(branch1Code));
        Set<String> statements2 = new LinkedHashSet<>(
            MethodBodyChangeConflictResolver.statementsIn(branch2Code));

        if (baseStatements.isEmpty()) {
            return conflicts;
        }

        Set<String> changed1 = difference(statements1, baseStatements);
        Set<String> changed2 = difference(statements2, baseStatements);

        boolean bothEdited = isAdditive(changed1, changed2);
        boolean bothPreserveBase = statements1.containsAll(baseStatements)
            && statements2.containsAll(baseStatements);
        boolean oneSideIsPureExtension = changed1.isEmpty() != changed2.isEmpty();

        // Statements both sides changed, in different ways: a direct clash.
        Set<String> contested = intersection(changed1, changed2);

        if (bothEdited && bothPreserveBase && oneSideIsPureExtension && contested.isEmpty()) {
            conflicts.add(new Conflict(ConflictType.METHOD_BODY_CHANGE, filePath,
                "One branch extended a method body that the other left equivalent",
                baseCode, branch1Code, branch2Code));
        }
        return conflicts;
    }

    /**
     * Both branches added a <em>distinct</em> member in the same place.
     *
     * <p>Two branches appending a method at the same point in a class produce adjacent insertions,
     * which is a conflict to a line-based tool and nothing of the sort to a reader: the additions do
     * not interact. Comparing the declared members shows that, where comparing the changed lines
     * cannot — which is the whole reason this detector exists, and why the resolver it feeds declares
     * {@link AnalysisLevel#STRUCTURE}.
     *
     * <p>Three conditions, each of which is a way the additions could interact:
     *
     * <ul>
     *   <li><b>a base side is required.</b> Without one, "both branches added it" cannot be told from
     *       "one branch added it and the other deliberately deleted it", and the union would resurrect
     *       a member somebody removed. This is the same distinction the import resolver draws against
     *       the base, for the same reason;</li>
     *   <li><b>nothing may have been removed on either side.</b> A removal is structural — a branch
     *       that drops a method while another keeps calling it produces code that compiles nowhere —
     *       and belongs to a human;</li>
     *   <li><b>the two additions must not share a signature.</b> Identical signatures collide, so only
     *       one member can exist; that is {@link OverloadAddConflictResolver}'s question, decided from
     *       resolved parameter types, and answering it here from text would be guessing.</li>
     * </ul>
     */
    public List<Conflict> detectMemberAddConflicts(String filePath, String baseCode,
                                                   String branch1Code, String branch2Code) {
        return detectMemberAddConflicts(filePath, baseCode, branch1Code, branch2Code,
            baseCode != null && !baseCode.isBlank());
    }

    /**
     * Both branches added a <em>distinct</em> member in the same place, told whether the base side is
     * known.
     *
     * <p><b>Why the base side is required, and what "empty" means.</b> Two branches appending a method
     * or a field at the same point produce adjacent insertions, which a line-based comparison reads as
     * "both sides replaced this region" — true and useless — while comparing the declared members
     * shows the additions do not interact. The danger is telling an addition from a deletion: keeping
     * both sides resurrects a member one branch deliberately removed, the same outcome
     * {@link ImportConflictResolver} refuses to produce. So the base must be there to compare against.
     * A base that is <em>present but empty</em> is exactly that evidence — the base had no lines in
     * this region, so both sides inserted — while a file with no base section at all is an
     * <em>unknown</em> base and is declined. {@code diff3} and {@code zdiff3} hunks carry the first;
     * git's default merge style carries the second.
     *
     * <p><b>What counts as a member.</b> Methods (by name and parameter list) and fields (by name),
     * and only at the declaration level: a fragment that never declares a method or a type gives no
     * way to tell a field from a local variable, so it is declined rather than guessed at.
     *
     * <p><b>Three conditions, each a way the additions could interact:</b> the base must be known; no
     * member may have been removed on either side; and the two additions must not share a signature.
     * Identical signatures collide — only one member can exist — which is
     * {@link OverloadAddConflictResolver}'s question, decided from resolved parameter types, and
     * answering it here from text would be guessing.
     */
    public List<Conflict> detectMemberAddConflicts(String filePath, String baseCode,
                                                   String branch1Code, String branch2Code,
                                                   boolean baseKnown) {
        List<Conflict> conflicts = new ArrayList<>();

        if (!baseKnown) {
            return conflicts;
        }

        boolean baseBlank = baseCode == null || baseCode.isBlank();
        Set<String> baseMembers = DeclarationScanner.membersOf(baseCode, baseBlank);
        Set<String> members1 = DeclarationScanner.membersOf(branch1Code, baseBlank);
        Set<String> members2 = DeclarationScanner.membersOf(branch2Code, baseBlank);

        Set<String> added1 = difference(members1, baseMembers);
        Set<String> added2 = difference(members2, baseMembers);

        if (added1.isEmpty() || added2.isEmpty()) {
            return conflicts;
        }
        if (!difference(baseMembers, members1).isEmpty()
            || !difference(baseMembers, members2).isEmpty()) {
            return conflicts;
        }
        // Names, not signatures: without a type context two parameter lists cannot be compared as
        // resolved types, and two methods whose names match may or may not be the same member. The
        // resolver re-checks this with the context it is handed, and declines when it cannot prove it.
        if (!intersection(namesOf(added1), namesOf(added2)).isEmpty()) {
            return conflicts;
        }

        conflicts.add(new Conflict(ConflictType.MEMBER_ADD, filePath,
            "Both branches added distinct members: " + added1 + " and " + added2,
            baseCode, branch1Code, branch2Code));
        return conflicts;
    }

    private static void add(List<Conflict> target, List<Conflict> detected) {
        target.addAll(detected);
    }

    /**
     * True when both branches added something. The additions need not be equal -
     * two different additions to the same region is exactly the case worth
     * reporting and then resolving additively.
     */
    private static boolean isAdditive(Set<String> addedByBranch1, Set<String> addedByBranch2) {
        return !addedByBranch1.isEmpty() && !addedByBranch2.isEmpty();
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> shared = new LinkedHashSet<>(left);
        shared.retainAll(right);
        return shared;
    }
    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static boolean differs(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        return !left.equals(right);
    }

    private static String declaredType(String code) {
        TypeChangeConflictResolver.TypeAndName parsed = TypeChangeConflictResolver.parse(code);
        return parsed == null ? null : parsed.type();
    }

    private static boolean hasVisibleDeclaration(String code) {
        for (String line : safeLines(code)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("public ") || trimmed.startsWith("protected ")) {
                return true;
            }
        }
        return false;
    }

    private static String signatureOf(String code) {
        for (String line : safeLines(code)) {
            String trimmed = line.trim();
            if (trimmed.contains("(") && trimmed.contains(")")
                && (trimmed.startsWith("public ") || trimmed.startsWith("protected "))) {
                return trimmed.replaceAll("\\s+", " ");
            }
        }
        return "";
    }

    private static List<String> safeLines(String code) {
        if (code == null) {
            return List.of();
        }
        return List.of(code.split("\n"));
    }
}
