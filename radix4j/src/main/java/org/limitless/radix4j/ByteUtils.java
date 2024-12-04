package org.limitless.radix4j;

public class ByteUtils {

    private static final int INT_ONES = 0x01010101;
    private static final int INT_EIGHTS = 0x80808080;
    private static final long LONG_ONES = 0x0101010101010101L;
    private static final long LONG_EIGHTS = 0x8080808080808080L;

    public static int hasLess(final int values, final byte value) {
        return ((values) - INT_ONES * value) & ~(values) & INT_EIGHTS;
    }

    public static long hasLess(final long values, final byte value) {
        return ((values) - LONG_ONES * value) & ~(values) & LONG_EIGHTS;
    }

    public static int hasGreater(final int values, final byte limit) {
        return ((values) + INT_ONES * (127 - limit) | values) & ~(values) & INT_EIGHTS;
    }

    public static long hasGreater(final long values, final byte limit) {
        return ((values) + LONG_ONES * (127 - limit) | values) & ~(values) & LONG_EIGHTS;
    }

    public static int hasZeroByte(final int values) {
        return ((values) - INT_ONES) & ~(values) & INT_EIGHTS;
    }

    public static long hasZeroByte(final long values) {
        return ((values) - LONG_ONES) & ~(values) & LONG_EIGHTS;
    }

    public static int hasByte(final int values, final byte value) {
        return hasZeroByte(values ^ (INT_ONES * value));
    }

    public static long hasByte(final long values, final byte value) {
        return hasZeroByte(values ^ (LONG_ONES * value));
    }

    public static int bytePosition(final int values, final byte value) {
        final int position = Integer.numberOfTrailingZeros(hasByte(values, value)) >>> 3;
        return position == Integer.BYTES ? -1 : position;
    }

    public static int bytePosition(final long values, final byte value) {
        final int position = Long.numberOfTrailingZeros(hasByte(values, value)) >>> 3;
        return position == Long.BYTES ? -1 : position;
    }

    public static int bytePosition2(final long values, byte value) {
        long mask = 0xff;
        int offset = 0;
        for (int i = 0; i < Long.BYTES; ++i) {
            if (((values & mask) >>> offset) == value) {
                return i;
            }
            offset += 8;
            mask <<= 8;
        }
        return -1;
    }
}
