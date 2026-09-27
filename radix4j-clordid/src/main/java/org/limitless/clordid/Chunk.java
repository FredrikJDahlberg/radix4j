package org.limitless.clordid;

import org.limitless.fsmp4j.BlockFlyweight;

import java.lang.foreign.MemorySegment;

/**
 * Container block of one size class, accessed as 16-bit values or 64-bit words.
 */
final class Chunk extends BlockFlyweight {

    private final int length;

    Chunk(final int length) {
        this.length = length;
    }

    @Override
    public int encodedLength() {
        return length;
    }

    int value(final int index) {
        return nativeShort(index << 1) & 0xffff;
    }

    void value(final int index, final int value) {
        nativeShort(index << 1, (short) value);
    }

    long word(final int index) {
        return nativeLong(index << 3);
    }

    void word(final int index, final long word) {
        nativeLong(index << 3, word);
    }

    /**
     * Move 16-bit values within the block, the ranges may overlap
     * @param from source index
     * @param to destination index
     * @param count number of values
     */
    void move(final int from, final int to, final int count) {
        if (count > 0) {
            final MemorySegment segment = memorySegment();
            MemorySegment.copy(segment, fieldOffset(from << 1), segment, fieldOffset(to << 1), (long) count << 1);
        }
    }

    /**
     * Copy 16-bit values to another block
     * @param from source index
     * @param target target block
     * @param to target index
     * @param count number of values
     */
    void copy(final int from, final Chunk target, final int to, final int count) {
        if (count > 0) {
            MemorySegment.copy(memorySegment(), fieldOffset(from << 1),
                target.memorySegment(), target.fieldOffset(to << 1), (long) count << 1);
        }
    }
}
