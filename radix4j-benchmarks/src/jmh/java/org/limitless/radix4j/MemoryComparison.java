package org.limitless.radix4j;

import org.limitless.clordid.ClientOrderIdSet;
import org.limitless.clordid.Pattern;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/**
 * Compares the memory used by a RadixTree, a HashSet&lt;String&gt; and a ClientOrderIdSet holding the same
 * strings.
 * Run each structure in its own JVM with the same heap limit:
 * <pre>
 *   java -Xmx4g -cp radix4j-benchmarks-1.0.0-jmh.jar org.limitless.radix4j.MemoryComparison radix sequential 50000000
 *   java -Xmx4g -cp radix4j-benchmarks-1.0.0-jmh.jar org.limitless.radix4j.MemoryComparison hashset random 50000000
 *   java -Xmx4g -cp radix4j-benchmarks-1.0.0-jmh.jar org.limitless.radix4j.MemoryComparison clordid order 50000000
 * </pre>
 * See {@link DataSet} for the data sets.
 */
public final class MemoryComparison {

    private static final int REPORT_INTERVAL = 5_000_000;

    public static void main(final String[] args) {
        if (args.length != 3) {
            System.err.println("usage: MemoryComparison radix|hashset|clordid " +
                "sequential|random|order|sparse|session|base36|base64|arb|mmk|retse|vwap|dma|spdopt|nasdaq count");
            System.exit(1);
        }
        final boolean radix = args[0].equals("radix");
        final DataSet dataSet = DataSet.of(args[1]);
        final DataSet.Generator generator = dataSet.generator();
        final int count = Integer.parseInt(args[2]);
        if (args[0].equals("clordid")) {
            clientOrderIds(dataSet, generator, count);
            return;
        }

        final byte[] string = new byte[DataSet.MAX_LENGTH];
        final long baseHeap = usedHeap();
        final RadixTree tree = radix ? new RadixTree(RadixTree.MAX_BLOCKS_PER_SEGMENT) : null;
        final HashSet<String> set = radix ? null : new HashSet<>();

        System.out.printf("%s %s, max heap %,d MB%n", args[0], args[1], Runtime.getRuntime().maxMemory() >> 20);
        long bytes = 0;
        int added = 0;
        try {
            for (int i = 0; i < count; ++i) {
                final int length = generator.next(string);
                if (radix) {
                    tree.add(0, length, string);
                } else {
                    set.add(new String(string, 0, length, StandardCharsets.ISO_8859_1));
                }
                bytes += length;
                ++added;
                if (added % REPORT_INTERVAL == 0) {
                    report(added, bytes, baseHeap, tree, set);
                }
            }
            if (added % REPORT_INTERVAL != 0) {
                report(added, bytes, baseHeap, tree, set);
            }
        } catch (final OutOfMemoryError error) {
            System.out.printf("OutOfMemoryError after %,d strings%n", added);
            System.exit(2);
        }
    }

    private static void clientOrderIds(final DataSet dataSet, final DataSet.Generator generator, final int count) {
        final byte[] string = new byte[DataSet.MAX_LENGTH];
        final long baseHeap = usedHeap();
        final ClientOrderIdSet idSet = new ClientOrderIdSet();
        System.out.printf("clordid %s, max heap %,d MB%n", dataSet.name().toLowerCase(), Runtime.getRuntime().maxMemory() >> 20);
        for (int i = 1; i <= count; ++i) {
            final int length = generator.next(string);
            idSet.add(0, string, 0, length);
            if (i % REPORT_INTERVAL == 0 || i == count) {
                final long heap = usedHeap() - baseHeap;
                final ClientOrderIdSet.Statistics statistics = idSet.statistics();
                final long offHeap = statistics.offHeapBytes();
                final long size = idSet.size();
                System.out.printf("%,12d ids  %s  %,d counters  %,d fallback  %,d prefixes  %,d containers  " +
                        "heap %,8d KB  off-heap %,8d KB  %6.2f bytes/id%n",
                    size, idSet.pattern(0), statistics.counterIds(), statistics.fallbackIds(), statistics.prefixes(),
                    statistics.containers(), heap >> 10, offHeap >> 10,
                    size == 0 ? 0.0 : (double) (heap + offHeap) / size);
            }
        }
        idSet.close();
    }

    private static void report(final int added, final long bytes, final long baseHeap,
                               final RadixTree tree, final HashSet<String> set) {
        final long heap = usedHeap() - baseHeap;
        final long offHeap = tree != null ? (long) tree.allocatedBlocks() * Node.BYTES : 0;
        final int size = tree != null ? tree.size() : set.size();
        System.out.printf("%,12d strings  %4.1f bytes avg  heap %,8d MB  off-heap %,8d MB  %6.1f bytes/string%n",
            size, (double) bytes / added, heap >> 20, offHeap >> 20, (double) (heap + offHeap) / size);
    }

    private static long usedHeap() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
