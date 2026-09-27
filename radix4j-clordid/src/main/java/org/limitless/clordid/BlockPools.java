package org.limitless.clordid;

import org.limitless.fsmp4j.BlockFlyweight;
import org.limitless.fsmp4j.BlockPool;
import org.limitless.fsmp4j.ByteUtils;

import java.lang.foreign.Arena;
import java.util.function.IntFunction;

/**
 * fsmp4j pools of blocks in size classes, all in one arena. A block address is 32 bits: the segment number plus
 * one in the upper 16 bits and the block in the lower 16, so 0 is never an address. Each size class has two
 * flyweights: {@link #get} positions one and {@link #allocate} the other, so a block and a new one are usable at
 * the same time.
 */
final class BlockPools<T extends BlockFlyweight> {

    private final BlockPool<T>[] pools;
    private final T[] blocks;
    private final T[] allocated;

    /**
     * Constructs the pools
     * @param lengths block length of each size class, ascending
     * @param factory flyweight of a block length
     * @throws IllegalArgumentException segments smaller than the largest block or too large for 16-bit block indices
     */
    @SuppressWarnings("unchecked")
    BlockPools(final Arena arena, final int segmentBytes, final int[] lengths, final IntFunction<T> factory) {
        if (segmentBytes < lengths[lengths.length - 1] || segmentBytes / lengths[0] > 1 << 16) {
            throw new IllegalArgumentException("invalid segment size: " + segmentBytes);
        }
        pools = new BlockPool[lengths.length];
        blocks = (T[]) new BlockFlyweight[lengths.length];
        allocated = (T[]) new BlockFlyweight[lengths.length];
        for (int sizeClass = 0; sizeClass < lengths.length; ++sizeClass) {
            final int length = lengths[sizeClass];
            pools[sizeClass] = BlockPool.builder(arena, () -> factory.apply(length))
                .blocksPerSegment(segmentBytes / length)
                .build();
            blocks[sizeClass] = factory.apply(length);
            allocated[sizeClass] = factory.apply(length);
        }
    }

    T get(final int sizeClass, final int address) {
        return pools[sizeClass].get(ByteUtils.pack(address >>> 16, address & 0xffff), blocks[sizeClass]);
    }

    T allocate(final int sizeClass) {
        return pools[sizeClass].allocate(allocated[sizeClass]);
    }

    void free(final int sizeClass, final T block) {
        pools[sizeClass].free(block);
    }

    /**
     * Address of a block
     * @throws IllegalStateException block address space exhausted
     */
    static int address(final BlockFlyweight block) {
        final int segment = block.segment() + 1;
        if (segment > 0xffff) {
            throw new IllegalStateException("block address space exhausted");
        }
        return segment << 16 | block.block();
    }

    /**
     * Bytes allocated by the pools, including unused blocks in their segments
     */
    long allocatedBytes() {
        long bytes = 0;
        for (final BlockPool<T> pool : pools) {
            bytes += pool.allocatedBytes();
        }
        return bytes;
    }

    /**
     * Bytes in blocks in use
     */
    long usedBytes() {
        long bytes = 0;
        for (final BlockPool<T> pool : pools) {
            bytes += pool.blocksInUse() * pool.blockLength();
        }
        return bytes;
    }
}
