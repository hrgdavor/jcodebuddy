// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling.meta;

/**
 * One source/target type pair a mapper request resolved, and whether it needs a converter — DEC-006 (accepted
 * 2026-10-08), plan step 6.4.
 *
 * <p>The decision is about <em>converter coverage</em>, and coverage is a question about a set of pairs: which pairs
 * does this project's mapping produce, and which of them has nothing that can convert? A message string answers that for
 * a human reading one line and not for anything else, which is why this is a value: the pass collects these, the
 * converter manifest is rendered from them, and a test can assert the classification without parsing prose.</p>
 *
 * <p><strong>Both outcomes are recorded, not only the failures.</strong> {@code converterRequired == false} is the
 * answer for a pair the mapper converts as it stands — a widening primitive, an identical type, an {@code Object}
 * target — and it is what makes the manifest a statement of coverage rather than a list of complaints: a project can see
 * that a pair was considered and needed nothing.</p>
 *
 * @param sourceType        the source field's declared type, as written
 * @param targetType        the target field's declared type, as written
 * @param location          where the pair was resolved, as {@code <View>.<field>}
 * @param converterRequired whether a converter must exist for the pair to be mapped at all
 * @param reason            why, in the mapper's own terms — the sentence a reader needs when it is {@code true}
 */
public record TypeDivergence(String sourceType, String targetType, String location, boolean converterRequired,
                             String reason) {

    /** The pair as one string, which is how a manifest line and a test both name it. */
    public String pair() {
        return sourceType + " -> " + targetType;
    }

    /**
     * One manifest line, deterministic: the pair, whether a converter is required, where it was seen, and why.
     *
     * <p>Deliberately not JSON: the manifest's own document shape is a decision this record does not make (see plan step
     * 6.4), and a caller that wants JSON escapes these fields rather than re-deriving them.</p>
     */
    public String render() {
        return pair() + (converterRequired ? " [converter required]" : " [converted as-is]")
                + " at " + location + " — " + reason;
    }
}
