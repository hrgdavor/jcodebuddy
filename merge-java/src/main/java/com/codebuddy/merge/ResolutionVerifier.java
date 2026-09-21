// {@link com.codebuddy.merge.ResolutionVerifier} Gate that must pass before an automatic resolution is applied.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Decides whether a resolution is safe to apply without a human.
 *
 * <p>The module's promise is that an <em>automatic</em> resolution is one that
 * will not break the build. That promise needs a check, because a resolver can be
 * wrong: combining two import lists is trivially correct, but a heuristic that
 * mis-parses a hunk can emit syntactically broken code, and a silent broken
 * automatic resolution is worse than a conflict - it hides a failure behind a
 * success report.
 *
 * <p>A verifier therefore never accepts a resolution. It can only:
 * <ul>
 *   <li>mark it {@link ConflictResolution.Verification#PASSED}, leaving it
 *       automatic;</li>
 *   <li>mark it {@link ConflictResolution.Verification#FAILED} and <b>downgrade
 *       it from {@code AUTO} to {@code REVIEW}</b>, attaching the reason as a fix
 *       path so the reviewer knows what to look at.</li>
 * </ul>
 *
 * <p>It deliberately cannot promote a resolution: verification is a floor, not a
 * proof. It establishes well-formedness, never intent. That is also why it does
 * not make the excluded conflict types (see
 * {@code DESIGN_NEVER_AUTO_RESOLVED.md}) safe to automate.
 */
public interface ResolutionVerifier {

    /**
     * The outcome of verifying one resolution.
     *
     * @param status   what was concluded
     * @param detail   human-readable reason, empty when the status is
     *                 {@link ConflictResolution.Verification#PASSED} or
     *                 {@link ConflictResolution.Verification#NOT_RUN}
     */
    record Result(ConflictResolution.Verification status, String detail) {

        public static Result passed() {
            return new Result(ConflictResolution.Verification.PASSED, "");
        }

        public static Result failed(String detail) {
            return new Result(ConflictResolution.Verification.FAILED,
                detail == null ? "verification failed" : detail);
        }

        public static Result skipped(String detail) {
            return new Result(ConflictResolution.Verification.SKIPPED,
                detail == null ? "verification not applicable" : detail);
        }

        public boolean isFailure() {
            return status == ConflictResolution.Verification.FAILED;
        }
    }

    /**
     * Verify a resolution. Implementations must not throw: return
     * {@link Result#skipped(String)} when the input cannot be checked.
     */
    Result verify(Conflict conflict, ConflictResolution resolution);

    /**
     * Verify and apply the outcome: a failure downgrades an automatic resolution
     * to review and records why, so that no caller can apply an unverified
     * automatic change.
     *
     * <p>Centralising the downgrade here - rather than in each verifier - keeps it
     * uniform: every verifier gets the same treatment, and the only way a
     * resolution's kind changes after a resolver produced it is through this
     * method.
     */
    default ConflictResolution apply(Conflict conflict, ConflictResolution resolution) {
        if (resolution.getKind() != ConflictResolution.ResolutionKind.AUTO) {
            // Only an automatic resolution carries the promise that is being
            // verified. Review, manual and replayed resolutions are already
            // surfaced to a human, so verifying them would add nothing.
            return resolution;
        }

        Result result = verify(conflict, resolution);
        if (result == null) {
            result = Result.skipped("verifier returned no result");
        }
        if (!result.isFailure()) {
            return ConflictResolution.copyOf(resolution)
                .verification(result.status())
                .build();
        }

        ConflictResolution failed = ConflictResolution.copyOf(resolution)
            .verification(ConflictResolution.Verification.FAILED)
            .explanation(resolution.getExplanation()
                + " [verification failed: " + result.detail() + "]")
            .build();

        // Downgrade: automatic was not earned. The copy constructor already
        // carries whatever fix paths the resolver offered, so the verification
        // reason is appended to them rather than replacing them.
        return ConflictResolution.copyOf(failed)
            .kind(ConflictResolution.ResolutionKind.REVIEW)
            .addAlternativePath(FixPath.builder()
                .conflictType(failed.getType())
                .description("Confirm the automatic resolution by hand")
                .options("Accept the resolved code", "Rewrite it", "Resolve manually")
                .recommended("Rewrite it")
                .justification("The automatic resolution did not pass verification: "
                    + result.detail())
                .impact("Applying it unchanged risks a build failure.")
                .build())
            .build();
    }

    /**
     * The default gate: a structural sanity check on the resolved code.
     *
     * <p>It catches the realistic failure mode - a resolver spliced two hunks
     * together and left a dangling brace, an unterminated literal, or an
     * unterminated comment - without needing a compiler, which makes it cheap
     * enough to run on every resolution.
     *
     * <p>Scope is stated explicitly rather than implied, because a verifier that
     * fails on valid input is worse than one that skips:
     *
     * <ul>
     *   <li>The code is checked when it stands alone - it declares a package, or
     *       it contains a type declaration - or when the three inputs were
     *       themselves balanced, which means the resolution had the opportunity to
     *       unbalance them.</li>
     *   <li>Otherwise the resolved code is a <em>fragment</em> (a method body, a
     *       list of constants) whose delimiter balance is undefined in isolation,
     *       so the result is {@code SKIPPED} with that reason. Reporting a false
     *       failure here would train callers to ignore the gate.</li>
     *   <li>A manual resolution is never applied, so there is nothing to
     *       verify.</li>
     * </ul>
     *
     * <p>It cannot promote a resolution and it establishes well-formedness only,
     * never intent - see the class comment.
     */
    static ResolutionVerifier structural() {
        return (conflict, resolution) -> {
            if (resolution.requiresHumanDecision()) {
                return Result.skipped("a manual resolution is never applied");
            }
            String code = resolution.getResolvedCode();
            if (code == null || code.isBlank()) {
                return Result.failed("the resolution produced no code");
            }
            if (code.contains("<<<<<<<") || code.contains(">>>>>>>")) {
                return Result.failed("the resolution still contains conflict markers");
            }

            if (!isSelfContained(code)
                && !wasBalanced(conflict.getBaseCode())
                && !wasBalanced(conflict.getBranch1Code())
                && !wasBalanced(conflict.getBranch2Code())) {
                return Result.skipped(
                    "the resolved code is a fragment, so delimiter balance is undefined");
            }

            String problem = checkBalanced(code);
            return problem == null ? Result.passed() : Result.failed(problem);
        };
    }

    /**
     * True when the code is a complete compilation unit or a set of top-level
     * declarations, so delimiter balance is meaningful on its own.
     */
    static boolean isSelfContained(String code) {
        String trimmed = code.stripLeading();
        if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
            return true;
        }
        // A top-level type declaration at the start of a line.
        return java.util.regex.Pattern
            .compile("(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
                + "(?:class|interface|enum|record|@interface)\\s")
            .matcher(code)
            .find();
    }

    /**
     * True when the given code has no delimiter defect, including the empty and
     * null cases.
     */
    static boolean wasBalanced(String code) {
        return code == null || code.isBlank() || checkBalanced(code) == null;
    }

    /**
     * A verifier that accepts everything. Useful in tests that exercise
     * resolution rather than the gate, and as an explicit opt-out.
     */
    static ResolutionVerifier permissive() {
        return (conflict, resolution) -> Result.passed();
    }

    /**
     * Check that brackets, parentheses and braces are balanced and correctly
     * nested, ignoring their appearance inside string and character literals and
     * comments.
     *
     * @return a description of the problem, or {@code null} when the code is
     *         balanced
     */
    static String checkBalanced(String code) {
        Deque<Character> expected = new ArrayDeque<>();
        int line = 1;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char stringDelimiter = 0;

        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\n') {
                line++;
                inLineComment = false;
                continue;
            }
            if (inLineComment) {
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < code.length() && code.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (stringDelimiter != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == stringDelimiter) {
                    stringDelimiter = 0;
                }
                continue;
            }

            switch (c) {
                case '/' -> {
                    if (i + 1 < code.length() && code.charAt(i + 1) == '/') {
                        inLineComment = true;
                        i++;
                    } else if (i + 1 < code.length() && code.charAt(i + 1) == '*') {
                        inBlockComment = true;
                        i++;
                    }
                }
                case '"', '\'' -> stringDelimiter = c;
                case '(', '[', '{' -> expected.push(closingFor(c));
                case ')', ']', '}' -> {
                    if (expected.isEmpty()) {
                        return "unbalanced '" + c + "' on line " + line;
                    }
                    char wanted = expected.pop();
                    if (wanted != c) {
                        return "expected '" + wanted + "' but found '" + c + "' on line " + line;
                    }
                }
                default -> {
                    // Ordinary character.
                }
            }
        }

        if (stringDelimiter != 0) {
            return "unterminated string literal";
        }
        if (inBlockComment) {
            return "unterminated block comment";
        }
        if (!expected.isEmpty()) {
            return "unclosed '" + closingFor(openingFor(expected.peek())) + "' ("
                + expected.size() + " unclosed)";
        }
        return null;
    }

    private static char closingFor(char opener) {
        return switch (opener) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            default -> opener;
        };
    }

    private static char openingFor(char closer) {
        return switch (closer) {
            case ')' -> '(';
            case ']' -> '[';
            case '}' -> '{';
            default -> closer;
        };
    }
}
