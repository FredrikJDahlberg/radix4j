package org.limitless.radix4j;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Fork(jvmArgs = "-server", value = 5)
@Warmup(iterations = 5, batchSize = 100_000_000)
@Measurement(iterations = 5, batchSize = 100_000_000)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.SingleShotTime)

/*
@State(Scope.Thread)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 2, time = 10)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
*/
public class ByteUtilsBenchmark {

    @State(Scope.Thread)
    public static class ByteUtilsState {
        long keys = 0x4847464544434241L;
        byte key;
        byte[] string = "ABCDEFGH".getBytes();
    }

    @Setup(Level.Iteration)
    public void setup(final ByteUtilsState state) {
        state.key = 'A';
    }

    @Benchmark
    public int searchLongLoop(ByteUtilsState state) {
        return ByteUtils.findBytePosition(state.key, state.keys);
    }

    @Benchmark
    public int searchLongSWAR(ByteUtilsState state) {
        return ByteUtils.bytePosition(state.key, state.keys);
    }

    @Benchmark
    public int mismatch(ByteUtilsState state) {
        return ByteUtils.mismatch(state.string, state.keys);
    }

    @Benchmark
    public int compare(ByteUtilsState state) {
        long string = ByteUtils.pack(state.string);
        return (Long.BYTES - Long.numberOfLeadingZeros(string & state.keys) / Byte.SIZE);
    }
}
