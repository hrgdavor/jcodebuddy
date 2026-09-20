package hr.hrg.hipster.entity.tooling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects and formats the generator's divergence diagnostics (plan.dsflash § 8.7/3.20).
 *
 * <h3>The uniform format</h3>
 * <p>Every entry is a single line of {@code key=value} pairs in the DEC-022 order:</p>
 * <pre>
 *   kind=&lt;kind&gt;, location=&lt;where&gt;, cause=&lt;why&gt;, current=&lt;what is there&gt;,
 *   canonical=&lt;what the generator would emit&gt;, action=&lt;what the reader should do&gt;
 * </pre>
 *
 * <p>The two diagnostics that must exist first are the two that bit the example — "field in the enum
 * but not in the interface" and "field in the interface but not in the enum" — together with the two
 * R1 kinds {@code enum_order_shuffled} and {@code enum_constant_removed} and the warning-level
 * {@code enum_reorder_allowed}.</p>
 *
 * <h3>Why a report and not just a print</h3>
 * <p>A regeneration pass that silently changed the field set is exactly the failure the R1 rule
 * exists to prevent, so the divergences are collected into a value a caller can assert on
 * (tests, a build step) and are also printed for a human running the generator by hand.</p>
 *
 * <h3>Structured entries, and one entry per fact</h3>
 * <p>The reporter stores the six DEC-022 <em>fields</em> rather than only the rendered line, for two
 * reasons that are both defects the earlier string-only version had:</p>
 * <ul>
 *   <li><strong>{@link #ofKind} cannot silently miss an entry.</strong> It used to match the literal
 *       prefix {@code "kind=" + kind} on the rendered text, so an entry that reached the reporter
 *       through {@link #add}/{@link #addAll} in a shape the prefix did not fit was invisible to it —
 *       and a report that says nothing is indistinguishable from a report with nothing to say.</li>
 *   <li><strong>A duplicate is a duplicate.</strong> Entries are de-duplicated <em>by their whole
 *       content</em>: two entries collapse only when every DEC-022 field is equal. The same kind at
 *       the same location with a different {@code cause}/{@code current} is two facts and stays two
 *       — deleting one to make a report tidier is the information loss this class exists to prevent,
 *       while repeating one fact once per marker that claims a view is the noise that trains readers
 *       to skim (plan.dsflash § 8.7/3.20's known remaining gap).</li>
 * </ul>
 */
public final class DivergenceReporter {

    /** The recognized kinds. Anything else is still reported; this list is documentation plus tests. */
    public static final List<String> KINDS = List.of(
            "field_in_enum_not_in_interface",
            "field_in_interface_not_in_enum",
            "stale_switch",
            "missing_setter",
            "type_mismatch",
            "type_ambiguous",
            "type_unresolved",
            "ordinal_drift",
            "enum_order_shuffled",
            "enum_constant_removed",
            "enum_constant_appended",
            "enum_reorder_allowed",
            "enum_not_parsed",
            "source_not_parsed",
            "field_retired",
            "addon_field_collision",
            "nested_record_reused",
            "polymorphic_root_enum_preserved",
            "addon_on_non_view",
            "generated_member_diverged",
            "mapper_field_missing_in_source",
            "mapper_field_missing_in_target",
            "mapper_type_incompatible",
            "mapper_view_not_found",
            "mapper_request_malformed",
            "validation_constraint_unsupported",
            "validation_constraint_type_mismatch",
            "deep_tracking_type_not_enabled");

    /** The DEC-022 field order. An entry is rendered in exactly this order. */
    private static final List<String> FIELDS =
            List.of("kind", "location", "cause", "current", "canonical", "action");

    /**
     * A field boundary: the literal <em>", field="</em> of a DEC-022 field.
     *
     * <p>Used to split a pre-formatted line without guessing where a value ends: a value may itself
     * contain a comma (a component list, a message), so splitting on every comma would corrupt it
     * while splitting on field boundaries cannot.</p>
     */
    private static final Pattern BOUNDARY = Pattern.compile(", (" + String.join("|", FIELDS) + ")=");

    /**
     * One divergence, as fields.
     *
     * <p>{@code raw} is non-null only for an entry that arrived through {@link #add} in a shape this
     * class cannot decompose into DEC-022 pairs. Such a line is still reported verbatim — it is a
     * diagnostic somebody wrote on purpose — and it still has an identity of its own, but it has no
     * kind and so no {@link #ofKind} answer.</p>
     */
    private record Entry(Map<String, String> pairs, String raw) {

        static Entry of(Map<String, String> pairs) {
            return new Entry(pairs, null);
        }

        static Entry raw(String line) {
            return new Entry(Map.of(), line);
        }

        String kind() {
            return pairs.get("kind");
        }

        /** The stable identity used for de-duplication: every field, or the raw line. */
        String identity() {
            return raw != null ? "raw:" + raw : renderFields(pairs);
        }

        String render() {
            return raw != null ? raw : renderFields(pairs);
        }
    }

    /**
     * De-duplicated entries in first-occurrence order. A {@link LinkedHashMap} keyed by the entry's
     * identity is the whole mechanism: the order is insertion order, and an identical repeat is a
     * no-op that is counted instead of appended.
     */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** How many entries were dropped because an identical entry was already present. */
    private int duplicatesRemoved;

    /**
     * Adds one already-formatted entry, or no-ops when the identical entry is already present.
     *
     * <p>A line that does not open with {@code kind=} is kept verbatim rather than parsed: guessing
     * at a diagnostic's shape is worse than reporting it unread.</p>
     */
    public void add(String entry) {
        if (entry == null || entry.isBlank()) {
            return;
        }
        Map<String, String> pairs = parsePairs(entry);
        addEntry(pairs.isEmpty() ? Entry.raw(entry) : Entry.of(pairs));
    }

    /** Adds every entry of a produced list. */
    public void addAll(List<String> produced) {
        if (produced != null) {
            produced.forEach(this::add);
        }
    }

    /** Builds one entry in the DEC-022 format, omitting pairs whose value is null. */
    public void report(String kind, String location, String cause, String current, String canonical, String action) {
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("kind", kind);
        pairs.put("location", location);
        pairs.put("cause", cause);
        pairs.put("current", current);
        pairs.put("canonical", canonical);
        pairs.put("action", action);
        addEntry(Entry.of(pairs));
    }

    private void addEntry(Entry entry) {
        String identity = entry.identity();
        if (entries.putIfAbsent(identity, entry) != null) {
            duplicatesRemoved++;
        }
    }

    /** Every entry, in the order it was first produced. */
    public List<String> entries() {
        List<String> rendered = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            rendered.add(entry.render());
        }
        return Collections.unmodifiableList(rendered);
    }

    /**
     * Only the entries of one kind.
     *
     * <p>Matched on the stored kind, so an entry is found however it reached the reporter.</p>
     */
    public List<String> ofKind(String kind) {
        List<String> matching = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (kind.equals(entry.kind())) {
                matching.add(entry.render());
            }
        }
        return Collections.unmodifiableList(matching);
    }

    /**
     * How many duplicate entries were collapsed.
     *
     * <p>Asserted by a test rather than assumed: "the report is short" and "the report collapsed the
     * duplicates" are different claims, and only the second one is the fix.</p>
     */
    public int duplicatesRemoved() {
        return duplicatesRemoved;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** The report as one line per entry, for a log or a test failure message. */
    public String render() {
        return String.join("\n", entries());
    }

    private static String renderFields(Map<String, String> pairs) {
        StringBuilder sb = new StringBuilder();
        for (String field : FIELDS) {
            String value = pairs.get(field);
            if (value == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(field).append('=').append(value);
        }
        return sb.toString();
    }

    /**
     * Decomposes a pre-formatted DEC-022 line into its pairs, or returns an empty map when the line
     * is not in that shape.
     */
    private static Map<String, String> parsePairs(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("kind=")) {
            return Map.of();
        }
        Matcher matcher = BOUNDARY.matcher(trimmed);
        List<String> keys = new ArrayList<>();
        List<Integer> bounds = new ArrayList<>();
        keys.add("kind");
        bounds.add(0);
        while (matcher.find()) {
            keys.add(matcher.group(1));
            bounds.add(matcher.start());
        }
        bounds.add(trimmed.length());

        Map<String, String> pairs = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            String segment = trimmed.substring(bounds.get(i), bounds.get(i + 1));
            int eq = segment.indexOf('=');
            if (eq < 0) {
                return Map.of();
            }
            String key = segment.substring(0, eq).trim();
            if (key.startsWith(", ")) {
                key = key.substring(2).trim();
            }
            pairs.put(key, segment.substring(eq + 1).trim());
        }
        return pairs.isEmpty() ? Map.of() : pairs;
    }
}
