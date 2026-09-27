package org.limitless.clordid;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

public class PrintableTest {

    @Test
    public void everyByteAtEveryPosition() {
        final byte[] id = new byte[24];
        for (int length = 1; length <= 20; ++length) {
            for (int at = 0; at < length; ++at) {
                for (int value = 0; value < 256; ++value) {
                    Arrays.fill(id, (byte) 'A');
                    id[2 + at] = (byte) value;
                    final boolean expected = value >= 0x20 && value <= 0x7e;
                    assertEquals(expected, Printable.isPrintable(id, 2, length), "value " + value + " at " + at);
                }
            }
        }
    }

    @Test
    public void edgesOfTheRange() {
        final byte[] id = new byte[16];
        Arrays.fill(id, (byte) 0x20);
        assertTrue(Printable.isPrintable(id, 0, 16));
        Arrays.fill(id, (byte) 0x7e);
        assertTrue(Printable.isPrintable(id, 0, 16));
        assertTrue(Printable.isPrintable(id, 0, 0));
    }

    @Test
    public void packMatchesBitByBitPacking() {
        final SplittableRandom random = new SplittableRandom(5);
        final byte[] id = new byte[48];
        final byte[] packed = new byte[64];
        for (int round = 0; round < 100_000; ++round) {
            final int length = random.nextInt(41);
            final int position = random.nextInt(4);
            for (int i = 0; i < length; ++i) {
                id[position + i] = (byte) random.nextInt(0x20, 0x7f);
            }
            final int offset = random.nextInt(5);
            final int count = Printable.pack(id, position, length, packed, offset);
            final byte[] expected = reference(id, position, length);
            assertEquals(expected.length, count);
            assertArrayEquals(expected, Arrays.copyOfRange(packed, offset, offset + count));
            assertArrayEquals(Arrays.copyOfRange(id, position, position + length), unpack(packed, offset, count));
        }
    }

    private static byte[] reference(final byte[] id, final int position, final int length) {
        final byte[] packed = new byte[(7 * length + 7) / 8];
        for (int bit = 0; bit < 7 * length; ++bit) {
            if ((id[position + bit / 7] >>> bit % 7 & 1) != 0) {
                packed[bit / 8] |= (byte) (1 << bit % 8);
            }
        }
        return packed;
    }

    private static byte[] unpack(final byte[] packed, final int offset, final int count) {
        final int length = 8 * count / 7;   // a trailing group of zeros is padding
        final byte[] id = new byte[length];
        for (int bit = 0; bit < 7 * length; ++bit) {
            if ((packed[offset + bit / 8] >>> bit % 8 & 1) != 0) {
                id[bit / 7] |= (byte) (1 << bit % 7);
            }
        }
        return length > 0 && id[length - 1] == 0 ? Arrays.copyOf(id, length - 1) : id;
    }
}
