// {@link com.codebuddy.merge.ApiIncompatibilityConflictResolver} Handles breaking public API changes.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles conflicts on public API surface: visibility, modifiers, exceptions and
 * return types.
 *
 * Every one of these is potentially breaking for callers that live outside the
 * merge, which the resolver cannot see. It therefore never applies a change; it
 * classifies the breaking edit and reports precisely which contract moved, so a
 * reviewer knows what to check rather than having to reconstruct it from conflict
 * markers.
 */
public final class ApiIncompatibilityConflictResolver extends AbstractConflictResolver {

    private static final Pattern SIGNATURE = Pattern.compile(
        "^\\s*((?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\\s+)*)"
            + "(?:<[^>]+>\\s*)?"
            + "([A-Za-z_$][\\w$.<>\\[\\],\\s?]*?)\\s+"
            + "([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)"
            + "(?:\\s*throws\\s+([\\w.,\\s]+))?");

    @Override
    public ConflictType supportedType() {
        return ConflictType.API_INCOMPATIBILITY;
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        // A public contract cannot be changed safely without seeing its callers.
        return null;
    }

    @Override
    protected List<FixPath> describeOptions(Conflict conflict) {
        Set<String> breakingEdits = describeBreakingEdits(conflict);

        FixPath.Builder primary = newFixPath(conflict)
            .description("Resolve a change to the public API surface")
            .options("Keep branch 1's signature", "Keep branch 2's signature",
                "Keep the base signature", "Introduce a compatibility overload")
            .justification(breakingEdits.isEmpty()
                ? "The two sides disagree on a declaration that is visible outside this file."
                : "Breaking edits detected: " + breakingEdits)
            .impact("Callers outside the merge may stop compiling, or may silently bind to "
                + "different behaviour. Search the repository for usages before choosing.");

        return List.of(
            primary.build(),
            newFixPath(conflict)
                .description("Preserve compatibility with a bridge")
                .options("Add an overload delegating to the new signature",
                    "Deprecate the old signature and keep both")
                .justification("Keeps existing callers compiling while the new shape is adopted.")
                .impact("Adds API surface that must later be removed; a deprecation cycle is "
                    + "the usual cost.")
                .build(),
            manualFixPath(conflict)
        );
    }

    /**
     * The specific contract elements that differ between the two branches, phrased
     * for a reviewer.
     */
    static Set<String> describeBreakingEdits(Conflict conflict) {
        Set<String> edits = new LinkedHashSet<>();

        Signature branch1 = parse(conflict.getBranch1Code());
        Signature branch2 = parse(conflict.getBranch2Code());
        Signature base = parse(conflict.getBaseCode());

        if (branch1 == null || branch2 == null) {
            edits.add("the changed declaration could not be parsed unambiguously");
            return edits;
        }

        if (!branch1.visibility().equals(branch2.visibility())) {
            edits.add("visibility differs ('" + branch1.visibility() + "' vs '"
                + branch2.visibility() + "')");
        }
        if (!branch1.modifiers().equals(branch2.modifiers())) {
            edits.add("modifiers differ ('" + branch1.modifiers() + "' vs '"
                + branch2.modifiers() + "')");
        }
        if (!branch1.returnType().equals(branch2.returnType())) {
            edits.add("return type differs ('" + branch1.returnType() + "' vs '"
                + branch2.returnType() + "')");
        }
        if (!branch1.parameters().equals(branch2.parameters())) {
            edits.add("parameters differ ('" + branch1.parameters() + "' vs '"
                + branch2.parameters() + "')");
        }
        if (!branch1.exceptions().equals(branch2.exceptions())) {
            edits.add("declared exceptions differ ('" + branch1.exceptions() + "' vs '"
                + branch2.exceptions() + "')");
        }

        if (base != null) {
            int baseRank = visibilityRank(base.visibility());
            boolean narrowed = visibilityRank(branch1.visibility()) < baseRank
                || visibilityRank(branch2.visibility()) < baseRank;
            if (narrowed) {
                edits.add("visibility was narrowed relative to base '" + base.visibility() + "'");
            }
            boolean exceptionDropped = !base.exceptions().isBlank()
                && (branch1.exceptions().isBlank() || branch2.exceptions().isBlank());
            if (exceptionDropped) {
                edits.add("a checked exception declared in base is no longer declared");
            }
        }

        if (edits.isEmpty()) {
            edits.add("the two sides disagree on a declaration visible outside this file");
        }
        return edits;
    }

    private static int visibilityRank(String visibility) {
        return switch (visibility) {
            case "public" -> 3;
            case "protected" -> 2;
            case "" -> 1;
            case "private" -> 0;
            default -> 1;
        };
    }

    private static Signature parse(String code) {
        if (code == null) {
            return null;
        }
        for (String line : code.split("\n")) {
            Matcher matcher = SIGNATURE.matcher(line);
            if (matcher.find()) {
                String modifiers = matcher.group(1) == null ? "" : matcher.group(1).trim();
                String visibility = "";
                List<String> others = new ArrayList<>();
                for (String modifier : modifiers.split("\\s+")) {
                    if (modifier.isEmpty()) {
                        continue;
                    }
                    if (modifier.equals("public") || modifier.equals("protected")
                        || modifier.equals("private")) {
                        visibility = modifier;
                    } else {
                        others.add(modifier);
                    }
                }
                String exceptions = matcher.group(5) == null
                    ? "" : matcher.group(5).replaceAll("\\s+", " ").trim();
                return new Signature(
                    visibility,
                    String.join(" ", others),
                    matcher.group(2).trim(),
                    matcher.group(3).trim(),
                    matcher.group(4).replaceAll("\\s+", " ").trim(),
                    exceptions
                );
            }
        }
        return null;
    }

    /**
     * The parts of a method signature that make up its public contract.
     */
    record Signature(String visibility, String modifiers, String returnType,
                     String name, String parameters, String exceptions) {
    }
}
