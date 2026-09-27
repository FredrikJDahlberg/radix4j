package org.limitless.radix4j;

import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

/**
 * Baseline for {@link RadixTreeBenchmark}: the same strings in a HashSet&lt;String&gt; (backed by a HashMap).
 * Every operation creates the String from the byte array, like a caller holding the bytes would.
 */
@State(Scope.Thread)
@Fork(jvmArgs = "-server", value = 1)
@Warmup(time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, batchSize = 25_000_000)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class HashMapBenchmark extends BaseBenchmark {

    @State(Scope.Benchmark)
    public static class HashMapState extends BaseState {
        HashSet<String> set;
    }

    @Setup(Level.Iteration)
    public void setupHashSet(HashMapState state) {
        state.set = new HashSet<>(SIZE);
        state.setup();
    }

    @TearDown(Level.Iteration)
    public void tearDownHashSet(final HashMapState state) {
        state.tearDown();
    }

    @State(Scope.Benchmark)
    public static class FullHashMapState extends BaseState {
        HashSet<String> set;
    }

    @Setup(Level.Iteration)
    public void setupFullHashMap(final FullHashMapState state) {
        state.set = new HashSet<>(SIZE);
        state.setup();
        for (int i = 0; i < SIZE; ++i) {
            state.set.add(string(i));
        }
    }

    @TearDown(Level.Iteration)
    public void tearDownFullHashSet(final FullHashMapState state) {
        state.tearDown();
    }

    @Benchmark
    public boolean hashMapAdd(final HashMapState state) {
        return state.updateStats(state.set.add(string(state.index)));
    }

    @Benchmark
    public boolean hashMapContains(final FullHashMapState state) {
        return state.updateStats(state.set.contains(string(state.index)));
    }

    @Benchmark
    public boolean hashMapRemove(final FullHashMapState state) {
        return state.updateStats(state.set.remove(string(state.index)));
    }

    @Benchmark
    @Measurement(iterations = 5, batchSize = 1)
    @BenchmarkMode(Mode.SingleShotTime)
    public int hashMapForEach(final FullHashMapState state) {
        final int[] result = {0};
        state.set.forEach(_ -> ++result[0]);
        return result[0];
    }

    @Benchmark
    @Measurement(iterations = 5, batchSize = 1)
    @BenchmarkMode(Mode.SingleShotTime)
    public int hashMapPrefixForEach(final FullHashMapState state) {
        final int[] result = {0};
        state.set.forEach(string -> {
            if (string.startsWith(prefixString)) {
                ++result[0];
            }
        });
        return result[0];
    }

    @Benchmark
    @Measurement(iterations = 5, batchSize = 1)
    @BenchmarkMode(Mode.SingleShotTime)
    public int hashMapPrefixRemove(final FullHashMapState state) {
        state.set.removeIf(string -> string.startsWith(prefixString));
        return state.set.size();
    }

    private static String string(final int index) {
        return new String(strings, offsets[index], length(index), StandardCharsets.ISO_8859_1);
    }
}
