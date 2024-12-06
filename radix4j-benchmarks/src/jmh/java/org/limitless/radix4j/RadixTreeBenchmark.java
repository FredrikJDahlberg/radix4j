package org.limitless.radix4j;

import org.openjdk.jmh.annotations.*;

import java.util.HashSet;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Fork(jvmArgs = "-server", value = 1)
@Warmup(time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(time = 5, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class RadixTreeBenchmark {

    private static final byte[] STRING = "ABCDEFGHI0000000".getBytes();
    private static final int STRING_LENGTH = STRING.length;

    public static final int SIZE = 10_000_000;

    private byte[] strings = new byte[SIZE * STRING_LENGTH];

    public RadixTreeBenchmark() {
        int offset = 0;

        for (int i = 0; i < SIZE; ++i) {
            System.arraycopy(STRING, 0, strings, offset, STRING_LENGTH);
            offset += STRING_LENGTH;
            intToChars(i, offset, 6, strings, 8);
        }
    }

    //
    // Radix tree
    //
    @State(Scope.Benchmark)
    public static class EmptyTree {
        final RadixTree tree = new RadixTree(RadixTree.MAX_BLOCK_COUNT);
        int stringOffset = 0;
    }

    @Setup(Level.Iteration)
    public void setupEmptyTree(EmptyTree state) {
        state.stringOffset = 0;
    }

    @State(Scope.Benchmark)
    public static class FullTree {
        final RadixTree tree = new RadixTree(RadixTree.MAX_BLOCK_COUNT);
        int stringOffset = 0;
    }

    @Benchmark
    public boolean radixTreeAdd(final EmptyTree state) {
        final boolean result = state.tree.add(state.stringOffset, STRING_LENGTH, strings);
        state.stringOffset = (state.stringOffset + STRING_LENGTH) % strings.length;
        return result;
    }

    @Setup(Level.Iteration)
    public void setupFullTree(final FullTree state) {
        state.stringOffset = 0;
        for (int offset = 0; offset < strings.length; offset += STRING_LENGTH) {
            state.tree.add(offset, STRING_LENGTH, strings);
        }
    }

    @Benchmark
    public boolean radixTreeContains(final FullTree state) {
        final boolean result = state.tree.contains(state.stringOffset, STRING_LENGTH, strings);
        state.stringOffset = (state.stringOffset + STRING_LENGTH) % strings.length;
        return result;
    }

    @Benchmark
    public boolean radixTreeRemove(final FullTree state) {
        final boolean result = state.tree.remove(state.stringOffset, STRING_LENGTH, strings);
        state.stringOffset = (state.stringOffset + STRING_LENGTH) % strings.length;
        return result;
    }

    //
    // Hash set
    //
    @State(Scope.Benchmark)
    public static class HashSetState {
        HashSet<Integer> set = new HashSet<>(SIZE);
        int stringOffset = 0;
        int failed = 0;
        int count = 0;
    }

    @Setup(Level.Iteration)
    public void setupHashSet(HashSetState state) {
        state.stringOffset = 0;
        state.failed = 0;
        state.count = 0;
        state.set.clear();
    }

    @TearDown(Level.Iteration)
    public void teardownHashSet(final HashSetState state) {
        if (state.failed >= 1) {
            System.out.println("Failed = " + state.failed);
        }
    }

    @State(Scope.Benchmark)
    public static class FullHashSetState {
        HashSet<Integer> set = new HashSet<>(SIZE);
        int stringOffset = 0;
    }

    @Setup(Level.Iteration)
    public void setupFullHashSet(final FullHashSetState state) {
        state.stringOffset = 0;
        state.set.clear();
        for (int offset = 0; offset < strings.length; offset += STRING_LENGTH) {
            final int hashCode = hashCode(0, strings, offset, STRING_LENGTH);
            state.set.add(hashCode);
        }
    }

    @Benchmark
    public boolean hashSetAdd(final HashSetState state) {
        final int hashCode = hashCode(0, strings, state.stringOffset, STRING_LENGTH);
        state.stringOffset = (state.stringOffset + STRING_LENGTH) % strings.length;
        final boolean success = state.set.add(hashCode);
        if (++state.count < SIZE && !success) {
            ++state.failed;
        }
        return success;
    }

    @Benchmark
    public boolean hashSetContains(final FullHashSetState state) {
        final int hashCode = hashCode(0, strings, state.stringOffset, STRING_LENGTH);
        state.stringOffset = (state.stringOffset + STRING_LENGTH) % strings.length;
        return state.set.contains(hashCode);
    }

    @Benchmark
    public boolean hashSetRemove(final FullHashSetState state) {
        final int hashCode = hashCode(0, strings, state.stringOffset, STRING_LENGTH);
        state.stringOffset = (state.stringOffset + STRING_LENGTH) % strings.length;
        return state.set.remove(hashCode);
    }

    //
    // Helpers
    //
    public static int hashCode(int result, byte[] a, int fromIndex, int length) {
        int end = fromIndex + length;
        for (int i = fromIndex; i < end; i++) {
            result = 31 * result + a[i];
        }
        return result;
    }

    public static void intToChars(int value, int offset, int length, final byte[] bytes, final int suffix) {
        int pos = offset + length - suffix;
        while (value >= 10) {
            final int result = value / 10;
            final int digit = value % 10;
            bytes[--pos] = (byte) ('0' + digit);
            value = result;
        }
        bytes[--pos] = (byte) ('0' + value);
    }
}
