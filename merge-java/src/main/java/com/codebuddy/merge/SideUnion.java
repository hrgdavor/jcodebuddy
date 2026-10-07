// {@link com.codebuddy.merge.SideUnion} The standard "keep both sides" construction, verified.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The code of a block that keeps <em>both</em> sides: what the two sides share, then each side's
 * addition.
 *
 * <h2>Why concatenation is wrong</h2>
 *
 * <p>Both sides of a conflict hunk carry the context around the change — which includes the members
 * the base already declared. Appending one side to the other therefore repeats every shared member,
 * and the result does not compile. This was not hypothetical: measured on the fixture
 * {@link MemberAddConflictResolver} was written for, the merged class declared {@code audit()} twice,
 * and {@link OverloadAddConflictResolver} did the same to {@code process()}. A "keep both" that emits
 * code the compiler rejects is worse than leaving the block for a human, because the merge looks
 * resolved.
 *
 * <h2>How the union is built, and what makes it sound</h2>
 *
 * <ol>
 *   <li>the longest common <b>prefix</b> of the two sides is taken once — for adjacent insertions
 *       that is the shared context, and it is what stops the shared members being repeated;</li>
 *   <li>each side's <b>remainder</b> after that prefix is appended, in order;</li>
 *   <li>the result is <b>verified structurally</b>: no member may appear twice. If the two sides share
 *       anything <em>past</em> the prefix, both remainders contain it and it is repeated, so the
 *       prefix was not enough to prove the merge sound — and rather than reason about which repeated
 *       lines happen to be harmless, the construction is refused.</li>
 * </ol>
 *
 * <p>A refused union returns {@code null}, and every caller turns that into a decline, so an
 * unprovable shape becomes a human's decision instead of code that silently does not compile.
 */
final class SideUnion {

    private SideUnion() {
    }

    /**
     * The union of the two sides, or {@code null} when it cannot be shown sound.
     *
     * @param branch1Code one side of the block, as it appears in the conflict markers
     * @param branch2Code the other side
     */
    static String of(String branch1Code, String branch2Code) {
        if (branch1Code == null || branch2Code == null) {
            return null;
        }

        List<String> lines1 = List.of(branch1Code.split("\n", -1));
        List<String> lines2 = List.of(branch2Code.split("\n", -1));

        int shared = 0;
        while (shared < lines1.size() && shared < lines2.size()
            && lines1.get(shared).equals(lines2.get(shared))) {
            shared++;
        }

        List<String> merged = new ArrayList<>(lines1.subList(0, shared));
        merged.addAll(lines1.subList(shared, lines1.size()));
        merged.addAll(lines2.subList(shared, lines2.size()));
        String union = String.join("\n", merged);

        List<DeclarationScanner.MethodDeclaration> declarations =
            DeclarationScanner.declarationsIn(union);
        long distinct = declarations.stream()
            .map(DeclarationScanner.MethodDeclaration::signature)
            .distinct()
            .count();
        if (distinct != declarations.size()) {
            return null;
        }

        // A field repeated does not compile either, and the same construction can repeat one. Both field
        // readings are counted: the level-based one for a fragment that declares a method or a type,
        // and the modifier-based one for a bare insertion, where a level cannot be established.
        List<String> fields = new ArrayList<>(DeclarationScanner.fieldNames(union));
        fields.addAll(DeclarationScanner.accessibleFieldNames(union));
        return new LinkedHashSet<>(fields).size() == fields.size() ? union : null;
    }

    /**
     * True when every non-blank line of {@code side} appears in {@code union} — the check a resolution
     * has to pass to be applied at all, exposed here so a caller can see the union kept everything.
     */
    static boolean keeps(String union, String side) {
        if (union == null || side == null) {
            return false;
        }
        List<String> unionLines = List.of(union.split("\n", -1));
        for (String line : side.split("\n", -1)) {
            if (!line.isBlank() && !unionLines.contains(line)) {
                return false;
            }
        }
        return true;
    }
}