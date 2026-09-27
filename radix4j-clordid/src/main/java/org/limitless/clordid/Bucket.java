package org.limitless.clordid;

import org.limitless.fsmp4j.BlockFlyweight;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Bucket block of a {@link FallbackSet} in one size class, laid out as a slotted page: a header with the address
 * of the next block in the chain, the entry count and the start of the keys, then a slot per entry holding its
 * fingerprint and key length, and the keys stored from the end of the block backwards. A lookup scans the
 * packed slots and reads a key only when its fingerprint matches.
 * <p>
 * Entries move between blocks as a fingerprint byte, a length byte and the key.
 */
final class Bucket extends BlockFlyweight {

    static final int ENTRY_HEADER = 2;

    private static final int NEXT_OFFSET = 0;
    private static final int COUNT_OFFSET = NEXT_OFFSET + Long.BYTES;
    private static final int TOP_OFFSET = COUNT_OFFSET + Short.BYTES;
    static final int SLOTS_OFFSET = TOP_OFFSET + Short.BYTES;

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final int FINGERPRINT_SHIFT = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? 0 : 8;
    private static final int LENGTH_SHIFT = 8 - FINGERPRINT_SHIFT;

    private final int bytes;

    Bucket(final int bytes) {
        this.bytes = bytes;
    }

    @Override
    public int encodedLength() {
        return bytes;
    }

    /**
     * Bytes available for entries, two per slot plus the keys
     */
    int capacity() {
        return bytes - SLOTS_OFFSET;
    }

    /**
     * Bytes used by entries, two per slot plus the keys
     */
    int used() {
        return (count() << 1) + bytes - top();
    }

    Bucket reset() {
        nativeLong(NEXT_OFFSET, 0);
        nativeShort(COUNT_OFFSET, (short) 0);
        nativeShort(TOP_OFFSET, (short) bytes);
        return this;
    }

    long next() {
        return nativeLong(NEXT_OFFSET);
    }

    void next(final long address) {
        nativeLong(NEXT_OFFSET, address);
    }

    private int count() {
        return nativeShort(COUNT_OFFSET);
    }

    private int top() {
        return nativeShort(TOP_OFFSET) & 0xffff;
    }

    /**
     * Look for a key
     * @return true when found
     */
    boolean contains(final byte fingerprint, final byte[] key, final int position, final int length) {
        final int count = count();
        int offset = bytes;
        for (int i = 0; i < count; ++i) {
            final int slot = nativeShort(SLOTS_OFFSET + (i << 1));
            final int keyLength = slot >>> LENGTH_SHIFT & 0xff;
            offset -= keyLength;
            if ((byte) (slot >>> FINGERPRINT_SHIFT) == fingerprint && keyLength == length &&
                keyEquals(offset, key, position, length)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Append an entry, the caller checks that it fits
     */
    void append(final byte fingerprint, final byte[] key, final int position, final int length) {
        final int count = count();
        final int top = top() - length;
        if (length > 0) {
            nativeByteArray(position, key, top, length);
        }
        nativeByte(SLOTS_OFFSET + (count << 1), fingerprint);
        nativeByte(SLOTS_OFFSET + (count << 1) + 1, (byte) length);
        nativeShort(COUNT_OFFSET, (short) (count + 1));
        nativeShort(TOP_OFFSET, (short) top);
    }

    /**
     * Append entries of a fingerprint byte, a length byte and the key, the caller checks that they fit
     */
    void append(final byte[] entries, final int offset, final int count) {
        for (int entry = offset; entry < offset + count; ) {
            final int length = entries[entry + 1] & 0xff;
            append(entries[entry], entries, entry + ENTRY_HEADER, length);
            entry += ENTRY_HEADER + length;
        }
    }

    /**
     * Copy all entries as a fingerprint byte, a length byte and the key
     * @return bytes written, {@link #used()}
     */
    int read(final byte[] target, final int offset) {
        final int count = count();
        int key = bytes;
        int out = offset;
        for (int i = 0; i < count; ++i) {
            final int length = nativeByte(SLOTS_OFFSET + (i << 1) + 1) & 0xff;
            key -= length;
            target[out] = nativeByte(SLOTS_OFFSET + (i << 1));
            target[out + 1] = (byte) length;
            if (length > 0) {
                nativeByteArray(key, length, out + ENTRY_HEADER, target);
            }
            out += ENTRY_HEADER + length;
        }
        return out - offset;
    }

    /**
     * Compare the key at offset, eight bytes at a time
     */
    private boolean keyEquals(final int offset, final byte[] key, final int position, final int length) {
        int i = 0;
        for (; i + Long.BYTES <= length; i += Long.BYTES) {
            if (nativeLong(offset + i) != (long) LONGS.get(key, position + i)) {
                return false;
            }
        }
        for (; i < length; ++i) {
            if (nativeByte(offset + i) != key[position + i]) {
                return false;
            }
        }
        return true;
    }

    static long word(final byte[] key, final int position) {
        return (long) LONGS.get(key, position);
    }
}
