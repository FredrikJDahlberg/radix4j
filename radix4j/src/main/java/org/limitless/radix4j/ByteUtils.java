package org.limitless.radix4j;

/**
 * For a detailed explanation see:
 * <a href="https://leveluppp.ghost.io/advanced-bit-hacks/">...</a>
 * <a href="https://lemire.me/blog/2022/01/21/swar-explained-parsing-eight-digits/">...</a>
 */
public final class ByteUtils {

    private static final int INT_ONES = 0x01010101;
    private static final int INT_EIGHTS = 0x80808080;
    private static final long LONG_ONES = 0x0101010101010101L;
    private static final long LONG_EIGHTS = 0x8080808080808080L;

    public static int hasLess(final byte value, final int values) {
        return (values - INT_ONES * value) & ~values & INT_EIGHTS;
    }

    public static long hasLess(final byte value, final long values) {
        return (values - LONG_ONES * value) & ~values & LONG_EIGHTS;
    }

    public static int hasGreater(final byte limit, final int values) {
        return (values + INT_ONES * (127 - limit) | values) & ~values & INT_EIGHTS;
    }

    public static long hasGreater(final long values, final byte limit) {
        return (values + LONG_ONES * (127 - limit) | values) & ~values & LONG_EIGHTS;
    }

    public static int hasZeroByte(final int value) {
        return (value - INT_ONES) & ~value & INT_EIGHTS;
    }

    public static long hasZeroByte(final long value) {
        return (value - LONG_ONES) & ~value & LONG_EIGHTS;
    }

    public static int hasByte(final byte value, final int values) {
        return hasZeroByte(values ^ (INT_ONES * value));
    }

    public static long hasByte(final byte value, final long values) {
        return hasZeroByte(values ^ (LONG_ONES * value));
    }

    public static int bytePosition(final byte value, final int values) {
        final int position = Integer.numberOfTrailingZeros(hasByte(value, values));
        return position == Integer.SIZE ? -1 : (position / Byte.SIZE);
    }

    public static int bytePosition(final byte value, final long values) {
        final int position = Long.numberOfTrailingZeros(hasByte(value, values));
        return position == Long.SIZE ? -1 : (position / Byte.SIZE);
    }

    public static int findBytePosition(byte value, final long values) {
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

    public static int mismatch(final byte[] string, final long values) {
        long value = values;
        for (int i = 0; i < Long.BYTES; ++i) {
            if ((value & 0xff) != string[i]) {
                return i;
            }
            value >>>= 8;
        }
        return -1;
    }

    public static long pack(final byte[] values) {
        long value = values[0];
        value |= (long) values[1] << 8L;
        value |= (long) values[2] << 16L;
        value |= (long) values[3] << 24L;
        value |= (long) values[4] << 32L;
        value |= (long) values[5] << 40L;
        value |= (long) values[6] << 48L;
        value |= (long) values[7] << 56L;
        return value;
    }

    //
    // Benchmark Helpers
    //
    public static int hashCode(int result, final byte[] string, final int fromIndex, final int length) {
        int end = fromIndex + length;
        for (int i = fromIndex; i < end; i++) {
            result = 31 * result + string[i];
        }
        return result;
    }

    public static void intToChars(int value, final int offset, final int length, final byte[] bytes) {
        int pos = offset;
        while (value >= 10) {
            final int result = value / 10;
            final int digit = value % 10;
            bytes[--pos] = (byte) ('0' + digit);
            value = result;
        }
        bytes[--pos] = (byte) ('0' + value);
    }
}
