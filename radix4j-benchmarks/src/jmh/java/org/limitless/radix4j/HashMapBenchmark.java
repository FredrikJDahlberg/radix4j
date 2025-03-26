package org.limitless.radix4j;

import org.openjdk.jmh.annotations.*;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Fork(jvmArgs = "-server", value = 1)
@Warmup(time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, batchSize = 25_000_000)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class HashMapBenchmark extends BaseBenchmark {

    @State(Scope.Benchmark)
    public static class HashMapState extends BaseState {
        HashMap<Integer,Integer> map;
        int count;
    }

    @Setup(Level.Iteration)
    public void setupHashSet(HashMapState state) {
        state.map = new HashMap<>(SIZE);
        state.count = 0;
        state.setup();
    }

    @TearDown(Level.Iteration)
    public void tearDownHashSet(final HashMapState state) {
        state.tearDown();
    }

    @State(Scope.Benchmark)
    public static class FullHashMapState extends BaseState {
        HashMap<Integer,Integer> map;
    }

    @Setup(Level.Iteration)
    public void setupFullHashMap(final FullHashMapState state) {
        state.map =  new HashMap<>(SIZE);
        state.setup();
        for (int offset = 0; offset < strings.length; offset += STRING_LENGTH) {
            final int hashCode = ByteUtils.hashCode(1, strings, offset, STRING_LENGTH);
            final int bucket = offset / STRING_LENGTH / 100;
            state.map.put(hashCode, bucket);
        }
    }

    @TearDown(Level.Iteration)
    public void tearDownFullHashSet(final FullHashMapState state) {
        state.tearDown();
    }

    @Benchmark
    public boolean hashMapAdd(final HashMapState state) {
        final int hashCode = ByteUtils.hashCode(1, strings, state.stringOffset, STRING_LENGTH);
        return state.updateStats(state.map.put(hashCode, ++state.count / 100) == null);
    }

    @Benchmark
    public boolean hashMapContains(final FullHashMapState state) {
        final int hashCode = ByteUtils.hashCode(1, strings, state.stringOffset, STRING_LENGTH);
        final boolean result = state.map.containsKey(hashCode);
        return state.updateStats(result);
    }

    @Benchmark
    public boolean hashMapRemove(final FullHashMapState state) {
        final int hashCode = ByteUtils.hashCode(1, strings, state.stringOffset, STRING_LENGTH);
        final boolean result = state.map.remove(hashCode) != null;
        return state.updateStats(result);
    }

    @Benchmark
    @Measurement(iterations = 5, batchSize = 1)
    @BenchmarkMode(Mode.SingleShotTime)
    public boolean hashMapForEach(final FullHashMapState state) {
        final long[] sum = { 0 } ;
        state.map.forEach((key, value) -> sum[0] += value);
        return sum[0] >= 1;
    }

    @Benchmark
    @Measurement(iterations = 5, batchSize = 1)
    @BenchmarkMode(Mode.SingleShotTime)
    public boolean hashMapStartsWith(final FullHashMapState state) {
        final long[] sum = { 0 } ;
        state.map.forEach((key, value) -> {
            if (value == 10) {
                sum[0] += value;
            }});
        return sum[0] >= 1;
    }
}
