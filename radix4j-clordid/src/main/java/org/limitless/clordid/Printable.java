package org.limitless.clordid;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Printable ASCII ids (0x20 to 0x7e): validation and packing into 7 bits per character.
 */
final class Printable {

    /** Bytes the output of {@link #pack} may be written past its length */
    static final int PACK_SLACK = Long.BYTES;

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long ONES = 0x0101010101010101L;
    private static final long HIGH = 0x8080808080808080L;
    private static final long SPACES = 0x2020202020202020L;

    private Printable() {
    }

    /**
     * Check that all bytes are printable ASCII, eight bytes at a time
     * @param id bytes
     * @param position start
     * @param length length
     * @return true when every byte is in 0x20..0x7e
     */
    static boolean isPrintable(final byte[] id, final int position, final int length) {
        int i = 0;
        for (; i + Long.BYTES <= length; i += Long.BYTES) {
            final long word = (long) LONGS.get(id, position + i);
            // high bit set, 0x7f (becomes 0x80 plus one), or below 0x20 (borrows into the high bit); carries and
            // borrows across bytes only flag bytes of a word that already holds an invalid byte
            if (((word | word + ONES) & HIGH | word - SPACES & ~word & HIGH) != 0) {
                return false;
            }
        }
        for (; i < length; ++i) {
            if ((id[position + i] - 0x20 & 0xff) >= 0x5f) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pack printable characters into 7 bits each, the first character in the low bits of the first byte. The
     * packing is reversible because no character is zero: a trailing 7-bit group of zeros is not a character.
     * @param id printable bytes
     * @param position start
     * @param length length
     * @param packed output with {@value #PACK_SLACK} bytes of room after the packed bytes
     * @param offset output start
     * @return number of packed bytes, (7 * length + 7) / 8
     */
    static int pack(final byte[] id, final int position, final int length, final byte[] packed, final int offset) {
        int i = 0;
        int out = offset;
        for (; i + Long.BYTES <= length; i += Long.BYTES) {
            LONGS.set(packed, out, compress((long) LONGS.get(id, position + i)));
            out += 7;
        }
        final int left = length - i;
        if (left > 0) {
            long word = 0;
            for (int j = left - 1; j >= 0; --j) {
                word = word << 8 | id[position + i + j] & 0xff;
            }
            LONGS.set(packed, out, compress(word));
            out += (7 * left + 7) >>> 3;
        }
        return out - offset;
    }

    /**
     * Squeeze eight 7-bit values, one per byte, into the low 56 bits
     */
    private static long compress(long word) {
        word = word & 0x007f007f007f007fL | (word & 0x7f007f007f007f00L) >>> 1;
        word = word & 0x00003fff00003fffL | (word & 0x3fff00003fff0000L) >>> 2;
        return word & 0x000000000fffffffL | (word & 0x0fffffff00000000L) >>> 4;
    }
}
