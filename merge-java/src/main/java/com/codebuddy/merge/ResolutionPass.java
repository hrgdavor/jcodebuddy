// {@link com.codebuddy.merge.ResolutionPass} The live conflicts of one block, and the state each holds.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The working set of one merge block: every conflict detection produced, the {@link ConflictState} each
 * one holds, and the check that no line of the block is lost between tiers.
 *
 * <h2>Why a set of live conflicts rather than a list of claims</h2>
 *
 * <p>The module resolved a block by producing a claim for every conflict and then arbitrating between
 * them ({@link AnalysisLevel}, and {@code MergeFileTool.outranking}). That shape cannot express the
 * requirement this class exists for: a conflict a higher tier has settled reliably must be
 * <b>removed</b>, so that the lower tier is never asked about it and no objection to it is ever
 * constructed. Arbitration requires every claim to exist first, which is precisely what "I do not want
 * lower level resolver to even see conflict" forbids.
 *
 * <p>So a pass holds <em>live</em> conflicts. A tier is offered {@link #live()} — the conflicts still
 * {@link ConflictState#OPEN} or {@link ConflictState#PARTIAL} — and a conflict that reaches
 * {@link ConflictState#RESOLVED} is gone from what the next tier is handed.
 *
 * <h2>The three regions of a conflict, and the invariant they must satisfy</h2>
 *
 * <p>A conflict keeps the region detection gave it ({@link Conflict#getRegion()}) and, as the pass
 * proceeds, the spans that have been settled inside it. Those account for the region exactly:
 *
 * <ul>
 *   <li>{@link ConflictState#OPEN} — nothing settled, so the whole region is still open;</li>
 *   <li>{@link ConflictState#RESOLVED} — the whole region is settled, and the conflict leaves
 *       {@link #live()};</li>
 *   <li>{@link ConflictState#PARTIAL} — the settled spans plus the open remainder are exactly the
 *       original region, with nothing lost and nothing settled twice.</li>
 * </ul>
 *
 * <p>{@link #partition()} is that invariant as a check, and it is written <b>before</b> anything consumes
 * it, because the defect it catches is silent: a line dropped between two tiers because each believed
 * the other had it is data loss that no outcome text would show. See
 * <a href="../../../../../../docs/HIERARCHICAL_RESOLUTION.md">HIERARCHICAL_RESOLUTION.md</a> § 4 and
 * <a href="../../../../../../../../doc-hipster-entity/architecture/decisions/DEC-046.md">DEC-046</a>
 * clauses 8–9; plan step 4.18.
 *
 * <h2>What this class does not do yet</h2>
 *
 * <p>Nothing here decides whether a resolution is reliable enough to settle a span — that is step 4.19
 * ({@code HIERARCHICAL_RESOLUTION.md} § 3.2), and it is the reason {@link #withResolved} and
 * {@link #withPartial} take the spans from their caller rather than deriving them. This step lands the
 * working set, the states and the invariant, and changes no behaviour: nothing is removed yet.
 */
public final class ResolutionPass {

    /**
     * One detected conflict and everything the pass knows about its state.
     *
     * @param conflict      the conflict as detection produced it; its {@link Conflict#getRegion() region}
     *                      is the original one and is never rewritten, so the partition can still be
     *                      checked after the conflict has moved on
     * @param region        the part of the region still open — the original region while
     *                      {@link ConflictState#OPEN}, the remainder when
     *                      {@link ConflictState#PARTIAL}, and the original region again when
     *                      {@link ConflictState#RESOLVED}, whose settled spans cover it entirely
     * @param state         what the pass has decided about this conflict so far
     * @param resolution    the resolution that settled the spans, or {@code null} while nothing has
     * @param settledSpans  the spans of the region a resolution has settled; empty while
     *                      {@link ConflictState#OPEN}, the whole region when
     *                      {@link ConflictState#RESOLVED}, and the settled part when
     *                      {@link ConflictState#PARTIAL}
     */
    public record LiveConflict(Conflict conflict,
                               Region region,
                               ConflictState state,
                               ConflictResolution resolution,
                               List<Region> settledSpans) {

        public LiveConflict {
            Objects.requireNonNull(conflict, "conflict");
            region = region == null ? Region.unknown() : region;
            state = state == null ? ConflictState.OPEN : state;
            settledSpans = settledSpans == null ? List.of() : List.copyOf(settledSpans);
        }

        /**
         * True when a tier is still entitled to be asked about this conflict.
         *
         * <p>A {@link ConflictState#RESOLVED} conflict is not live: it has been removed from the
         * working set, and that is the whole point of the state.
         */
        public boolean isLive() {
            return state != ConflictState.RESOLVED;
        }

        /** True while nothing has settled this conflict. */
        public boolean isOpen() {
            return state == ConflictState.OPEN;
        }

        /**
         * True when every line of this conflict's original region is accounted for by a settled span or
         * by the open remainder — the per-conflict half of {@link #partition()}.
         */
        public boolean accountsForRegion() {
            return droppedLines(conflict.getRegion(), region, settledSpans).isEmpty();
        }
    }

    /**
     * What {@link #partition()} found: every way a block's lines can be lost or claimed twice.
     *
     * <p>Empty lists mean the partition holds. The lists are the defects rather than a boolean, because
     * "which line" is the only useful thing to know about a dropped line, and a bare {@code false} would
     * send the reader back to the spans to find out.
     *
     * @param block            the region of the base version the block covers, or {@link Region#unknown()}
     * @param multiplyResolved lines settled by more than one claim, which would apply two answers to one
     *                         line
     * @param outsideBlock     settled spans that reach outside the block, which would rewrite text the
     *                         block does not own
     * @param unplaced         settled spans with no location at all; an unknown region spans no lines, so
     *                         it can settle nothing and must never be marked as if it had
     * @param dropped          lines of a conflict's original region that are neither settled nor left
     *                         open — the silent loss this check exists for
     */
    public record PartitionReport(Region block,
                                  List<Integer> multiplyResolved,
                                  List<Region> outsideBlock,
                                  List<Region> unplaced,
                                  List<Integer> dropped) {

        public PartitionReport {
            block = block == null ? Region.unknown() : block;
            multiplyResolved = List.copyOf(multiplyResolved);
            outsideBlock = List.copyOf(outsideBlock);
            unplaced = List.copyOf(unplaced);
            dropped = List.copyOf(dropped);
        }

        /** True when no line is lost, settled twice, or settled outside the block. */
        public boolean holds() {
            return multiplyResolved.isEmpty()
                && outsideBlock.isEmpty()
                && unplaced.isEmpty()
                && dropped.isEmpty();
        }

        /**
         * True when the block's region is known, so the parts of the check that need line numbers could
         * run at all.
         *
         * <p>An unknown block is not a failure — a caller that cannot place the block has said so — but
         * it is also not a pass: {@link #holds()} on an unknown block only means the per-conflict half
         * held.
         */
        public boolean checkable() {
            return block.isKnown();
        }

        /** The defects in one line each, for an assertion message or a report. */
        public String describe() {
            if (holds()) {
                return "partition holds (" + block + ")";
            }
            StringBuilder text = new StringBuilder("partition broken (").append(block).append("):");
            if (!dropped.isEmpty()) {
                text.append(" dropped lines ").append(dropped);
            }
            if (!multiplyResolved.isEmpty()) {
                text.append(" settled twice at lines ").append(multiplyResolved);
            }
            if (!outsideBlock.isEmpty()) {
                text.append(" settled outside the block ").append(outsideBlock);
            }
            if (!unplaced.isEmpty()) {
                text.append(" settled without a location ").append(unplaced);
            }
            return text.toString();
        }
    }

    private final Region block;
    private final List<LiveConflict> conflicts;

    private ResolutionPass(Region block, List<LiveConflict> conflicts) {
        this.block = block == null ? Region.unknown() : block;
        this.conflicts = List.copyOf(conflicts);
    }

    /**
     * A pass over the conflicts of one block, every one of them still open.
     *
     * <p>This is the state a block starts in: detection has spoken and no tier has been asked yet.
     */
    public static ResolutionPass of(List<Conflict> detected) {
        return of(Region.unknown(), detected);
    }

    /**
     * A pass over one block, told which region of the base version the block covers.
     *
     * <p>The block region is what makes {@link PartitionReport#outsideBlock()} answerable. A caller that
     * cannot place the block passes {@link Region#unknown()} and the check reports what it can.
     */
    public static ResolutionPass of(Region block, List<Conflict> detected) {
        List<LiveConflict> open = new ArrayList<>();
        if (detected != null) {
            for (Conflict conflict : detected) {
                if (conflict == null) {
                    continue;
                }
                Region region = conflict.getRegion();
                open.add(new LiveConflict(conflict, region, ConflictState.OPEN, null, List.of()));
            }
        }
        return new ResolutionPass(block, open);
    }

    /** The region of the base version this block covers; {@link Region#unknown()} when unplaced. */
    public Region block() {
        return block;
    }

    /** Every conflict detection produced, in detection order. */
    public List<LiveConflict> all() {
        return conflicts;
    }

    /**
     * The conflicts a tier is still entitled to be asked about: those {@link ConflictState#OPEN} or
     * {@link ConflictState#PARTIAL}.
     *
     * <p>A conflict that reached {@link ConflictState#RESOLVED} is absent, and that absence is the whole
     * mechanism — the lower tier is never handed it.
     */
    public List<LiveConflict> live() {
        return conflicts.stream().filter(LiveConflict::isLive).toList();
    }

    /** How many conflicts the block carries, resolved or not. */
    public int size() {
        return conflicts.size();
    }

    /** The state of the conflict at {@code index}, or {@link ConflictState#OPEN} when out of range. */
    public ConflictState stateOf(int index) {
        return index >= 0 && index < conflicts.size()
            ? conflicts.get(index).state()
            : ConflictState.OPEN;
    }

    /**
     * Mark the conflict at {@code index} as wholly settled by {@code resolution}.
     *
     * <p>The settled span is the conflict's own region, which is what makes this the
     * "resolution spans the whole merge conflict region" case of DEC-046 clause 9: the conflict leaves
     * {@link #live()} and no lower tier is offered it.
     *
     * <p>Reliability is not checked here — the caller has already decided that (step 4.19) — and this
     * method therefore does not throw on a conflict whose region is unknown. It records, and
     * {@link #partition()} reports the {@code unplaced} span, because a check that can be skipped by
     * making it impossible to express the defect is not a check.
     */
    public ResolutionPass withResolved(int index, ConflictResolution resolution) {
        LiveConflict current = at(index);
        if (current == null) {
            return this;
        }
        Region whole = current.conflict().getRegion();
        return replace(index, new LiveConflict(current.conflict(), whole, ConflictState.RESOLVED,
            resolution, List.of(whole)));
    }

    /**
     * Mark the conflict at {@code index} as partly settled: {@code settledSpans} are applied and
     * {@code remainder} is what the tiers below are still offered.
     *
     * <p>The caller supplies both halves rather than the remainder alone, so that a pass which loses a
     * line between them is <em>expressible</em> and therefore checkable by {@link #partition()}. That is
     * deliberate: the failure this guards against is a line that neither half carries, and a state that
     * could not represent the mistake could not detect it either.
     */
    public ResolutionPass withPartial(int index, ConflictResolution resolution,
                                      List<Region> settledSpans, Region remainder) {
        LiveConflict current = at(index);
        if (current == null) {
            return this;
        }
        return replace(index, new LiveConflict(current.conflict(), remainder, ConflictState.PARTIAL,
            resolution, settledSpans));
    }

    /**
     * The invariant of the whole design, over this pass: every line of every conflict's region is either
     * settled once or still open, settled spans lie inside the block, and no span is settled without a
     * location.
     */
    public PartitionReport partition() {
        Map<Integer, Integer> owners = new LinkedHashMap<>();
        List<Region> outside = new ArrayList<>();
        List<Region> unplaced = new ArrayList<>();
        List<Integer> dropped = new ArrayList<>();

        for (LiveConflict live : conflicts) {
            for (Region span : live.settledSpans()) {
                if (!span.isKnown()) {
                    unplaced.add(span);
                    continue;
                }
                if (block.isKnown() && !block.covers(span)) {
                    outside.add(span);
                    continue;
                }
                for (int line = span.startLine(); line <= span.endLine(); line++) {
                    owners.merge(line, 1, Integer::sum);
                }
            }
            dropped.addAll(droppedLines(live.conflict().getRegion(), live.region(),
                live.settledSpans()));
        }

        List<Integer> multiply = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : owners.entrySet()) {
            if (entry.getValue() > 1) {
                multiply.add(entry.getKey());
            }
        }
        multiply.sort(null);
        dropped.sort(null);
        return new PartitionReport(block, multiply, outside, unplaced, dropped);
    }

    /**
     * The lines of {@code original} that neither {@code settledSpans} nor {@code remainder} accounts
     * for.
     *
     * <p>A line is accounted for when a settled span contains it or the remainder does. Everything else
     * was dropped between the two.
     */
    static List<Integer> droppedLines(Region original, Region remainder, List<Region> settledSpans) {
        List<Integer> dropped = new ArrayList<>();
        if (original == null || !original.isKnown()) {
            // A conflict with no location has no lines to lose; the unplaced check is the one that
            // speaks about it.
            return dropped;
        }
        for (int line = original.startLine(); line <= original.endLine(); line++) {
            if (containsLine(settledSpans, line) || containsLine(remainder, line)) {
                continue;
            }
            dropped.add(line);
        }
        return dropped;
    }

    /** True when any region in {@code spans} contains {@code line}; unknown regions contain none. */
    private static boolean containsLine(List<Region> spans, int line) {
        if (spans == null) {
            return false;
        }
        for (Region span : spans) {
            if (span != null && span.isKnown()
                && span.startLine() <= line && line <= span.endLine()) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code region} contains {@code line}; an unknown region contains none. */
    private static boolean containsLine(Region region, int line) {
        return region != null && region.isKnown()
            && region.startLine() <= line && line <= region.endLine();
    }

    private LiveConflict at(int index) {
        return index >= 0 && index < conflicts.size() ? conflicts.get(index) : null;
    }

    private ResolutionPass replace(int index, LiveConflict replacement) {
        List<LiveConflict> updated = new ArrayList<>(conflicts);
        updated.set(index, replacement);
        return new ResolutionPass(block, updated);
    }
}
