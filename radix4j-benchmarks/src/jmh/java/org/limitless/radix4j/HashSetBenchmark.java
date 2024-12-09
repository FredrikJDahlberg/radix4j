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
public class HashSetBenchmark extends BaseBenchmark {

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
    public void tearDownHashSet(final HashSetState state) {
        state.tearDown();
    }

    @State(Scope.Benchmark)
    public static class FullHashSetState extends BaseState {
        HashSet<Integer> set;
    }

    @Setup(Level.Iteration)
    public void setupFullHashSet(final FullHashSetState state) {
        state.set =  new HashSet<>(SIZE);
        state.setup();
        for (int offset = 0; offset < strings.length; offset += STRING_LENGTH) {
            final int hashCode = ByteUtils.hashCode(1, strings, offset, STRING_LENGTH);
            state.set.add(hashCode);
        }
    }

    @TearDown(Level.Iteration)
    public void tearDownFullHashSet(final FullHashSetState state) {
        state.tearDown();
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
