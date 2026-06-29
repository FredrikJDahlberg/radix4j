package org.limitless.radix4j;

import java.lang.foreign.Arena;
import java.util.concurrent.TimeUnit;

public class StringCache {

    private static final int MAX_LENGTH = 32;
    private static final int MAX_COUNT = (int) TimeUnit.HOURS.toSeconds(2);

    private static final int PREFIX_LENGTH = 1;

    private final RadixTree cache;

    public StringCache() {
        cache = new RadixTree(256, Arena.ofShared());
    }

    /*
    1 add: add string with timestamp to cache, calc timestamp -> bucket
    3 add string with hint (bucket) to radix tree
    4 remove: remove string from tree
    4 cleanup: remove nodes from start to stop with correct bucket number

    string = |0, len (1 byte) |1-31, string (max - 1) |
    radix entry = bucket + string

    one tree per participant

     */

    private final byte[] prefix = new byte[64];

    private int epochTimestamp;

    public void add(final int timestampSeconds, final int offset, final int length, final byte[] string) {
        if (epochTimestamp == 0) {
            epochTimestamp = timestampSeconds;
        }
        System.arraycopy(string, offset, prefix, PREFIX_LENGTH, length);
        prefix[0] = (byte) ((timestampSeconds - epochTimestamp) % (60 * 24));
        var _ = cache.add(offset, length + PREFIX_LENGTH, prefix);
    }

    public void remove(final int timestampSeconds, final int offset, final int length, final byte[] string) {
        System.arraycopy(string, offset, prefix, PREFIX_LENGTH, length);
        prefix[0] = (byte) ((timestampSeconds - epochTimestamp) % (60 * 24));
        var _ = cache.remove(0, length + PREFIX_LENGTH, prefix);
    }

    public void removeAll(final int timestampSeconds) {
        prefix[0] = (byte) ((timestampSeconds - epochTimestamp) % (60 * 24));
        /* var _ =*/ cache.forEach(PREFIX_LENGTH, prefix, System.out::println);
    }

    public static void main(String...args) {
        final StringCache cache = new StringCache();
        cache.add(10, 0, 5, "ABCDE".getBytes());
        cache.add(10, 0, 5, "ABCEF".getBytes());
        cache.add(11, 0, 5, "CDEFG".getBytes());
        cache.removeAll(10);
        cache.removeAll(11);
    }
}
