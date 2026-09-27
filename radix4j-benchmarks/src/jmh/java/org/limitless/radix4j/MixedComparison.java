package org.limitless.radix4j;

import org.limitless.clordid.ClientOrderIdSet;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.SplittableRandom;

/**
 * Compares the memory and speed of a ClientOrderIdSet, a RadixTree and a HashSet&lt;String&gt; holding ids from
 * all data sets but random and base64, one session each, taking turns. Ids longer than the maximum length keep
 * their last characters. The trees and the set hold the session as the first byte of the string, 'A' for
 * session 0.
 * Run each structure in its own JVM:
 * <pre>
 *   java -Xmx16g -cp radix4j-benchmarks-1.0.0-jmh.jar org.limitless.radix4j.MixedComparison clordid 100000000 14
 * </pre>
 * Lookups are of every tenth id added and of as many ids that come next in each data set, in random order.
 */
public final class MixedComparison {

    private static final DataSet[] DATA_SETS = {
        DataSet.SEQUENTIAL, DataSet.ORDER, DataSet.SPARSE, DataSet.SESSION, DataSet.BASE36, DataSet.ARB,
        DataSet.MMK, DataSet.RETSE, DataSet.VWAP, DataSet.DMA, DataSet.SPDOPT, DataSet.NASDAQ
    };
    private static final int SAMPLE_INTERVAL = 10;
    private static final int ROUNDS = 3;

    private final String structure;
    private final int maxLength;
    private final int stride;
    private final ClientOrderIdSet idSet;
    private final RadixTree tree;
    private final HashSet<String> set;

    private MixedComparison(final String structure, final int maxLength) {
        this.structure = structure;
        this.maxLength = maxLength;
        this.stride = 2 + maxLength;
        idSet = structure.equals("clordid") ? new ClientOrderIdSet() : null;
        tree = structure.equals("radix") ? new RadixTree(RadixTree.MAX_BLOCKS_PER_SEGMENT) : null;
        set = structure.equals("hashset") ? new HashSet<>() : null;
    }

    public static void main(final String[] args) {
        if (args.length != 3 || !args[0].matches("clordid|radix|hashset")) {
            System.err.println("usage: MixedComparison clordid|radix|hashset count maxLength");
            System.exit(1);
        }
        new MixedComparison(args[0], Integer.parseInt(args[2])).run(Integer.parseInt(args[1]));
    }

    private void run(final int count) {
        final int samples = count / SAMPLE_INTERVAL;
        final byte[] present = new byte[samples * stride];
        final byte[] absent = new byte[samples * stride];
        final DataSet.Generator[] generators = new DataSet.Generator[DATA_SETS.length];
        for (int i = 0; i < DATA_SETS.length; ++i) {
            generators[i] = DATA_SETS[i].generator();
        }
        final byte[] string = new byte[1 + DataSet.MAX_LENGTH];
        final long baseHeap = usedHeap();

        long bytes = 0;
        long added = 0;
        final long start = System.nanoTime();
        for (int i = 0; i < count; ++i) {
            final int scope = i % DATA_SETS.length;
            final int length = next(generators[scope], scope, string);
            bytes += length - 1;
            if (add(string, length)) {
                ++added;
            }
            if (i % SAMPLE_INTERVAL == 0 && i / SAMPLE_INTERVAL < samples) {
                System.arraycopy(string, 0, present, i / SAMPLE_INTERVAL * stride + 1, length);
                present[i / SAMPLE_INTERVAL * stride] = (byte) length;
            }
        }
        final long addNanos = System.nanoTime() - start;
        for (int i = 0; i < samples; ++i) {
            final int scope = i % DATA_SETS.length;
            final int length = next(generators[scope], scope, string);
            System.arraycopy(string, 0, absent, i * stride + 1, length);
            absent[i * stride] = (byte) length;
        }
        final SplittableRandom random = new SplittableRandom(7);
        shuffle(present, samples, random);
        shuffle(absent, samples, random);

        final long heap = usedHeap() - baseHeap;
        final long offHeap = idSet != null ? idSet.statistics().offHeapBytes() :
            tree != null ? (long) tree.allocatedBlocks() * Node.BYTES : 0;
        System.out.printf("%s, %,d ids of at most %d bytes, %.1f bytes avg, %,d added, max heap %,d MB%n",
            structure, count, maxLength, (double) bytes / count, added, Runtime.getRuntime().maxMemory() >> 20);
        System.out.printf("memory: heap %,d MB  off-heap %,d MB  %.2f bytes/id%n",
            heap >> 20, offHeap >> 20, (double) (heap + offHeap) / added);
        System.out.printf("add: %.1f s  %.0f ns/id%n", addNanos / 1e9, (double) addNanos / count);
        for (int round = 1; round <= ROUNDS; ++round) {
            final long presentNanos = lookups(present, samples, true);
            final long absentNanos = lookups(absent, samples, false);
            System.out.printf("contains, round %d: present %.0f ns  absent %.0f ns%n",
                round, (double) presentNanos / samples, (double) absentNanos / samples);
        }
        if (idSet != null) {
            final ClientOrderIdSet.Statistics statistics = idSet.statistics();
            System.out.printf("counters %,d  fallback %,d  prefixes %,d  containers %,d%n", statistics.counterIds(),
                statistics.fallbackIds(), statistics.prefixes(), statistics.containers());
            for (int scope = 0; scope < DATA_SETS.length; ++scope) {
                System.out.printf("  %-10s %s%s%n", DATA_SETS[scope].name().toLowerCase(), idSet.pattern(scope),
                    idSet.demoted(scope) ? ", demoted" : "");
            }
            idSet.close();
        }
    }

    /**
     * Next id of a data set after its session byte, cut to its last maxLength characters
     * @return length including the session byte
     */
    private int next(final DataSet.Generator generator, final int scope, final byte[] string) {
        final int length = generator.next(string);
        final int kept = Math.min(length, maxLength);
        System.arraycopy(string, length - kept, string, 1, kept);
        string[0] = (byte) ('A' + scope);
        return 1 + kept;
    }

    private boolean add(final byte[] string, final int length) {
        if (idSet != null) {
            return idSet.add(string[0] - 'A', string, 1, length - 1) == ClientOrderIdSet.ADDED;
        }
        if (tree != null) {
            final int size = tree.size();
            tree.add(0, length, string);
            return tree.size() != size;
        }
        return set.add(new String(string, 0, length, StandardCharsets.ISO_8859_1));
    }

    private long lookups(final byte[] strings, final int count, final boolean expected) {
        int found = 0;
        final long start = System.nanoTime();
        for (int i = 0; i < count; ++i) {
            final int offset = i * stride;
            final int length = strings[offset];
            final boolean contains;
            if (idSet != null) {
                contains = idSet.contains(strings[offset + 1] - 'A', strings, offset + 2, length - 1);
            } else if (tree != null) {
                contains = tree.contains(offset + 1, length, strings);
            } else {
                contains = set.contains(new String(strings, offset + 1, length, StandardCharsets.ISO_8859_1));
            }
            if (contains) {
                ++found;
            }
        }
        final long nanos = System.nanoTime() - start;
        if (found != (expected ? count : 0)) {
            System.out.printf("  %,d of %,d %s ids found%n", found, count, expected ? "present" : "absent");
        }
        return nanos;
    }

    private void shuffle(final byte[] strings, final int count, final SplittableRandom random) {
        final byte[] swap = new byte[stride];
        for (int i = count - 1; i > 0; --i) {
            final int j = random.nextInt(i + 1);
            System.arraycopy(strings, i * stride, swap, 0, stride);
            System.arraycopy(strings, j * stride, strings, i * stride, stride);
            System.arraycopy(swap, 0, strings, j * stride, stride);
        }
    }

    private static long usedHeap() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
