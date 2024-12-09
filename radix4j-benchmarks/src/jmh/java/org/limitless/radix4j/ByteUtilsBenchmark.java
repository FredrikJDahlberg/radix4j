package org.limitless.radix4j;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Fork(jvmArgs = "-server", value = 5)
@Warmup(iterations = 5, batchSize = 100_000_000)
@Measurement(iterations = 5, batchSize = 100_000_000)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.SingleShotTime)
public class ByteUtilsBenchmark {

    @State(Scope.Thread)
    public static class ByteUtilsState {
        final long keys = 0x4847464544434241L;
        byte key = 'A';
        final byte[] string = "ABCDEFGH".getBytes();
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
