package org.limitless.radix4j;

import org.openjdk.jmh.annotations.*;

import java.util.HashSet;
import java.util.concurrent.TimeUnit;

@Fork(value = 5)
@Warmup(iterations = 5, batchSize = 1_000_000)
@Measurement(iterations = 5, batchSize = 1_000_000)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
public class RadixTreeBenchmark {

    @State(Scope.Benchmark)
    public static class TreeState {
        RadixTree fullTree;
        RadixTree emptyTree;
        String[] strings;
        int count;
        int failed;

        HashSet<String> fullSet;
        HashSet<String> emptySet;

        public TreeState() {
            strings = new String[1_000_000];
            fullTree = new RadixTree(RadixTree.MAX_BLOCK_COUNT);
            emptyTree = new RadixTree(RadixTree.MAX_BLOCK_COUNT);
            fullSet = new HashSet<>(1_000_000);
            emptySet = new HashSet<>(1_000_000);
        }
    }

    @Setup(Level.Iteration)
    public void setup(final TreeState state) {
        state.failed = 0;
        state.count = 0;
        state.fullTree = new RadixTree(RadixTree.MAX_BLOCK_COUNT);
        state.emptyTree = new RadixTree(RadixTree.MAX_BLOCK_COUNT);
        state.emptySet.clear();
        state.fullSet.clear();
        final String prefix = "12345678901234567890";
        for (int i = 0; i < 1_000_000; ++i) {
            state.strings[i] = prefix + i;
            state.fullTree.add(state.strings[i]);
            state.fullSet.add(state.strings[i]);
        }
    }

    @TearDown(Level.Iteration)
    public void tearDown(final TreeState state) {
        if (state.failed >= 1) {
            System.out.println("failed = " + state.failed);
        }
        state.failed = 0;
        state.count = 0;
        state.fullSet.clear();
        state.emptySet.clear();
    }

    @Benchmark
    public String baseline(final TreeState state) {
        return state.strings[state.count++];
    }

    @Benchmark
    public boolean radixTreeAdd(final TreeState state) {
        return state.emptyTree.add(state.strings[state.count++]);
    }

    @Benchmark
    public boolean radixTreeContains(final TreeState state) {
        return state.fullTree.contains(state.strings[state.count++]);
    }

    @Benchmark
    public boolean radixTreeRemove(final TreeState state) {
        return state.fullTree.remove(state.strings[state.count++]);
    }

    @Benchmark
    public boolean hashSetAdd(final TreeState state) {
        final boolean result = state.fullSet.add(state.strings[state.count++]);
        if (!result) {
            ++state.failed;
        }
        return result;
    }

    @Benchmark
    public boolean hashSetContains(final TreeState state) {
        return state.fullSet.contains(state.strings[state.count++]);
    }

    @Benchmark
    public boolean hashSetRemove(final TreeState state) {
        return state.fullSet.remove(state.strings[state.count++]);
    }
}
