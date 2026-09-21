// {@link com.codebuddy.merge.ConflictDetectionService} Detects conflicts between base and branch versions of a file.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

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

        List<Conflict> recognised = new ArrayList<>(conflicts);
        Conflict structural = detectStructuralConflict(filePath, baseCode, branch1Code, branch2Code,
            recognised);
        if (structural != null) {
            conflicts.add(structural);
        }

        return attributeRegions(baseCode, conflicts, typeContext);
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

        if (membersDiverged) {
            return new Conflict(ConflictType.STRUCTURAL_CHANGE, filePath,
                "The set of declared members differs between the branches",
                baseCode, branch1Code, branch2Code);
        }

        Set<String> residual = residualLines(baseCode, branch1Code, branch2Code);
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
            if (bothBranchesChangedContent(baseCode, branch1Code, branch2Code)) {
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
        Set<String> base = normalisedLines(baseCode);
        Set<String> changed1 = difference(normalisedLines(branch1Code), base);
        Set<String> changed2 = difference(normalisedLines(branch2Code), base);

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
        Set<String> branch1Lines = normalisedLines(branch1Code);
        Set<String> branch2Lines = normalisedLines(branch2Code);

        Set<String> residual = new LinkedHashSet<>();
        for (String line : normalisedLines(baseCode)) {
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

    private static Set<String> normalisedLines(String code) {
        Set<String> lines = new LinkedHashSet<>();
        if (code == null) {
            return lines;
        }
        for (String line : code.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /**
     * Attach the base-file region each conflict covers, so a report can tell
     * which automatic resolutions are independent of the conflicts that still
     * need a human, and the type context a resolver may need.
     */
    private List<Conflict> attributeRegions(String baseCode, List<Conflict> conflicts,
                                            TypeContext typeContext) {
        List<Conflict> attributed = new ArrayList<>(conflicts.size());
        for (Conflict conflict : conflicts) {
            Conflict withRegion = conflict.withRegion(regionFor(baseCode, conflict));
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
    private Region regionFor(String baseCode, Conflict conflict) {
        if (conflict.getType() == ConflictType.STRUCTURAL_CHANGE
            || conflict.getType() == ConflictType.METHOD_BODY_CHANGE
            || conflict.getType() == ConflictType.API_INCOMPATIBILITY) {
            // These types have no single anchor declaration: what changed is the
            // content of a member, so the region is the span of base lines the
            // branches did not both preserve.
            return structuralRegion(baseCode, conflict.getBranch1Code(), conflict.getBranch2Code());
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
    private Region structuralRegion(String baseCode, String branch1Code, String branch2Code) {
        String[] baseLines = baseCode == null || baseCode.isBlank()
            ? new String[0]
            : baseCode.split("\n", -1);
        return spanNotKeptByBoth(baseLines, branch1Code, branch2Code);
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
        int first = -1;
        int last = -1;
        for (int index = 0; index < baseLines.length; index++) {
            if (keptByBothBranches(baseLines[index], branch1Code, branch2Code)) {
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
     * True when the line, compared without surrounding whitespace, survives
     * unchanged in both branches.
     *
     * <p>A blank line counts as kept: whitespace churn is not a structural change.
     */
    static boolean keptByBothBranches(String line, String branch1Code, String branch2Code) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        return containsLine(branch1Code, trimmed) && containsLine(branch2Code, trimmed);
    }

    private static boolean containsLine(String code, String trimmedLine) {
        if (code == null) {
            return false;
        }
        for (String line : code.split("\n")) {
            if (line.trim().equals(trimmedLine)) {
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
