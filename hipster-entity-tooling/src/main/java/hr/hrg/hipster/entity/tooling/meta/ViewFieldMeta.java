package hr.hrg.hipster.entity.tooling.meta;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One field of a view with <strong>every location the pass knows</strong>, not just its declaration start
 * — the record {@code views[].fields[]} is written from (DEC-028).
 *
 * <p>This is the answer to "where is this field", written by the pass that emitted the files instead of
 * reconstructed by a consumer that scans them. The metadata previously carried one location per field
 * ({@code lineNumber} + a path), which is the <em>declaration start</em> in the declaring interface —
 * annotation included — and nothing else. The constant in the generated field enum, the record component,
 * the field and setter in each builder and the two switch arms were not in the metadata at all, so the
 * only consumer that could answer the question was the Bun page, by scanning the committed source and
 * guessing by naming convention. A heuristic answer to "where is this field" is a second place the same
 * knowledge lives, and the two can disagree silently.</p>
 *
 * <p><strong>The shape.</strong> Grouped, not a flat list of {@code {artifact, role, file, line}}: an
 * artifact name is nearly as long as a source path ({@code PersonSummaryBuilderTracking} is 28
 * characters), so repeating it per location would keep the payload as large as inlining paths did — the
 * whole point of the central index is lost. A flat list is simpler for a consumer and roughly 40 % larger;
 * the measured trade-off is recorded in DEC-028.</p>
 *
 * <ul>
 *   <li>Keys of {@code at} are <strong>artifact ids</strong> from {@code views[].artifacts[]} (1–2
 *       characters), so the artifact's own file id, name and kind are stated once, in the inventory,
 *       rather than once per location.</li>
 *   <li>Values are {@code role → line} maps. Both the artifact order and the role order are fixed by
 *       DEC-028 so two runs produce byte-identical output and the page's columns are stable.</li>
 *   <li>A role that does not exist for a field is <strong>absent</strong>, not empty and not {@code -1}:
 *       a {@code DERIVED} field has no setter anywhere, and a view has no record location for a field the
 *       record does not carry. The page renders the absence.</li>
 * </ul>
 *
 * <p>The authoritative per-field location map is this one. {@code allFields[]} deliberately does not gain
 * one: it is a per-marker union across views, and a union of per-view artifact ids has no stable meaning
 * (artifact {@code 2} of one view is a different type than artifact {@code 2} of another). A consumer that
 * wants "everywhere this field is" walks the marker's views.</p>
 *
 * @param name       the accessor name, which is also the enum constant name and the JSON name
 * @param ordinal    the field's position in the view's field enum (DEC-023's ledger order), or {@code -1}
 *                   when the ledger does not carry the field
 * @param type       the declared type as written in source, e.g. {@code Map&lt;String, List&lt;Long&gt;&gt;};
 *                   the JSON writes it in the same shape {@code properties[].type} already uses
 * @param fieldKind  the {@code FieldKind} name, or {@code null}
 * @param column     the {@code column} override, or {@code null}
 * @param relation   the {@code relation} value, or {@code null}
 * @param expression the {@code expression} value, or {@code null}
 * @param at         artifact id → role → line, in artifact order and, inside an artifact, in the fixed
 *                   role order. Never {@code null}; empty for a view whose emission was skipped
 */
public record ViewFieldMeta(String name, int ordinal, String type, String fieldKind, String column,
                            String relation, String expression,
                            Map<Integer, Map<String, Integer>> at) {

    /**
     * The fixed role order DEC-028 pins. The page's column order is derived from it, so it is part of the
     * contract rather than an implementation detail: changing it reorders the rendered table.
     *
     * <p>{@code accessor} precedes {@code annotation} because the page shows the declared accessor before
     * the {@code @FieldSource} line that annotates it, and the two are genuinely different lines
     * ({@code PersonSummary.age} is 17 and 16 respectively).</p>
     */
    public static final java.util.List<String> ROLES = java.util.List.of(
            "accessor", "annotation", "enum-constant", "name-slot", "record-component", "field",
            "setter", "ordinal-slot");

    /** Position of {@code role} in {@link #ROLES}, or {@code ROLES.size()} for an unknown role. */
    public static int roleOrder(String role) {
        int index = ROLES.indexOf(role);
        return index < 0 ? ROLES.size() : index;
    }

    public ViewFieldMeta {
        name = name == null ? "" : name;
        at = copyAt(at);
    }

    /**
     * A defensive, order-preserving copy.
     *
     * <p>Both levels are {@link LinkedHashMap}s because insertion order <em>is</em> the contract: the
     * writer emits the outer keys in artifact order and the inner keys in role order, and a consumer reads
     * them in that order. A plain {@code Map.copyOf} would make the iteration order unspecified and two
     * runs could differ.</p>
     */
    private static Map<Integer, Map<String, Integer>> copyAt(Map<Integer, Map<String, Integer>> at) {
        if (at == null || at.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<Integer, Map<String, Integer>> copy = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Integer>> entry : at.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(entry.getValue()));
        }
        return copy;
    }

    /** The line recorded for {@code artifactId}/{@code role}, or {@code -1} when the role does not exist. */
    public int lineAt(int artifactId, String role) {
        Map<String, Integer> roles = at.get(artifactId);
        if (roles == null) {
            return -1;
        }
        Integer line = roles.get(role);
        return line == null ? -1 : line;
    }

    /** Whether this field has any recorded location at all. */
    public boolean located() {
        return !at.isEmpty();
    }

    public String getName() {
        return name;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public String getType() {
        return type;
    }

    public Map<Integer, Map<String, Integer>> getAt() {
        return at;
    }
}
