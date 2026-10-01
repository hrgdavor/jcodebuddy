package hr.hrg.hipster.entity.core;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for the agent-service-overlap use case: a session carries a set of
 * service ids, many agents each carry a set of service ids, and the question per agent
 * is "does this agent share at least one service with the session?" — the overlap
 * primitive is {@link EEnumSetRead#hasAny(EEnumSetRead)}.
 *
 * <p>Modelling: the service-id universe is bounded and known (an enum; ordinal = registry
 * slot), which is the precondition under which {@code EEnumSet} is a sensible
 * representation. Every implementation below — {@code EEnumSet}, {@code EEnumSetBuilder},
 * {@code HashSet} of {@code Long} ids (the production baseline: rebuild + {@code retainAll}
 * per agent), {@code BitSet}, and raw longs — holds the <em>same</em> deterministic
 * ordinals (see {@link EEnumSetOverlapFixtures}), so the comparison is between the
 * implementations, not the fixtures. The {@code Long}-id baselines are kept precisely so
 * the relative-cost story holds whether or not the bounded-universe assumption is met in a
 * given deployment.
 *
 * <p>Each state builds its fixtures in {@code @Setup} and then asserts a parity guard:
 * every representation must agree with the brute-force ground truth for the session and
 * every agent. If a representation ever diverges, the benchmark fails loudly in setup
 * instead of silently benchmarking divergent logic.
 *
 * <p>Run with the repository runner, which now compiles {@code hipster-entity-core} with
 * the {@code jmh} profile (JDK 25, {@code -proc:full} for the annotation processor):
 * <pre>
 *   bun run scripts/run-jmh.js --include ".*EEnumSetOverlapJmhBenchmark.*" \
 *     -p density=0.05,0.25,0.5,0.9 -p agents=100,1000
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class EEnumSetOverlapJmhBenchmark {

    /**
     * Width-64 fixture: one session, 16 agents, plus a guaranteed-disjoint pair
     * (session = lower half of the universe, agent = upper half) for the worst case.
     */
    @State(Scope.Thread)
    public static class Overlap64State {
        static final int UNIVERSE = 64;
        static final int AGENT_COUNT = 16;

        @Param("density")
        public String density = "0.5";

        private int[] sessionOrdinals;
        private EEnumSet<EEnumSetOverlapFixtures.Service64> session;
        private EEnumSet<EEnumSetOverlapFixtures.Service64>[] agentSets;
        private EEnumSetBuilder<EEnumSetOverlapFixtures.Service64>[] agentBuilders;
        private List<Long>[] agentIds;
        private Set<Long> sessionIds;
        private BitSet sessionBitSet;
        private BitSet[] agentBitSets;
        private long sessionBits;
        private long[] agentBits;
        private EEnumSet<EEnumSetOverlapFixtures.Service64> disjointSession;
        private EEnumSet<EEnumSetOverlapFixtures.Service64> disjointAgent;
        private Set<Long> disjointSessionIds;
        private List<Long> disjointAgentIds;

        @SuppressWarnings("unchecked")
        @Setup
        public void setup() {
            int setSize = Math.max(1, (int) Math.floor(Double.parseDouble(density) * UNIVERSE));

            sessionOrdinals = EEnumSetOverlapFixtures.pickOrdinals(UNIVERSE, setSize, 0x5EED_00L);
            int[][] agentOrdinals = new int[AGENT_COUNT][];
            for (int i = 0; i < AGENT_COUNT; i++) {
                agentOrdinals[i] = EEnumSetOverlapFixtures.pickOrdinals(UNIVERSE, setSize, 0x5EED_10L + i);
            }

            session = EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service64.class, sessionOrdinals);
            agentSets = new EEnumSet[AGENT_COUNT];
            agentBuilders = new EEnumSetBuilder[AGENT_COUNT];
            agentIds = new List[AGENT_COUNT];
            agentBitSets = new BitSet[AGENT_COUNT];
            agentBits = new long[AGENT_COUNT];
            for (int i = 0; i < AGENT_COUNT; i++) {
                agentSets[i] = EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service64.class, agentOrdinals[i]);
                agentBuilders[i] = EEnumSetOverlapFixtures.builderOf(EEnumSetOverlapFixtures.Service64.class, agentOrdinals[i]);
                agentIds[i] = EEnumSetOverlapFixtures.longIdsOf(agentOrdinals[i]);
                agentBitSets[i] = EEnumSetOverlapFixtures.bitSetOf(agentOrdinals[i]);
                agentBits[i] = EEnumSetOverlapFixtures.longsOf(agentOrdinals[i], UNIVERSE)[0];
            }
            sessionIds = new HashSet<>(EEnumSetOverlapFixtures.longIdsOf(sessionOrdinals));
            sessionBitSet = EEnumSetOverlapFixtures.bitSetOf(sessionOrdinals);
            sessionBits = EEnumSetOverlapFixtures.longsOf(sessionOrdinals, UNIVERSE)[0];

            int half = UNIVERSE / 2;
            int[] lower = new int[half];
            int[] upper = new int[half];
            for (int i = 0; i < half; i++) {
                lower[i] = i;
                upper[i] = half + i;
            }
            disjointSession = EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service64.class, lower);
            disjointAgent = EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service64.class, upper);
            disjointSessionIds = new HashSet<>(EEnumSetOverlapFixtures.longIdsOf(lower));
            disjointAgentIds = EEnumSetOverlapFixtures.longIdsOf(upper);

            parityGuard(sessionOrdinals, agentOrdinals, session, agentSets, agentBuilders, agentIds, sessionIds, sessionBitSet, agentBitSets, sessionBits, agentBits);
            EEnumSetOverlapFixtures.expect(disjointSession.hasAny(disjointAgent), false, "disjoint pair (EEnumSet)");
            EEnumSetOverlapFixtures.expect(EEnumSetOverlapFixtures.hashOverlap(disjointSessionIds, disjointAgentIds), false, "disjoint pair (HashSet)");
        }
    }

    /** Width-96 fixture: session + 16 agents, no disjoint pair (worst case is covered at 64 and 256). */
    @State(Scope.Thread)
    public static class Overlap96State {
        static final int UNIVERSE = 96;
        static final int AGENT_COUNT = 16;

        @Param("density")
        public String density = "0.5";

        private int[] sessionOrdinals;
        private EEnumSet<EEnumSetOverlapFixtures.Service96> session;
        private EEnumSet<EEnumSetOverlapFixtures.Service96>[] agentSets;
        private List<Long>[] agentIds;
        private Set<Long> sessionIds;
        private BitSet sessionBitSet;
        private BitSet[] agentBitSets;

        @SuppressWarnings("unchecked")
        @Setup
        public void setup() {
            int setSize = Math.max(1, (int) Math.floor(Double.parseDouble(density) * UNIVERSE));

            sessionOrdinals = EEnumSetOverlapFixtures.pickOrdinals(UNIVERSE, setSize, 0x5EED_00L);
            int[][] agentOrdinals = new int[AGENT_COUNT][];
            for (int i = 0; i < AGENT_COUNT; i++) {
                agentOrdinals[i] = EEnumSetOverlapFixtures.pickOrdinals(UNIVERSE, setSize, 0x5EED_10L + i);
            }

            session = EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service96.class, sessionOrdinals);
            agentSets = new EEnumSet[AGENT_COUNT];
            agentIds = new List[AGENT_COUNT];
            agentBitSets = new BitSet[AGENT_COUNT];
            for (int i = 0; i < AGENT_COUNT; i++) {
                agentSets[i] = EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service96.class, agentOrdinals[i]);
                agentIds[i] = EEnumSetOverlapFixtures.longIdsOf(agentOrdinals[i]);
                agentBitSets[i] = EEnumSetOverlapFixtures.bitSetOf(agentOrdinals[i]);
            }
            sessionIds = new HashSet<>(EEnumSetOverlapFixtures.longIdsOf(sessionOrdinals));
            sessionBitSet = EEnumSetOverlapFixtures.bitSetOf(sessionOrdinals);

            parityGuard96(sessionOrdinals, agentOrdinals, session, agentSets, agentIds, sessionIds, sessionBitSet, agentBitSets);
        }
    }

    /** Width-256 fixture: session + 16 agents + guaranteed-disjoint pair (worst case at width). */
    @State(Scope.Thread)
    public static class Overlap256State {
        static final int UNIVERSE = 256;
        static final int AGENT_COUNT = 16;

        @Param("density")
        public String density = "0.5";

        private int[] sessionOrdinals;
        private EEnumSet<EEnumSetOverlapFixtures.Service256> session;
        private EEnumSet<EEnumSetOverlapFixtures.Service256>[] agentSets;
        private EEnumSetBuilder<EEnumSetOverlapFixtures.Service256>[] agentBuilders;
        private List<Long>[] agentIds;
        private Set<Long> sessionIds;
        private BitSet sessionBitSet;
        private BitSet[] agentBitSets;
        private EEnumSet<EEnumSetOverlapFixtures.Service256> disjointSession;
        private EEnumSet<EEnumSetOverlapFixtures.Service256> disjointAgent;
        private Set<Long> disjointSessionIds;
        private List<Long> disjointAgentIds;

        @SuppressWarnings("unchecked")
        @Setup
        public void setup() {
            int setSize = Math.max(1, (int) Math.floor(Double.parseDouble(density) * UNIVERSE));

            sessionOrdinals = EEnumSetOverlapFixtures.pickOrdinals(UNIVERSE, setSize, 0x5EED_00L);
            int[][] agentOrdinals = new int[AGENT_COUNT][];
            for (int i = 0; i < AGENT_COUNT; i++) {
                agentOrdinals[i] = EEnumSetOverlapFixtures.pickOrdinals(UNIVERSE, setSize, 0x5EED_10L + i);
            }

            session = EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service256.class, sessionOrdinals);
            agentSets = new EEnumSet[AGENT_COUNT];
            agentBuilders = new EEnumSetBuilder[AGENT_COUNT];
            agentIds = new List[AGENT_COUNT];
            agentBitSets = new BitSet[AGENT_COUNT];
            for (int i = 0; i < AGENT_COUNT; i++) {
                agentSets[i] = EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service256.class, agentOrdinals[i]);
                agentBuilders[i] = EEnumSetOverlapFixtures.builderOf(EEnumSetOverlapFixtures.Service256.class, agentOrdinals[i]);
                agentIds[i] = EEnumSetOverlapFixtures.longIdsOf(agentOrdinals[i]);
                agentBitSets[i] = EEnumSetOverlapFixtures.bitSetOf(agentOrdinals[i]);
            }
            sessionIds = new HashSet<>(EEnumSetOverlapFixtures.longIdsOf(sessionOrdinals));
            sessionBitSet = EEnumSetOverlapFixtures.bitSetOf(sessionOrdinals);

            int half = UNIVERSE / 2;
            int[] lower = new int[half];
            int[] upper = new int[half];
            for (int i = 0; i < half; i++) {
                lower[i] = i;
                upper[i] = half + i;
            }
            disjointSession = EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service256.class, lower);
            disjointAgent = EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service256.class, upper);
            disjointSessionIds = new HashSet<>(EEnumSetOverlapFixtures.longIdsOf(lower));
            disjointAgentIds = EEnumSetOverlapFixtures.longIdsOf(upper);

            parityGuard256(sessionOrdinals, agentOrdinals, session, agentSets, agentBuilders, agentIds, sessionIds, sessionBitSet, agentBitSets);
            EEnumSetOverlapFixtures.expect(disjointSession.hasAny(disjointAgent), false, "disjoint pair (EEnumSet)");
            EEnumSetOverlapFixtures.expect(EEnumSetOverlapFixtures.hashOverlap(disjointSessionIds, disjointAgentIds), false, "disjoint pair (HashSet)");
        }
    }

    /**
     * Batch fixture, width 64: one session and {@code agents} agents. Models the realistic
     * workload — filtering many agents by session overlap — at a batch size the per-agent
     * fixture (16 agents) cannot reach.
     */
    @State(Scope.Thread)
    public static class OverlapBatch64State {
        static final int UNIVERSE = 64;
        static final int PARITY_CHECK_COUNT = 16;

        @Param("density")
        public String density = "0.5";

        @Param("agents")
        public String agents = "100";

        private int[] sessionOrdinals;
        private EEnumSet<EEnumSetOverlapFixtures.Service64> session;
        private EEnumSet<EEnumSetOverlapFixtures.Service64>[] agentSets;
        private List<Long>[] agentIds;
        private Set<Long> sessionIds;

        @SuppressWarnings("unchecked")
        @Setup
        public void setup() {
            int agentCount = Integer.parseInt(agents);
            int setSize = Math.max(1, (int) Math.floor(Double.parseDouble(density) * UNIVERSE));

            sessionOrdinals = EEnumSetOverlapFixtures.pickOrdinals(UNIVERSE, setSize, 0x5EED_00L);
            int[][] agentOrdinals = new int[agentCount][];
            for (int i = 0; i < agentCount; i++) {
                agentOrdinals[i] = EEnumSetOverlapFixtures.pickOrdinals(UNIVERSE, setSize, 0x5EED_10L + i);
            }

            session = EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service64.class, sessionOrdinals);
            sessionIds = new HashSet<>(EEnumSetOverlapFixtures.longIdsOf(sessionOrdinals));
            agentSets = new EEnumSet[agentCount];
            agentIds = new List[agentCount];
            for (int i = 0; i < agentCount; i++) {
                agentSets[i] = EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service64.class, agentOrdinals[i]);
                agentIds[i] = EEnumSetOverlapFixtures.longIdsOf(agentOrdinals[i]);
            }

            // Parity guard on a sample of agents; the rest share the same generation.
            for (int i = 0; i < Math.min(PARITY_CHECK_COUNT, agentCount); i++) {
                boolean truth = EEnumSetOverlapFixtures.bruteOverlap(UNIVERSE, sessionOrdinals, agentOrdinals[i]);
                EEnumSetOverlapFixtures.expect(session.hasAny(agentSets[i]), truth, "batch EEnumSet, agent " + i);
                EEnumSetOverlapFixtures.expect(EEnumSetOverlapFixtures.hashOverlap(sessionIds, agentIds[i]), truth, "batch HashSet, agent " + i);
            }
        }
    }

    /**
     * Parity guard: every representation of the same ordinals must return the same
     * overlap verdict as the brute-force ground truth.
     */
    private static void parityGuard(
            int[] sessionOrdinals,
            int[][] agentOrdinals,
            EEnumSet<EEnumSetOverlapFixtures.Service64> session,
            EEnumSet<EEnumSetOverlapFixtures.Service64>[] agentSets,
            EEnumSetBuilder<EEnumSetOverlapFixtures.Service64>[] agentBuilders,
            List<Long>[] agentIds,
            Set<Long> sessionIds,
            BitSet sessionBitSet,
            BitSet[] agentBitSets,
            long sessionBits,
            long[] agentBits) {
        for (int i = 0; i < agentOrdinals.length; i++) {
            boolean truth = EEnumSetOverlapFixtures.bruteOverlap(Overlap64State.UNIVERSE, sessionOrdinals, agentOrdinals[i]);
            EEnumSetOverlapFixtures.expect(session.hasAny(agentSets[i]), truth, "EEnumSet immutable, agent " + i);
            EEnumSetOverlapFixtures.expect(session.hasAny(agentBuilders[i]), truth, "EEnumSet builder, agent " + i);
            EEnumSetOverlapFixtures.expect(EEnumSetOverlapFixtures.hashOverlap(sessionIds, agentIds[i]), truth, "HashSet, agent " + i);
            EEnumSetOverlapFixtures.expect(EEnumSetOverlapFixtures.bitOverlap(sessionBitSet, agentBitSets[i]), truth, "BitSet, agent " + i);
            EEnumSetOverlapFixtures.expect((sessionBits & agentBits[i]) != 0, truth, "raw long, agent " + i);
        }
    }

    private static void parityGuard96(
            int[] sessionOrdinals,
            int[][] agentOrdinals,
            EEnumSet<EEnumSetOverlapFixtures.Service96> session,
            EEnumSet<EEnumSetOverlapFixtures.Service96>[] agentSets,
            List<Long>[] agentIds,
            Set<Long> sessionIds,
            BitSet sessionBitSet,
            BitSet[] agentBitSets) {
        for (int i = 0; i < agentOrdinals.length; i++) {
            boolean truth = EEnumSetOverlapFixtures.bruteOverlap(Overlap96State.UNIVERSE, sessionOrdinals, agentOrdinals[i]);
            EEnumSetOverlapFixtures.expect(session.hasAny(agentSets[i]), truth, "EEnumSet, agent " + i);
            EEnumSetOverlapFixtures.expect(EEnumSetOverlapFixtures.hashOverlap(sessionIds, agentIds[i]), truth, "HashSet, agent " + i);
            EEnumSetOverlapFixtures.expect(EEnumSetOverlapFixtures.bitOverlap(sessionBitSet, agentBitSets[i]), truth, "BitSet, agent " + i);
        }
    }

    private static void parityGuard256(
            int[] sessionOrdinals,
            int[][] agentOrdinals,
            EEnumSet<EEnumSetOverlapFixtures.Service256> session,
            EEnumSet<EEnumSetOverlapFixtures.Service256>[] agentSets,
            EEnumSetBuilder<EEnumSetOverlapFixtures.Service256>[] agentBuilders,
            List<Long>[] agentIds,
            Set<Long> sessionIds,
            BitSet sessionBitSet,
            BitSet[] agentBitSets) {
        for (int i = 0; i < agentOrdinals.length; i++) {
            boolean truth = EEnumSetOverlapFixtures.bruteOverlap(Overlap256State.UNIVERSE, sessionOrdinals, agentOrdinals[i]);
            EEnumSetOverlapFixtures.expect(session.hasAny(agentSets[i]), truth, "EEnumSet, agent " + i);
            EEnumSetOverlapFixtures.expect(session.hasAny(agentBuilders[i]), truth, "EEnumSet builder, agent " + i);
            EEnumSetOverlapFixtures.expect(EEnumSetOverlapFixtures.hashOverlap(sessionIds, agentIds[i]), truth, "HashSet, agent " + i);
            EEnumSetOverlapFixtures.expect(EEnumSetOverlapFixtures.bitOverlap(sessionBitSet, agentBitSets[i]), truth, "BitSet, agent " + i);
        }
    }

    // ------------------------------------------------------------------
    // Width 64 — single long; the canonical small case
    // ------------------------------------------------------------------

    /** {@code EEnumSet} immutable: one word AND plus a branch on the fast path. */
    @Benchmark
    public boolean overlapEEnumSet64(Overlap64State s) {
        return s.session.hasAny(s.agentSets[0]);
    }

    /** {@code EEnumSetBuilder} as the second operand: the DEC-012 tracking-state shape. */
    @Benchmark
    public boolean overlapEEnumSetBuilder64(Overlap64State s) {
        return s.session.hasAny(s.agentBuilders[0]);
    }

    /** Production baseline: per-agent {@code HashSet} rebuild + {@code retainAll}. */
    @Benchmark
    public boolean overlapHashSet64(Overlap64State s) {
        Set<Long> copy = new HashSet<>(s.agentIds[0]);
        copy.retainAll(s.sessionIds);
        return !copy.isEmpty();
    }

    /** Raw-long floor: what one word AND costs with no abstraction at all. */
    @Benchmark
    public boolean overlapRawLong64(Overlap64State s) {
        return (s.sessionBits & s.agentBits[0]) != 0;
    }

    /** Worst case: guaranteed-disjoint pair, {@code EEnumSet}. */
    @Benchmark
    public boolean disjointEEnumSet64(Overlap64State s) {
        return s.disjointSession.hasAny(s.disjointAgent);
    }

    /** Worst case: guaranteed-disjoint pair, production baseline. */
    @Benchmark
    public boolean disjointHashSet64(Overlap64State s) {
        Set<Long> copy = new HashSet<>(s.disjointAgentIds);
        copy.retainAll(s.disjointSessionIds);
        return !copy.isEmpty();
    }

    // ------------------------------------------------------------------
    // Width 96 — two segments
    // ------------------------------------------------------------------

    @Benchmark
    public boolean overlapEEnumSet96(Overlap96State s) {
        return s.session.hasAny(s.agentSets[0]);
    }

    @Benchmark
    public boolean overlapHashSet96(Overlap96State s) {
        Set<Long> copy = new HashSet<>(s.agentIds[0]);
        copy.retainAll(s.sessionIds);
        return !copy.isEmpty();
    }

    @Benchmark
    public boolean overlapBitSet96(Overlap96State s) {
        BitSet copy = (BitSet) s.agentBitSets[0].clone();
        copy.and(s.sessionBitSet);
        return !copy.isEmpty();
    }

    // ------------------------------------------------------------------
    // Width 256 — four segments
    // ------------------------------------------------------------------

    @Benchmark
    public boolean overlapEEnumSet256(Overlap256State s) {
        return s.session.hasAny(s.agentSets[0]);
    }

    @Benchmark
    public boolean overlapEEnumSetBuilder256(Overlap256State s) {
        return s.session.hasAny(s.agentBuilders[0]);
    }

    @Benchmark
    public boolean overlapHashSet256(Overlap256State s) {
        Set<Long> copy = new HashSet<>(s.agentIds[0]);
        copy.retainAll(s.sessionIds);
        return !copy.isEmpty();
    }

    @Benchmark
    public boolean overlapBitSet256(Overlap256State s) {
        BitSet copy = (BitSet) s.agentBitSets[0].clone();
        copy.and(s.sessionBitSet);
        return !copy.isEmpty();
    }

    /** Worst case at width: guaranteed-disjoint pair, {@code EEnumSet}. */
    @Benchmark
    public boolean disjointEEnumSet256(Overlap256State s) {
        return s.disjointSession.hasAny(s.disjointAgent);
    }

    /** Worst case at width: guaranteed-disjoint pair, production baseline. */
    @Benchmark
    public boolean disjointHashSet256(Overlap256State s) {
        Set<Long> copy = new HashSet<>(s.disjointAgentIds);
        copy.retainAll(s.disjointSessionIds);
        return !copy.isEmpty();
    }

    // ------------------------------------------------------------------
    // Batch — filter many agents by session overlap, width 64
    // ------------------------------------------------------------------

    /** EEnumSet scan: one {@code hasAny} per agent. */
    @Benchmark
    public int filterAgents64(OverlapBatch64State s) {
        int count = 0;
        for (EEnumSet<EEnumSetOverlapFixtures.Service64> agent : s.agentSets) {
            if (s.session.hasAny(agent)) count++;
        }
        return count;
    }

    /** Production baseline scan: per-agent {@code HashSet} rebuild + {@code retainAll}. */
    @Benchmark
    public int filterAgentsHashSet64(OverlapBatch64State s) {
        int count = 0;
        for (List<Long> ids : s.agentIds) {
            Set<Long> copy = new HashSet<>(ids);
            copy.retainAll(s.sessionIds);
            if (!copy.isEmpty()) count++;
        }
        return count;
    }
}
