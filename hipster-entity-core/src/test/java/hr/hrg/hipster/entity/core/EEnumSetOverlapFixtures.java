package hr.hrg.hipster.entity.core;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Random;

/**
 * Shared deterministic fixtures for the agent-service-overlap benchmark
 * ({@link EEnumSetOverlapJmhBenchmark}) and its JUnit parity test
 * ({@link EEnumSetOverlapParityTest}).
 *
 * <p>The model, deliberately POJO-free: a bounded service-id universe represented by an
 * enum (ordinal = registry slot), one session set of ordinals, and many agent sets of
 * ordinals. Every representation below — {@code EEnumSet}, {@code EEnumSetBuilder},
 * {@code EnumSet}, {@code BitSet}, raw longs, and {@code Long} id lists — holds the
 * <strong>same</strong> ordinals, so the benchmark implementations are compared on
 * identical data rather than skewed fixtures.
 *
 * <p>All ordinals are drawn from a seeded {@link Random}, so the fixtures are
 * reproducible across machines and runs.
 */
final class EEnumSetOverlapFixtures {

    /** Service-id universe of width 64 — the single-long EEnumSet shape. */
    enum Service64 {
        S0, S1, S2, S3, S4, S5, S6, S7, S8, S9, S10, S11, S12, S13, S14, S15,
        S16, S17, S18, S19, S20, S21, S22, S23, S24, S25, S26, S27, S28, S29, S30, S31,
        S32, S33, S34, S35, S36, S37, S38, S39, S40, S41, S42, S43, S44, S45, S46, S47,
        S48, S49, S50, S51, S52, S53, S54, S55, S56, S57, S58, S59, S60, S61, S62, S63
    }

    /** Service-id universe of width 96 — the two-segment Large shape. */
    enum Service96 {
        S0, S1, S2, S3, S4, S5, S6, S7, S8, S9, S10, S11, S12, S13, S14, S15,
        S16, S17, S18, S19, S20, S21, S22, S23, S24, S25, S26, S27, S28, S29, S30, S31,
        S32, S33, S34, S35, S36, S37, S38, S39, S40, S41, S42, S43, S44, S45, S46, S47,
        S48, S49, S50, S51, S52, S53, S54, S55, S56, S57, S58, S59, S60, S61, S62, S63,
        S64, S65, S66, S67, S68, S69, S70, S71, S72, S73, S74, S75, S76, S77, S78, S79,
        S80, S81, S82, S83, S84, S85, S86, S87, S88, S89, S90, S91, S92, S93, S94, S95
    }

    /** Service-id universe of width 256 — the four-segment Large shape. */
    enum Service256 {
        S0, S1, S2, S3, S4, S5, S6, S7, S8, S9, S10, S11, S12, S13, S14, S15,
        S16, S17, S18, S19, S20, S21, S22, S23, S24, S25, S26, S27, S28, S29, S30, S31,
        S32, S33, S34, S35, S36, S37, S38, S39, S40, S41, S42, S43, S44, S45, S46, S47,
        S48, S49, S50, S51, S52, S53, S54, S55, S56, S57, S58, S59, S60, S61, S62, S63,
        S64, S65, S66, S67, S68, S69, S70, S71, S72, S73, S74, S75, S76, S77, S78, S79,
        S80, S81, S82, S83, S84, S85, S86, S87, S88, S89, S90, S91, S92, S93, S94, S95,
        S96, S97, S98, S99, S100, S101, S102, S103, S104, S105, S106, S107, S108, S109, S110, S111,
        S112, S113, S114, S115, S116, S117, S118, S119, S120, S121, S122, S123, S124, S125, S126, S127,
        S128, S129, S130, S131, S132, S133, S134, S135, S136, S137, S138, S139, S140, S141, S142, S143,
        S144, S145, S146, S147, S148, S149, S150, S151, S152, S153, S154, S155, S156, S157, S158, S159,
        S160, S161, S162, S163, S164, S165, S166, S167, S168, S169, S170, S171, S172, S173, S174, S175,
        S176, S177, S178, S179, S180, S181, S182, S183, S184, S185, S186, S187, S188, S189, S190, S191,
        S192, S193, S194, S195, S196, S197, S198, S199, S200, S201, S202, S203, S204, S205, S206, S207,
        S208, S209, S210, S211, S212, S213, S214, S215, S216, S217, S218, S219, S220, S221, S222, S223,
        S224, S225, S226, S227, S228, S229, S230, S231, S232, S233, S234, S235, S236, S237, S238, S239,
        S240, S241, S242, S243, S244, S245, S246, S247, S248, S249, S250, S251, S252, S253, S254, S255
    }

    private EEnumSetOverlapFixtures() {
    }

    /**
     * Deterministically pick {@code count} distinct ordinals from {@code 0..universe-1}.
     * The same (universe, count, seed) triple always yields the same ordinals, which is
     * what makes the fixtures reproducible.
     */
    static int[] pickOrdinals(int universe, int count, long seed) {
        if (count >= universe) {
            int[] all = new int[universe];
            for (int i = 0; i < universe; i++) all[i] = i;
            return all;
        }
        Random rnd = new Random(seed);
        boolean[] seen = new boolean[universe];
        int[] out = new int[count];
        int picked = 0;
        while (picked < count) {
            int o = rnd.nextInt(universe);
            if (!seen[o]) {
                seen[o] = true;
                out[picked++] = o;
            }
        }
        return out;
    }

    static <E extends Enum<E>> EEnumSet<E> immutableOf(Class<E> cls, int[] ordinals) {
        EEnumSetBuilder<E> b = EEnumSetBuilder.create(cls);
        for (int o : ordinals) b.addOrdinal(o);
        return b.toImmutable();
    }

    static <E extends Enum<E>> EEnumSetBuilder<E> builderOf(Class<E> cls, int[] ordinals) {
        EEnumSetBuilder<E> b = EEnumSetBuilder.create(cls);
        for (int o : ordinals) b.addOrdinal(o);
        return b;
    }

    static <E extends Enum<E>> EnumSet<E> enumSetOf(Class<E> cls, int[] ordinals) {
        EnumSet<E> s = EnumSet.noneOf(cls);
        E[] constants = cls.getEnumConstants();
        for (int o : ordinals) s.add(constants[o]);
        return s;
    }

    static BitSet bitSetOf(int[] ordinals) {
        BitSet b = new BitSet();
        for (int o : ordinals) b.set(o);
        return b;
    }

    /** One long per 64 ordinals, bit {@code o & 63} of segment {@code o / 64} set. */
    static long[] longsOf(int[] ordinals, int universe) {
        long[] bits = new long[(universe + 63) >>> 6];
        for (int o : ordinals) bits[o >>> 6] |= 1L << (o & 63);
        return bits;
    }

    static List<Long> longIdsOf(int[] ordinals) {
        List<Long> ids = new ArrayList<>(ordinals.length);
        for (int o : ordinals) ids.add((long) o);
        return ids;
    }

    /** Brute-force ground truth: do the two ordinal sets share at least one ordinal? */
    static boolean bruteOverlap(int universe, int[] a, int[] b) {
        boolean[] mark = new boolean[universe];
        for (int o : a) mark[o] = true;
        for (int o : b) if (mark[o]) return true;
        return false;
    }

    /** The production baseline: rebuild a {@code HashSet} per agent, retainAll, test emptiness. */
    static boolean hashOverlap(Set<Long> session, List<Long> agent) {
        Set<Long> copy = new HashSet<>(agent);
        copy.retainAll(session);
        return !copy.isEmpty();
    }

    static <E extends Enum<E>> boolean enumOverlap(EnumSet<E> session, EnumSet<E> agent) {
        for (E e : agent) {
            if (session.contains(e)) return true;
        }
        return false;
    }

    static boolean bitOverlap(BitSet session, BitSet agent) {
        BitSet copy = (BitSet) agent.clone();
        copy.and(session);
        return !copy.isEmpty();
    }

    static void expect(boolean actual, boolean expected, String label) {
        if (actual != expected) {
            throw new IllegalStateException("parity mismatch at " + label + ": expected " + expected + ", got " + actual);
        }
    }
}
