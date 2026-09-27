package org.limitless.clordid;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

public class CounterSetTest {

    private static final byte[] PREFIX = "AB".getBytes(StandardCharsets.US_ASCII);

    @Test
    public void emptySet() {
        try (CounterSet set = new CounterSet(ClientOrderIdSet.DEFAULT_SEGMENT_BYTES)) {
            assertFalse(set.contains(PREFIX, 0, PREFIX.length, 1));
            assertEquals(0, set.size());
        }
    }

    @Test
    public void duplicates() {
        try (CounterSet set = new CounterSet(ClientOrderIdSet.DEFAULT_SEGMENT_BYTES)) {
            assertTrue(set.add(PREFIX, 0, PREFIX.length, 7));
            assertFalse(set.add(PREFIX, 0, PREFIX.length, 7));
            assertTrue(set.add(PREFIX, 0, 1, 7));
            assertTrue(set.contains(PREFIX, 0, 1, 7));
            assertFalse(set.contains(PREFIX, 1, 1, 7));
            assertEquals(2, set.size());
        }
    }

    @Test
    public void contiguousCounterIsOneRun() {
        try (CounterSet set = new CounterSet(ClientOrderIdSet.DEFAULT_SEGMENT_BYTES)) {
            for (int i = 1; i <= 60_000; ++i) {
                assertTrue(set.add(PREFIX, 0, PREFIX.length, i));
            }
            assertEquals(1, set.containers());
            assertEquals(16, set.usedBytes());
        }
    }

    @Test
    public void sparseNumbersAreGapEncoded() {
        final SplittableRandom random = new SplittableRandom(5);
        try (CounterSet set = new CounterSet(ClientOrderIdSet.DEFAULT_SEGMENT_BYTES)) {
            long number = 1_300_000_000L;
            for (int i = 0; i < 1_000_000; ++i) {
                assertTrue(set.add(PREFIX, 0, PREFIX.length, number += random.nextInt(1, 513)));
            }
            assertTrue(set.usedBytes() < 1.6 * set.size(), "bytes per number: " + (double) set.usedBytes() / set.size());
            assertFalse(set.add(PREFIX, 0, PREFIX.length, number));
            assertTrue(set.contains(PREFIX, 0, PREFIX.length, number));
            assertFalse(set.contains(PREFIX, 0, PREFIX.length, number + 1));
        }
    }

    @Test
    public void matchesHashSet() {
        final SplittableRandom random = new SplittableRandom(3);
        check("sequential", new LongSupplier() {
            long next;
            public long getAsLong() { return next++; }
        });
        check("dense gaps", new LongSupplier() {
            long next;
            public long getAsLong() { return next += random.nextInt(3) == 0 ? random.nextInt(2, 9) : 1; }
        });
        check("sparse", new LongSupplier() {
            long next = 1_300_000_000L;
            public long getAsLong() { return next += random.nextInt(1, 513); }
        });
        check("sparse with large gaps", new LongSupplier() {
            long next = 1_300_000_000L;
            public long getAsLong() {
                return next += random.nextInt(50) == 0 ? random.nextInt(513, 1 << 16) : random.nextInt(1, 513);
            }
        });
        check("sparse out of order", () -> random.nextInt(1 << 26));
        check("sparse chunk edges", () -> (long) random.nextInt(1 << 10) << 16 |
            (random.nextBoolean() ? random.nextInt(256) : 0xffff - random.nextInt(256)));
        check("random in chunk", () -> random.nextInt(1 << 16));
        check("random in few chunks", () -> random.nextInt(1 << 18));
        check("random runs", new LongSupplier() {
            long next;
            int left;
            public long getAsLong() {
                if (left-- == 0) {
                    next = random.nextLong(1 << 20);
                    left = random.nextInt(1, 50);
                }
                return next++;
            }
        });
        check("wide", () -> random.nextLong(Long.MAX_VALUE));
        check("chunk edges", () -> (long) random.nextInt(4) << 16 | (random.nextBoolean() ? 0 : 0xffff));
    }

    private static void check(final String name, final LongSupplier numbers) {
        final SplittableRandom random = new SplittableRandom(name.hashCode());
        final byte[][] prefixes = {PREFIX, "22-".getBytes(StandardCharsets.US_ASCII), new byte[0]};
        final Set<String> expected = new HashSet<>();
        try (CounterSet set = new CounterSet(ClientOrderIdSet.DEFAULT_SEGMENT_BYTES)) {
            for (int i = 0; i < 200_000; ++i) {
                final int p = random.nextInt(prefixes.length);
                final long number = numbers.getAsLong();
                final boolean added = expected.add(p + ":" + number);
                assertEquals(added, set.add(prefixes[p], 0, prefixes[p].length, number), name);
                if ((i & 1023) == 0) {
                    assertTrue(set.contains(prefixes[p], 0, prefixes[p].length, number), name);
                    final long other = random.nextBoolean() ? number + 1 : random.nextLong(Long.MAX_VALUE);
                    assertEquals(expected.contains(p + ":" + other),
                        set.contains(prefixes[p], 0, prefixes[p].length, other), name);
                }
            }
            assertEquals(expected.size(), set.size(), name);
            for (final String entry : expected) {
                final int p = entry.charAt(0) - '0';
                final long number = Long.parseLong(entry.substring(2));
                assertTrue(set.contains(prefixes[p], 0, prefixes[p].length, number), name);
            }
        }
    }
}
