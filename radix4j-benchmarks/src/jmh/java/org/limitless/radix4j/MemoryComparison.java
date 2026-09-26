package org.limitless.radix4j;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.SplittableRandom;

/**
 * Compares the memory used by a RadixTree and a HashSet&lt;String&gt; holding the same strings.
 * Run each structure in its own JVM with the same heap limit:
 * <pre>
 *   java -Xmx4g -cp radix4j-benchmarks-1.0.0-jmh.jar org.limitless.radix4j.MemoryComparison radix sequential 50000000
 *   java -Xmx4g -cp radix4j-benchmarks-1.0.0-jmh.jar org.limitless.radix4j.MemoryComparison hashset random 50000000
 * </pre>
 * Strings are 32 bytes. "sequential" strings share a 20-byte prefix followed by a zero padded counter,
 * "random" strings are 32 random alphanumeric characters.
 */
public final class MemoryComparison {

    private static final int STRING_LENGTH = 32;
    private static final byte[] PREFIX = "CUSTOMER-ORDER-2026-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
    private static final int REPORT_INTERVAL = 5_000_000;

    public static void main(final String[] args) {
        if (args.length != 3) {
            System.err.println("usage: MemoryComparison radix|hashset sequential|random count");
            System.exit(1);
        }
        final boolean radix = args[0].equals("radix");
        final boolean random = args[1].equals("random");
        final int count = Integer.parseInt(args[2]);

        final byte[] string = new byte[STRING_LENGTH];
        final SplittableRandom generator = new SplittableRandom(42);
        final long baseHeap = usedHeap();
        final RadixTree tree = radix ? new RadixTree(RadixTree.MAX_BLOCKS_PER_SEGMENT) : null;
        final HashSet<String> set = radix ? null : new HashSet<>();

        System.out.printf("%s %s, %d-byte strings, max heap %,d MB%n", args[0], args[1], STRING_LENGTH,
            Runtime.getRuntime().maxMemory() >> 20);
        int added = 0;
        try {
            for (int i = 0; i < count; ++i) {
                if (random) {
                    randomString(generator, string);
                } else {
                    sequentialString(i, string);
                }
                if (radix) {
                    tree.add(0, STRING_LENGTH, string);
                } else {
                    set.add(new String(string, StandardCharsets.ISO_8859_1));
                }
                ++added;
                if (added % REPORT_INTERVAL == 0) {
                    report(added, baseHeap, tree, set);
                }
            }
            if (added % REPORT_INTERVAL != 0) {
                report(added, baseHeap, tree, set);
            }
        } catch (final OutOfMemoryError error) {
            System.out.printf("OutOfMemoryError after %,d strings%n", added);
            System.exit(2);
        }
    }

    private static void report(final int added, final long baseHeap, final RadixTree tree, final HashSet<String> set) {
        final long heap = usedHeap() - baseHeap;
        final long offHeap = tree != null ? (long) tree.allocatedBlocks() * Node.BYTES : 0;
        final int size = tree != null ? tree.size() : set.size();
        System.out.printf("%,12d strings  heap %,8d MB  off-heap %,8d MB  %6.1f bytes/string%n",
            size, heap >> 20, offHeap >> 20, (double) (heap + offHeap) / size);
    }

    private static long usedHeap() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void sequentialString(int value, final byte[] string) {
        System.arraycopy(PREFIX, 0, string, 0, PREFIX.length);
        for (int i = STRING_LENGTH - 1; i >= PREFIX.length; --i) {
            string[i] = (byte) ('0' + value % 10);
            value /= 10;
        }
    }

    private static void randomString(final SplittableRandom generator, final byte[] string) {
        for (int i = 0; i < STRING_LENGTH; ++i) {
            string[i] = ALPHABET[generator.nextInt(ALPHABET.length)];
        }
    }
}
