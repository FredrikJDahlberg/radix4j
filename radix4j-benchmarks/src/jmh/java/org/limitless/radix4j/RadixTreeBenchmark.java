package org.limitless.radix4j;

import org.openjdk.jmh.annotations.*;

import java.util.HashSet;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Fork(jvmArgs = "-server", value = 1)
@Warmup(time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, batchSize = 25_000_000)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class RadixTreeBenchmark {

    public static final byte[] STRING = "ABCDEFGHI000000000".getBytes();
    public static final int STRING_LENGTH = STRING.length;

    public static final int SIZE = 25_000_000;
    public static final int MAX = SIZE * STRING_LENGTH;
    public byte[] strings = new byte[SIZE * STRING_LENGTH];

    public RadixTreeBenchmark() {
        int offset = 0;
        for (int i = 0; i < SIZE; ++i) {
            System.arraycopy(STRING, 0, strings, offset, STRING_LENGTH);
            offset += STRING_LENGTH;
            ByteUtils.intToChars(i, offset, STRING_LENGTH, strings);
        }
    }

    public static class BaseState {
        int success;
        int failed;
        int stringOffset;

        void setup() {
            stringOffset = 0;
            failed = 0;
            success = 0;
        }

        void teardown() {
            if (failed >= 1) {
                System.out.println("HashSet: success = " + success + " failed = " + failed);
            }
        }

        boolean counters(final boolean result) {
            stringOffset = (stringOffset + STRING_LENGTH) % MAX;
            if (result) {
                ++success;
            } else {
                ++failed;
            }
            return result;
        }
    }

    //
    // Radix tree
    //
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
    public void teardownEmptyTree(final EmptyTree state){
        state.teardown();
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
    public void teardownFullTree(final FullTree state) {
        state.teardown();
    }

    @Benchmark
    public boolean radixTreeContains(final FullTree state) {
        return state.counters(state.tree.contains(state.stringOffset, STRING_LENGTH, strings));
    }

    @Benchmark
    public boolean radixTreeRemove(final FullTree state) {
        return state.counters(state.tree.remove(state.stringOffset, STRING_LENGTH, strings));
    }

    //
    // Hash set
    //
    @State(Scope.Benchmark)
    public static class HashSetState extends BaseState {
        HashSet<Integer> set;
    }

    @Setup(Level.Iteration)
    public void setupHashSet(HashSetState state) {
        state.set = new HashSet<>(SIZE);
        state.setup();
    }

    @TearDown(Level.Iteration)
    public void teardownHashSet(final HashSetState state) {
        state.teardown();
    }

    @State(Scope.Benchmark)
    public static class FullHashSetState extends BaseState {
        HashSet<Integer> set;
    }

    @Setup(Level.Iteration)
    public void setupFullHashSet(final FullHashSetState state) {
        state.set =  new HashSet<>(SIZE);
        state.setup();;
        for (int offset = 0; offset < strings.length; offset += STRING_LENGTH) {
            final int hashCode = ByteUtils.hashCode(1, strings, offset, STRING_LENGTH);
            state.set.add(hashCode);
        }
    }

    @TearDown(Level.Iteration)
    public void teardownFullHashSet(final FullHashSetState state) {
        state.teardown();
    }

    @Benchmark
    public boolean hashSetAdd(final HashSetState state) {
        final int hashCode = ByteUtils.hashCode(1, strings, state.stringOffset, STRING_LENGTH);
        return state.counters(state.set.add(hashCode));
    }

    @Benchmark
    public boolean hashSetContains(final FullHashSetState state) {
        final int hashCode = ByteUtils.hashCode(1, strings, state.stringOffset, STRING_LENGTH);
        final boolean result = state.set.contains(hashCode);
        state.counters(result);
        return result;
    }

    @Benchmark
    public boolean hashSetRemove(final FullHashSetState state) {
        final int hashCode = ByteUtils.hashCode(1, strings, state.stringOffset, STRING_LENGTH);
        final boolean result = state.set.remove(hashCode);
        state.counters(result);
        return result;
    }
}
