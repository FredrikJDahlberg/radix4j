package org.limitless.radix4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.limitless.radix4j.ByteUtils.bytePosition;
import static org.limitless.radix4j.ByteUtils.findBytePosition;

public class ByteUtilsTest {

    @Test
    public void bytesPosition() {
        assertEquals(0, bytePosition((byte) 1, 0x00000001));
        assertEquals(1, bytePosition((byte) 9, 0x00000900));
        assertEquals(2, bytePosition((byte) 37, 0x00250000));
        assertEquals(3, bytePosition((byte) 7, 0x07000000));
        assertEquals(-1, bytePosition((byte) 9, 0x07000000));

        assertEquals(0, bytePosition((byte) 1, 0x0000000000000001L));
        assertEquals(1, bytePosition((byte) 1, 0x0000000000000100L));
        assertEquals(2, bytePosition((byte) 1, 0x0000000000010000L));
        assertEquals(3, bytePosition((byte) 1, 0x0000000001000000L));
        assertEquals(4, bytePosition((byte) 1, 0x0000000100000000L));
        assertEquals(5, bytePosition((byte) 1, 0x0000010000000000L));
        assertEquals(6, bytePosition((byte) 1, 0x0001000000000000L));
        assertEquals(7, bytePosition((byte) 1, 0x0100000000000000L));
        assertEquals(-1, bytePosition((byte) 10, 0x0100000000000000L));
    }

    @Test
    public void findBytesPosition() {
        assertEquals(0, findBytePosition((byte) 1, 0x0000000000000001L));
        assertEquals(1, findBytePosition((byte) 1, 0x0000000000000100L));
        assertEquals(2, findBytePosition((byte) 1, 0x0000000000010000L));
        assertEquals(3, findBytePosition((byte) 1, 0x0000000001000000L));
        assertEquals(4, findBytePosition((byte) 1, 0x0000000100000000L));
        assertEquals(5, findBytePosition((byte) 1, 0x0000010000000000L));
        assertEquals(6, findBytePosition((byte) 1, 0x0001000000000000L));
        assertEquals(7, findBytePosition((byte) 1, 0x0100000000000000L));
        assertEquals(-1, findBytePosition((byte) 10, 0x0100000000000000L));
    }
}
