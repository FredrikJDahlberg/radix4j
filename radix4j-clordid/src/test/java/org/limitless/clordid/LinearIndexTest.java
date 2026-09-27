package org.limitless.clordid;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

public class LinearIndexTest {

    @Test
    public void growsOneBucketAtATime() {
        final LinearIndex index = new LinearIndex(0);
        final SplittableRandom random = new SplittableRandom(5);
        final int[] hashes = new int[100_000];
        for (int id = 0; id < hashes.length; ++id) {
            hashes[id] = random.nextInt();
            assertEquals(id, index.add(hashes[id]));
            assertEquals(id + 1, index.buckets());
        }
        for (int id = 0; id < hashes.length; ++id) {
            assertTrue(chain(index, hashes[id], id));
            assertEquals(hashes[id], index.hash(id));
        }
    }

    @Test
    public void collidingHashesShareABucket() {
        final LinearIndex index = new LinearIndex(0);
        for (int id = 0; id < 5_000; ++id) {
            index.add(42);
        }
        int count = 0;
        for (int id = index.first(42); id >= 0; id = index.next(id)) {
            ++count;
        }
        assertEquals(5_000, count);
    }

    @Test
    public void prefixTableGivesDenseIds() {
        final PrefixTable table = new PrefixTable();
        final int count = 300_000;
        for (int i = 0; i < count; ++i) {
            final byte[] prefix = prefix(i);
            assertEquals(i, table.add(prefix, 0, prefix.length));
        }
        for (int i = 0; i < count; ++i) {
            final byte[] prefix = prefix(i);
            assertEquals(i, table.add(prefix, 0, prefix.length));
            assertEquals(i, table.find(prefix, 0, prefix.length));
            assertEquals(-1, table.find(prefix, 0, prefix.length - 1));
        }
        final byte[] longest = new byte[PrefixTable.MAX_LENGTH + 1];
        assertEquals(count, table.add(longest, 0, PrefixTable.MAX_LENGTH));
        assertEquals(count, table.find(longest, 0, PrefixTable.MAX_LENGTH));
        assertEquals(-1, table.find(longest, 0, longest.length));
        assertEquals(count + 1, table.size());
    }

    private static boolean chain(final LinearIndex index, final int hash, final int id) {
        for (int found = index.first(hash); found >= 0; found = index.next(found)) {
            if (found == id) {
                return true;
            }
        }
        return false;
    }

    private static byte[] prefix(final int i) {
        return ("P" + i + "-" + "x".repeat(i % 40)).getBytes(StandardCharsets.US_ASCII);
    }
}
