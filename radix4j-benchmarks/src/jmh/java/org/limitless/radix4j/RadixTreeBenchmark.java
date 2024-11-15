package org.limitless.radix4j;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@Fork(value = 5)
@Warmup(iterations = 5, batchSize = 100_000)
@Measurement(iterations = 5, batchSize = 100_000)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
public class RadixTreeBenchmark {

    RadixTree tree;
    final String shortPrefix = "12345678901234567890";
    int count = 0;
    String[] strings = new String[100_000];

    @Setup(Level.Iteration)
    public void setup() {
        tree = new RadixTree();
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        tree.clear();
        count = 0;
    }

    @Benchmark
    public String baseline() {
        String string = shortPrefix + count;
        strings[count] = string;
        ++count;
        return string;
    }

    @Benchmark
    public String add() {
        String string = shortPrefix + count;
        tree.add(string);
        ++count;
        return string;
    }
}
