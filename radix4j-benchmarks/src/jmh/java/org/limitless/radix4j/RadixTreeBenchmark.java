package org.limitless.radix4j;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Fork(jvmArgs = "-server", value = 1)
@Warmup(time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, batchSize = 25_000_000)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class RadixTreeBenchmark extends BaseBenchmark {

    @State(Scope.Benchmark)
    public static class EmptyTree extends BaseState {
        RadixTree tree;
    }

    @Setup(Level.Iteration)
    public void setupEmptyTree(final EmptyTree state) {
        state.tree = new RadixTree(RadixTree.MAX_BLOCKS_PER_SEGMENT);
        state.setup();
    }

    @TearDown(Level.Iteration)
    public void tearDownEmptyTree(final EmptyTree state){
        state.tearDown();
    }

    @Benchmark
    public boolean radixTreeAdd(final EmptyTree state) {
        return state.counters(state.tree.add(state.stringOffset, STRING_LENGTH, strings));
    }

    @State(Scope.Benchmark)
    public static class FullTree extends EmptyTree {
    }

    @Setup(Level.Iteration)
    public void setupFullTree(final FullTree state) {
        state.tree = new RadixTree(RadixTree.MAX_BLOCKS_PER_SEGMENT);
        state.setup();
        for (int offset = 0; offset < strings.length; offset += STRING_LENGTH) {
            state.tree.add(offset, STRING_LENGTH, strings);
        }
    }

    @TearDown(Level.Iteration)
    public void tearDownFullTree(final FullTree state) {
        state.tearDown();
    }

    @Benchmark
    public boolean radixTreeContains(final FullTree state) {
        return state.counters(state.tree.contains(state.stringOffset, STRING_LENGTH, strings));
    }

    @Benchmark
    public boolean radixTreeRemove(final FullTree state) {
        return state.counters(state.tree.remove(state.stringOffset, STRING_LENGTH, strings));
    }
}
