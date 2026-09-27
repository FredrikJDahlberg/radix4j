package org.limitless.clordid;

import java.util.Arrays;

/**
 * Table giving each distinct prefix of at most {@value #MAX_LENGTH} bytes a dense id from 0, found through a
 * {@link LinearIndex}. Prefixes are stored after a length byte in pages of {@value #BYTES_PAGE} bytes, so no add
 * copies earlier prefixes.
 */
final class PrefixTable {

    static final int MAX_LENGTH = 255;

    private static final int BYTES_SHIFT = 14;
    private static final int BYTES_PAGE = 1 << BYTES_SHIFT;
    private static final int BYTES_MASK = BYTES_PAGE - 1;
    private static final int OFFSET = 0;    // byte page << BYTES_SHIFT | position of the length byte

    private final LinearIndex index = new LinearIndex(1);
    private byte[][] bytes = {new byte[BYTES_PAGE]};
    private int bytePage;
    private int bytePosition;

    int size() {
        return index.size();
    }

    /**
     * Find a prefix
     * @return prefix id or -1
     */
    int find(final byte[] key, final int position, final int length) {
        return find(hash(key, position, length), key, position, length);
    }

    /**
     * Find or add a prefix
     * @return prefix id
     */
    int add(final byte[] key, final int position, final int length) {
        assert length <= MAX_LENGTH;
        final int hash = hash(key, position, length);
        final int found = find(hash, key, position, length);
        if (found >= 0) {
            return found;
        }
        if (bytePosition + 1 + length > BYTES_PAGE) {
            if (++bytePage == bytes.length) {
                bytes = Arrays.copyOf(bytes, bytePage << 1);
            }
            bytes[bytePage] = new byte[BYTES_PAGE];
            bytePosition = 0;
        }
        final byte[] page = bytes[bytePage];
        page[bytePosition] = (byte) length;
        System.arraycopy(key, position, page, bytePosition + 1, length);
        final int id = index.add(hash);
        index.field(id, OFFSET, bytePage << BYTES_SHIFT | bytePosition);
        bytePosition += 1 + length;
        return id;
    }

    long heapBytes() {
        return index.heapBytes() + (long) BYTES_PAGE * (bytePage + 1) + (long) Long.BYTES * bytes.length;
    }

    private int find(final int hash, final byte[] key, final int position, final int length) {
        for (int id = index.first(hash); id >= 0; id = index.next(id)) {
            if (index.hash(id) == hash && matches(id, key, position, length)) {
                return id;
            }
        }
        return -1;
    }

    private boolean matches(final int id, final byte[] key, final int position, final int length) {
        final int offset = index.field(id, OFFSET);
        final byte[] page = bytes[offset >>> BYTES_SHIFT];
        final int start = (offset & BYTES_MASK) + 1;
        return (page[start - 1] & 0xff) == length &&
            Arrays.equals(page, start, start + length, key, position, position + length);
    }

    private static int hash(final byte[] key, final int position, final int length) {
        int hash = length;
        for (int i = position; i < position + length; ++i) {
            hash = 31 * hash + key[i];
        }
        hash *= 0x9e3779b9;
        return hash ^ hash >>> 15;
    }
}
