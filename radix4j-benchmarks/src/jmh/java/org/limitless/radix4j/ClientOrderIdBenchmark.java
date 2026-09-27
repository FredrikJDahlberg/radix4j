package org.limitless.radix4j;

import org.limitless.clordid.ClientOrderIdSet;
import org.limitless.clordid.Pattern;
import org.openjdk.jmh.annotations.*;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Lookups of ids present in a {@link ClientOrderIdSet} and a {@link RadixTree}, in random order.
 */
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {"-Xmx8g"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
public class ClientOrderIdBenchmark {

    private static final int SIZE = 10_000_000;

    @Param({"base64", "order", "session", "sparse"})
    public String dataSet;

    private byte[] strings;
    private int[] offsets;
    private int[] order;
    private ClientOrderIdSet idSet;
    private RadixTree tree;
    private int index;

    @Setup(Level.Trial)
    public void setup() {
        final DataSet set = DataSet.of(dataSet);
        final DataSet.Generator generator = set.generator();
        final byte[] string = new byte[DataSet.MAX_LENGTH];
        strings = new byte[SIZE * DataSet.MAX_LENGTH];
        offsets = new int[SIZE + 1];
        idSet = new ClientOrderIdSet();
        idSet.pattern(0, switch (set) {
            case BASE36 -> Pattern.base36(7);
            case RANDOM, BASE64 -> Pattern.none();
            default -> Pattern.decimal();
        });
        tree = new RadixTree(RadixTree.MAX_BLOCKS_PER_SEGMENT);
        int offset = 0;
        for (int i = 0; i < SIZE; ++i) {
            final int length = generator.next(string);
            System.arraycopy(string, 0, strings, offset, length);
            offsets[i] = offset;
            offset += length;
            idSet.add(0, strings, offsets[i], length);
            tree.add(offsets[i], length, strings);
        }
        offsets[SIZE] = offset;
        order = new int[SIZE];
        final SplittableRandom random = new SplittableRandom(3);
        for (int i = 0; i < SIZE; ++i) {
            final int j = random.nextInt(i + 1);
            order[i] = order[j];
            order[j] = i;
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        idSet.close();
    }

    @Benchmark
    public boolean idSetContains() {
        final int i = order[index++ % SIZE];
        return idSet.contains(0, strings, offsets[i], offsets[i + 1] - offsets[i]);
    }

    @Benchmark
    public boolean treeContains() {
        final int i = order[index++ % SIZE];
        return tree.contains(offsets[i], offsets[i + 1] - offsets[i], strings);
    }
}
