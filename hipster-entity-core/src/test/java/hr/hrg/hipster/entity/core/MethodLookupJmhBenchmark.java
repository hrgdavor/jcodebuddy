package hr.hrg.hipster.entity.core;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class MethodLookupJmhBenchmark {

    private static final String[] SMALL_SORTED = {
            "age",
            "departmentName",
            "firstName",
            "id",
            "lastName",
            "metadata"
    };

    private static final char[] SMALL_FIRST_CHARS = {'a', 'd', 'f', 'i', 'l', 'm'};
    private static final int[] SMALL_RANGE_START = {0, 1, 2, 3, 4, 5};
    private static final int[] SMALL_RANGE_END = {1, 2, 3, 4, 5, 6};

    private static final String[] LARGE_SORTED = {
            "age",
            "city",
            "country",
            "createdAt",
            "departmentCode",
            "departmentId",
            "departmentName",
            "email",
            "firstName",
            "fullName",
            "id",
            "lastLogin",
            "lastName",
            "managerId",
            "metadata",
            "middleName",
            "phoneNumber",
            "status",
            "timezone",
            "title",
            "updatedAt",
            "userName",
            "workPhone",
            "zipCode"
    };

    private static final char[] LARGE_FIRST_CHARS = {'a', 'c', 'd', 'e', 'f', 'i', 'l', 'm', 'p', 's', 't', 'u', 'w', 'z'};
    private static final int[] LARGE_RANGE_START = {0, 1, 4, 7, 8, 10, 11, 13, 16, 17, 18, 20, 22, 23};
    private static final int[] LARGE_RANGE_END = {1, 4, 7, 8, 10, 11, 13, 16, 17, 18, 20, 22, 23, 24};

    @State(Scope.Thread)
    public static class SmallLookupState {
        public String hit = "firstName";
        public String miss = "firstNme";
    }

    @State(Scope.Thread)
    public static class LargeLookupState {
        public String hit = "userName";
        public String miss = "usrName";
    }

    @Benchmark
    public int switchSmallHit(SmallLookupState state) {
        return switchLookupSmall(state.hit);
    }

    @Benchmark
    public int switchSmallMiss(SmallLookupState state) {
        return switchLookupSmall(state.miss);
    }

    @Benchmark
    public int binarySmallHit(SmallLookupState state) {
        return binaryLookup(SMALL_SORTED, state.hit);
    }

    @Benchmark
    public int binarySmallMiss(SmallLookupState state) {
        return binaryLookup(SMALL_SORTED, state.miss);
    }

    @Benchmark
    public int firstCharSmallHit(SmallLookupState state) {
        return firstCharLookup(SMALL_SORTED, SMALL_FIRST_CHARS, SMALL_RANGE_START, SMALL_RANGE_END, state.hit);
    }

    @Benchmark
    public int firstCharSmallMiss(SmallLookupState state) {
        return firstCharLookup(SMALL_SORTED, SMALL_FIRST_CHARS, SMALL_RANGE_START, SMALL_RANGE_END, state.miss);
    }

    @Benchmark
    public int switchLargeHit(LargeLookupState state) {
        return switchLookupLarge(state.hit);
    }

    @Benchmark
    public int switchLargeMiss(LargeLookupState state) {
        return switchLookupLarge(state.miss);
    }

    @Benchmark
    public int binaryLargeHit(LargeLookupState state) {
        return binaryLookup(LARGE_SORTED, state.hit);
    }

    @Benchmark
    public int binaryLargeMiss(LargeLookupState state) {
        return binaryLookup(LARGE_SORTED, state.miss);
    }

    @Benchmark
    public int firstCharLargeHit(LargeLookupState state) {
        return firstCharLookup(LARGE_SORTED, LARGE_FIRST_CHARS, LARGE_RANGE_START, LARGE_RANGE_END, state.hit);
    }

    @Benchmark
    public int firstCharLargeMiss(LargeLookupState state) {
        return firstCharLookup(LARGE_SORTED, LARGE_FIRST_CHARS, LARGE_RANGE_START, LARGE_RANGE_END, state.miss);
    }

    private static int binaryLookup(String[] sortedNames, String methodName) {
        if (methodName == null) {
            return -1;
        }
        int idx = Arrays.binarySearch(sortedNames, methodName);
        return idx >= 0 ? idx : -1;
    }

    private static int firstCharLookup(
            String[] sortedNames,
            char[] firstChars,
            int[] rangeStart,
            int[] rangeEnd,
            String methodName
    ) {
        if (methodName == null || methodName.isEmpty()) {
            return -1;
        }
        int firstCharIdx = Arrays.binarySearch(firstChars, methodName.charAt(0));
        if (firstCharIdx < 0) {
            return -1;
        }

        int from = rangeStart[firstCharIdx];
        int to = rangeEnd[firstCharIdx];
        int idx = Arrays.binarySearch(sortedNames, from, to, methodName);
        return idx >= 0 ? idx : -1;
    }

    private static int switchLookupSmall(String methodName) {
        if (methodName == null) {
            return -1;
        }
        return switch (methodName) {
            case "id" -> 3;
            case "firstName" -> 2;
            case "lastName" -> 4;
            case "age" -> 0;
            case "departmentName" -> 1;
            case "metadata" -> 5;
            default -> -1;
        };
    }

    private static int switchLookupLarge(String methodName) {
        if (methodName == null) {
            return -1;
        }
        return switch (methodName) {
            case "age" -> 0;
            case "city" -> 1;
            case "country" -> 2;
            case "createdAt" -> 3;
            case "departmentCode" -> 4;
            case "departmentId" -> 5;
            case "departmentName" -> 6;
            case "email" -> 7;
            case "firstName" -> 8;
            case "fullName" -> 9;
            case "id" -> 10;
            case "lastLogin" -> 11;
            case "lastName" -> 12;
            case "managerId" -> 13;
            case "metadata" -> 14;
            case "middleName" -> 15;
            case "phoneNumber" -> 16;
            case "status" -> 17;
            case "timezone" -> 18;
            case "title" -> 19;
            case "updatedAt" -> 20;
            case "userName" -> 21;
            case "workPhone" -> 22;
            case "zipCode" -> 23;
            default -> -1;
        };
    }
}
