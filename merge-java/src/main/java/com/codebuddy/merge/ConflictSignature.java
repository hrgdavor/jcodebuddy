// {@link com.codebuddy.merge.ConflictSignature} Stable identity for a recurring conflict.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A stable identity for "the same conflict", used to replay a recorded decision
 * on a later base-branch update.
 *
 * <p>The signature deliberately ignores formatting churn and the exact line
 * numbers, because those move on every rebase. It is derived from the conflict
 * type plus the normalised code on both sides: if the two versions still differ
 * in the same way, the previous decision still applies.
 *
 * @param conflictType   the kind of conflict
 * @param filePath       repository-relative path, so decisions stay per file
 * @param contentHash    hash of the normalised branch-1/branch-2 content
 */
public record ConflictSignature(ConflictType conflictType, String filePath, String contentHash) {

    public ConflictSignature {
        Objects.requireNonNull(conflictType, "conflictType");
        filePath = filePath == null ? "<unknown>" : filePath.replace('\\', '/');
        contentHash = contentHash == null ? "" : contentHash;
    }

    /**
     * Derive the signature of a concrete conflict.
     */
    public static ConflictSignature of(Conflict conflict) {
        Objects.requireNonNull(conflict, "conflict");
        return of(conflict.getType(), conflict.getFilePath(), conflict.getBranch1Code(),
            conflict.getBranch2Code());
    }

    /**
     * Derive the signature from the parts a report carries.
     *
     * <p>A resolution holds its own three sides, so a renderer that shows a resolution can key the decision a
     * reviewer makes on it without reconstructing a {@link Conflict} first - which is what the review page's
     * exported decisions need (plan step 4.3), and what lets the recorder check that the key the page showed
     * is the key the incoming conflict really has.</p>
     */
    public static ConflictSignature of(ConflictType conflictType, String filePath, String branch1Code,
                                       String branch2Code) {
        Objects.requireNonNull(conflictType, "conflictType");
        return new ConflictSignature(
            conflictType,
            filePath,
            hash(normalise(branch1Code) + "\u0000" + normalise(branch2Code))
        );
    }

    /**
     * A filesystem-safe name for this signature.
     */
    public String toFileName() {
        return conflictType.name().toLowerCase() + "-" + contentHash.substring(0, Math.min(16, contentHash.length()));
    }

    /**
     * Strip formatting noise so that reformatting a conflict does not invalidate
     * a recorded decision.
     *
     * <p>Blank lines are dropped, runs of whitespace are collapsed, and spaces
     * around punctuation are removed, because an editor that reindents or
     * re-spaces the same code has not changed the disagreement. Anything semantic
     * - identifiers, operators, literals, and their order - is preserved, so a
     * genuinely different conflict still hashes differently.
     */
    static String normalise(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder normalised = new StringBuilder(code.length());
        for (String line : code.split("\n")) {
            String collapsed = line.strip()
                .replaceAll("\\s+", " ")
                // Spacing around punctuation is formatting, not meaning.
                .replaceAll("\\s*([=;,.(){}\\[\\]])\\s*", "$1");
            if (!collapsed.isEmpty()) {
                normalised.append(collapsed).append('\n');
            }
        }
        return normalised.toString();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    @Override
    public String toString() {
        return conflictType + "@" + filePath + "#" + contentHash.substring(0, Math.min(8, contentHash.length()));
    }
}
