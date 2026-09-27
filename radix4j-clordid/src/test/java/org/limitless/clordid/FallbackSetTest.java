package org.limitless.clordid;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

public class FallbackSetTest {

    @Test
    public void addAndContains() {
        try (FallbackSet set = new FallbackSet(ClientOrderIdSet.DEFAULT_SEGMENT_BYTES)) {
            final byte[] key = "EAg7HEEzRdy37rY8NMLV-gA".getBytes(StandardCharsets.US_ASCII);
            assertFalse(set.contains(0, key, 0, key.length));
            assertTrue(set.add(0, key, 0, key.length));
            assertFalse(set.add(0, key, 0, key.length));
            assertTrue(set.contains(0, key, 0, key.length));
            assertFalse(set.contains(1, key, 0, key.length));
            assertFalse(set.contains(0, key, 0, key.length - 1));
            assertTrue(set.add(1, key, 0, key.length));
            assertTrue(set.add(0, key, 1, key.length - 1));
            assertTrue(set.add(0, key, 0, 0));
            assertFalse(set.add(0, key, 5, 0));
            assertEquals(4, set.size());
            assertEquals(3, set.size(0));
            assertEquals(1, set.size(1));
            assertEquals(0, set.size(2));
        }
    }

    @Test
    public void matchesHashSet() {
        final SplittableRandom random = new SplittableRandom(11);
        final Set<String> expected = new HashSet<>();
        final byte[] key = new byte[80];
        try (FallbackSet set = new FallbackSet(ClientOrderIdSet.DEFAULT_SEGMENT_BYTES)) {
            for (int i = 0; i < 400_000; ++i) {
                // few scopes, a short alphabet and short lengths, so that keys repeat and chains overflow
                final int scope = random.nextInt(3) * 100;
                final int length = random.nextInt(random.nextInt(8) == 0 ? 70 : 12);
                final int position = random.nextInt(8);
                for (int j = 0; j < length; ++j) {
                    key[position + j] = (byte) ('A' + random.nextInt(4));
                }
                final String string = scope + ":" + new String(key, position, length, StandardCharsets.ISO_8859_1);
                assertEquals(expected.add(string), set.add(scope, key, position, length), string);
                if ((i & 255) == 0) {
                    assertTrue(set.contains(scope, key, position, length));
                }
            }
            assertEquals(expected.size(), set.size());
            for (final String string : expected) {
                final int colon = string.indexOf(':');
                final byte[] bytes = string.substring(colon + 1).getBytes(StandardCharsets.ISO_8859_1);
                assertTrue(set.contains(Integer.parseInt(string, 0, colon, 10), bytes, 0, bytes.length), string);
            }
        }
    }

    @Test
    public void growsOneBucketAtATime() {
        final byte[] key = new byte[21];
        try (FallbackSet set = new FallbackSet(ClientOrderIdSet.DEFAULT_SEGMENT_BYTES)) {
            int buckets = set.buckets(7);
            for (int i = 0; i < 200_000; ++i) {
                Arrays.fill(key, (byte) i);
                key[0] = (byte) (i >>> 8);
                key[1] = (byte) (i >>> 16);
                assertTrue(set.add(7, key, 0, key.length));
                final int now = set.buckets(7);
                assertTrue(now == buckets || now == buckets + 1, "grew from " + buckets + " to " + now);
                buckets = now;
            }
            // 23 bytes per entry, split at an average of 96 bytes per bucket
            assertTrue(buckets >= 200_000L * 23 / FallbackSet.TARGET_BYTES, "buckets " + buckets);
            assertEquals(0, set.buckets(6));
            // the directory is off-heap, only one address per 1,024 buckets is on the heap
            assertTrue(set.heapBytes() <= 2L * Integer.BYTES * (buckets / FallbackSet.DIRECTORY_ENTRIES + 1), "heap " + set.heapBytes());
            for (int i = 0; i < 200_000; ++i) {
                Arrays.fill(key, (byte) i);
                key[0] = (byte) (i >>> 8);
                key[1] = (byte) (i >>> 16);
                assertTrue(set.contains(7, key, 0, key.length));
            }
        }
    }

    @Test
    public void bucketsGrowThroughSizeClassesAndChain() {
        final SplittableRandom random = new SplittableRandom(13);
        final Set<String> expected = new HashSet<>();
        final byte[] key = new byte[40];
        // no splits: one bucket moves through every size class and then chains blocks of the largest
        try (FallbackSet set = new FallbackSet(ClientOrderIdSet.DEFAULT_SEGMENT_BYTES, Integer.MAX_VALUE)) {
            for (int i = 0; i < 2_000; ++i) {
                final int length = random.nextInt(1, 40);
                for (int j = 0; j < length; ++j) {
                    key[j] = (byte) random.nextInt(0x20, 0x7f);
                }
                final String string = new String(key, 0, length, StandardCharsets.ISO_8859_1);
                assertEquals(expected.add(string), set.add(3, key, 0, length));
            }
            assertEquals(1, set.buckets(3));
            assertTrue(set.usedBytes() > 2 * 1024);
            for (final String string : expected) {
                final byte[] bytes = string.getBytes(StandardCharsets.ISO_8859_1);
                assertTrue(set.contains(3, bytes, 0, bytes.length), string);
                assertFalse(set.contains(4, bytes, 0, bytes.length), string);
            }
        }
    }
}
