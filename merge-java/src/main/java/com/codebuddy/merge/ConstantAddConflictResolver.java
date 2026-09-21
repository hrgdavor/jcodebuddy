// {@link com.codebuddy.merge.ConstantAddConflictResolver} Resolves constant addition conflicts.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves conflicts where both branches add constants, enum members or other
 * static declarations.
 *
 * Adding a constant is additive exactly like adding an import, so the union is
 * correct - unless both branches introduced the same name with different values,
 * which is a real disagreement and is escalated for review.
 */
public final class ConstantAddConflictResolver extends AbstractConflictResolver {

    /**
     * Matches a static final field or an enum constant, capturing the name.
     */
    private static final Pattern CONSTANT_DECLARATION = Pattern.compile(
        "^\\s*(?:(?:public|protected|private)\\s+)?(?:static\\s+final|final\\s+static)\\s+"
            + "[A-Za-z_$][\\w$.<>\\[\\],\\s?]*\\s+([A-Z_$][A-Z0-9_$]*)\\s*(?:=|;)");

    /**
     * Matches an enum member, which by convention is SCREAMING_CASE on its own line.
     */
    private static final Pattern ENUM_MEMBER = Pattern.compile(
        "^\\s*([A-Z][A-Z0-9_]*)\\s*(?:\\(|,|;|$)");

    @Override
    public ConflictType supportedType() {
        return ConflictType.CONSTANT_ADD;
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        Map<String, String> branch1Constants = constantsIn(conflict.getBranch1Code());
        Map<String, String> branch2Constants = constantsIn(conflict.getBranch2Code());

        if (branch1Constants.isEmpty() && branch2Constants.isEmpty()) {
            return null;
        }

        Set<String> sharedNames = new LinkedHashSet<>(branch1Constants.keySet());
        sharedNames.retainAll(branch2Constants.keySet());

        List<String> colliding = new ArrayList<>();
        for (String name : sharedNames) {
            if (!branch1Constants.get(name).equals(branch2Constants.get(name))) {
                colliding.add(name);
            }
        }

        if (!colliding.isEmpty()) {
            return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
                .resolvedCode(conflict.getBranch1Code())
                .explanation("Both branches defined " + colliding
                    + " with different values, so one definition must win.")
                .alternativePaths(describeOptions(conflict))
                .build();
        }

        Set<String> unionNames = new LinkedHashSet<>(branch1Constants.keySet());
        unionNames.addAll(branch2Constants.keySet());

        // Identical name and value on both sides is a duplicate, not a conflict.
        return autoResolution(conflict, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode(conflict.getBranch1Code() + "\n" + conflict.getBranch2Code())
            .explanation("Merged " + unionNames.size() + " constant(s) from both branches"
                + (sharedNames.isEmpty() ? ""
                    : " (" + sharedNames.size() + " defined identically on both sides)")
                + ". Additive with no name/value disagreement.")
            .alternativePaths(describeOptions(conflict))
            .build();
    }

    @Override
    protected List<FixPath> describeOptions(Conflict conflict) {
        Map<String, String> branch1Constants = constantsIn(conflict.getBranch1Code());
        Map<String, String> branch2Constants = constantsIn(conflict.getBranch2Code());

        Set<String> names = new LinkedHashSet<>(branch1Constants.keySet());
        names.addAll(branch2Constants.keySet());

        Map<String, String> disagreements = new LinkedHashMap<>();
        for (String name : names) {
            String from1 = branch1Constants.get(name);
            String from2 = branch2Constants.get(name);
            if (from1 != null && from2 != null && !from1.equals(from2)) {
                disagreements.put(name, "branch 1: " + from1 + " | branch 2: " + from2);
            }
        }

        FixPath.Builder primary = newFixPath(conflict)
            .description(disagreements.isEmpty()
                ? "Keep the constants added by both branches"
                : "Resolve constants defined differently on each branch")
            .options("Keep both", "Keep branch 1's values", "Keep branch 2's values")
            .recommended(disagreements.isEmpty() ? "Keep both" : null)
            .justification(disagreements.isEmpty()
                ? "Constant additions are additive and the names do not collide."
                : "These names collide with different values: " + disagreements)
            .impact(disagreements.isEmpty()
                ? "None - both constants remain available."
                : "Callers observing the wrong value may misbehave; the value change is "
                    + "observable at runtime, not just at compile time.");

        return List.of(
            primary.build(),
            newFixPath(conflict)
                .description("Rename the colliding constant on one side")
                .options("Rename branch 1's constant", "Rename branch 2's constant")
                .justification("Keeps both values available when both are genuinely needed.")
                .impact("Renaming a public constant breaks callers that reference the old name.")
                .build()
        );
    }

    /**
     * Constants declared in a hunk, mapped from name to its declaration line.
     */
    static Map<String, String> constantsIn(String code) {
        Map<String, String> constants = new LinkedHashMap<>();
        if (code == null) {
            return constants;
        }
        for (String rawLine : code.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            Matcher constant = CONSTANT_DECLARATION.matcher(line);
            if (constant.find()) {
                constants.put(constant.group(1), line);
                continue;
            }
            Matcher enumMember = ENUM_MEMBER.matcher(line);
            if (enumMember.find() && !isModifierLike(enumMember.group(1))) {
                constants.putIfAbsent(enumMember.group(1), line);
            }
        }
        return constants;
    }

    private static boolean isModifierLike(String name) {
        return switch (name) {
            case "PUBLIC", "PRIVATE", "PROTECTED", "STATIC", "FINAL", "CLASS", "INTERFACE" -> true;
            default -> false;
        };
    }

    /**
     * The first constant name declared in a hunk, if any.
     */
    static Optional<String> firstName(String code) {
        return constantsIn(code).keySet().stream().findFirst();
    }
}
