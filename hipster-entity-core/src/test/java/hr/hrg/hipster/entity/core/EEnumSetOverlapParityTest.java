package hr.hrg.hipster.entity.core;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JUnit parity gate for the agent-service-overlap model behind
 * {@link EEnumSetOverlapJmhBenchmark}.
 *
 * <p>For universe widths 64 / 96 / 256, densities 0.05 / 0.25 / 0.5 / 0.9, and 25 random
 * session-vs-agent pairs each, {@code EEnumSet.hasAny} is checked against every other
 * representation of the same data: the {@code HashSet}&lt;{@code Long}&gt; baseline
 * (rebuild + {@code retainAll}), {@code EnumSet}, {@code BitSet}, and a raw long AND.
 * If any representation ever disagrees with the brute-force ground truth, the test fails
 * and the benchmark above is meaningless — the benchmark may only be interpreted as valid
 * as long as this test passes.
 */
public class EEnumSetOverlapParityTest {

    private static final long SEED_BASE = 0x5EED_100L;
    private static final int PAIR_COUNT = 25;
    private static final double[] DENSITIES = {0.05, 0.25, 0.5, 0.9};

    @Test
    public void hasAnyMatchesEveryRepresentation() {
        checkWidth(64, EEnumSetOverlapFixtures.Service64.class);
        checkWidth(96, EEnumSetOverlapFixtures.Service96.class);
        checkWidth(256, EEnumSetOverlapFixtures.Service256.class);
    }

    @Test
    public void disjointHalvesNeverOverlap() {
        checkDisjoint(64, EEnumSetOverlapFixtures.Service64.class);
        checkDisjoint(96, EEnumSetOverlapFixtures.Service96.class);
        checkDisjoint(256, EEnumSetOverlapFixtures.Service256.class);
    }

    @Test
    public void emptySetOverlapsNothing() {
        EEnumSet<EEnumSetOverlapFixtures.Service64> empty =
                EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service64.class, new int[0]);
        EEnumSet<EEnumSetOverlapFixtures.Service64> nonEmpty =
                EEnumSetOverlapFixtures.immutableOf(EEnumSetOverlapFixtures.Service64.class, new int[]{7});

        assertFalse(nonEmpty.hasAny(empty));
        assertFalse(empty.hasAny(nonEmpty));
        // hasAll is vacuous for the empty other: every element of {} is contained in any set.
        assertTrue(nonEmpty.hasAll(empty));
        assertFalse(empty.hasAll(nonEmpty));
    }

    private static <E extends Enum<E>> void checkWidth(int universe, Class<E> enumClass) {
        for (double density : DENSITIES) {
            int setSize = Math.max(1, (int) Math.floor(density * universe));
            for (int pair = 0; pair < PAIR_COUNT; pair++) {
                long pairSeed = SEED_BASE + universe * 1000L + (int) (density * 100) * PAIR_COUNT + pair;
                int[] a = EEnumSetOverlapFixtures.pickOrdinals(universe, setSize, pairSeed);
                int[] b = EEnumSetOverlapFixtures.pickOrdinals(universe, setSize, pairSeed + 0x10_0000L);

                boolean truth = EEnumSetOverlapFixtures.bruteOverlap(universe, a, b);

                EEnumSet<E> ea = EEnumSetOverlapFixtures.immutableOf(enumClass, a);
                EEnumSet<E> eb = EEnumSetOverlapFixtures.immutableOf(enumClass, b);
                check(ea.hasAny(eb), truth, universe, density, pair, "EEnumSet");

                List<Long> la = EEnumSetOverlapFixtures.longIdsOf(a);
                List<Long> lb = EEnumSetOverlapFixtures.longIdsOf(b);
                check(EEnumSetOverlapFixtures.hashOverlap(new HashSet<>(la), lb), truth, universe, density, pair, "HashSet");

                EnumSet<E> sa = EEnumSetOverlapFixtures.enumSetOf(enumClass, a);
                EnumSet<E> sb = EEnumSetOverlapFixtures.enumSetOf(enumClass, b);
                check(EEnumSetOverlapFixtures.enumOverlap(sa, sb), truth, universe, density, pair, "EnumSet");

                check(EEnumSetOverlapFixtures.bitOverlap(EEnumSetOverlapFixtures.bitSetOf(a), EEnumSetOverlapFixtures.bitSetOf(b)),
                        truth, universe, density, pair, "BitSet");

                long[] bitsA = EEnumSetOverlapFixtures.longsOf(a, universe);
                long[] bitsB = EEnumSetOverlapFixtures.longsOf(b, universe);
                boolean raw = false;
                for (int i = 0; i < bitsA.length; i++) raw |= (bitsA[i] & bitsB[i]) != 0;
                check(raw, truth, universe, density, pair, "raw long");
            }
        }
    }

    private static <E extends Enum<E>> void checkDisjoint(int universe, Class<E> enumClass) {
        int half = universe / 2;
        int[] lower = new int[half];
        int[] upper = new int[half];
        for (int i = 0; i < half; i++) {
            lower[i] = i;
            upper[i] = half + i;
        }
        EEnumSet<E> eLower = EEnumSetOverlapFixtures.immutableOf(enumClass, lower);
        EEnumSet<E> eUpper = EEnumSetOverlapFixtures.immutableOf(enumClass, upper);

        assertFalse(eLower.hasAny(eUpper));
        assertFalse(eUpper.hasAny(eLower));
        assertFalse(EEnumSetOverlapFixtures.hashOverlap(
                new HashSet<>(EEnumSetOverlapFixtures.longIdsOf(lower)),
                EEnumSetOverlapFixtures.longIdsOf(upper)));
    }

    private static void check(boolean actual, boolean truth, int universe, double density, int pair, String label) {
        if (actual != truth) {
            throw new AssertionError(
                    "parity mismatch: " + label + " (universe " + universe + ", density " + density + ", pair " + pair + "): expected " + truth + ", got " + actual);
        }
    }
}
