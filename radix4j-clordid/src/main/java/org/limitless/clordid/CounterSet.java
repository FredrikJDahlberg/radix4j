package org.limitless.clordid;

import java.lang.foreign.Arena;
import java.util.Arrays;

/**
 * Append-only set of (prefix, number) pairs, one generation of a {@link ClientOrderIdSet}.
 * <p>
 * Numbers are split into chunks of 2^16 values as in roaring bitmaps. A directory maps (prefix id, chunk)
 * to a container holding the low 16 bits of the numbers in the chunk. The directory and the prefix table are
 * {@link LinearIndex} tables in pages, so they grow without rehashing. The prefixes come from
 * {@link ClientOrderIdSet}, which checks their length. The containers are:
 * <ul>
 *   <li>run: sorted (start, end - start) pairs, 4 bytes per run</li>
 *   <li>array: sorted values, 2 bytes per value</li>
 *   <li>bitmap: 8 KB, one bit per value</li>
 *   <li>Elias-Fano: the low L bits of each value packed, the high bits as gaps in unary, about L + 2 bits per
 *       value with L = log2(65536 / values); for numbers too far apart for runs or arrays</li>
 * </ul>
 * Containers live in off-heap {@link BlockPools} of 16 to 8192 bytes, in steps of 1.5 and 2.
 * A container that outgrows its block moves to the kind taking the smallest block for its contents, Elias-Fano
 * only when its block is smaller than the others. Nothing is
 * removed: {@link #close()} frees the whole generation at once.
 */
final class CounterSet implements AutoCloseable {

    static final int ADDED = 1;
    static final int DUPLICATE = 0;
    static final int MISSING = -1;

    private static final int CHUNK_BITS = 16;
    private static final int LOW_MASK = (1 << CHUNK_BITS) - 1;
    private static final int RUN = 0;
    private static final int ARRAY = 1;
    private static final int BITMAP = 2;
    private static final int ELIAS_FANO = 3;
    private static final int[] BLOCK_BYTES = {
        16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192
    };
    private static final int BITMAP_BYTES = 8192;
    private static final int BITMAP_WORDS = BITMAP_BYTES / Long.BYTES;

    // meta: kind (2 bits), size class (5 bits), count (17 bits): runs, values or bits set

    // Elias-Fano header, 16-bit values of word 0: low bits per value, words of high bits, last value. Value i
    // sets high bit (value >>> low) + i in the words after the header, its low bits follow the high words.
    private static final int EF_LOW = 0;
    private static final int EF_HIGH_WORDS = 1;
    private static final int EF_LAST = 2;
    private static final int CLASS_SHIFT = 2;
    private static final int CLASS_MASK = 0x1f;
    private static final int COUNT_SHIFT = 7;

    // directory fields per container: chunk of the numbers, prefix id, block address and meta
    private static final int KEY_LOW = 0;
    private static final int KEY_HIGH = 1;
    private static final int OWNER = 2;
    private static final int ADDRESS = 3;
    private static final int META = 4;

    private final Arena arena = Arena.ofShared();
    private final BlockPools<Chunk> blocks;
    private final PrefixTable prefixes = new PrefixTable();
    private final LinearIndex directory = new LinearIndex(5);
    private final long[] bits = new long[BITMAP_WORDS];
    private long size;

    /**
     * Constructs a set in a shared arena
     * @param segmentBytes bytes per segment of each block size, at least 8192
     * @throws IllegalArgumentException segments smaller than a bitmap or too large for 16-bit block indices
     */
    CounterSet(final int segmentBytes) {
        blocks = new BlockPools<>(arena, segmentBytes, BLOCK_BYTES, Chunk::new);
    }

    /**
     * Add a number
     * @param prefix prefix bytes
     * @param position prefix start
     * @param length prefix length, at most {@value PrefixTable#MAX_LENGTH}
     * @param number non-negative number
     * @return true when added, false when already present
     * @throws IllegalStateException block address space exhausted
     */
    boolean add(final byte[] prefix, final int position, final int length, final long number) {
        return add(prefixes.add(prefix, position, length), number, true) == ADDED;
    }

    /**
     * Add a number to the container of its prefix and chunk if there is one
     * @param prefix prefix bytes
     * @param position prefix start
     * @param length prefix length
     * @param number non-negative number
     * @return {@link #ADDED}, {@link #DUPLICATE}, or {@link #MISSING} when there is no container
     */
    int addExisting(final byte[] prefix, final int position, final int length, final long number) {
        final int owner = prefixes.find(prefix, position, length);
        return owner < 0 ? MISSING : add(owner, number, false);
    }

    private int add(final int owner, final long number, final boolean create) {
        final long key = number >>> CHUNK_BITS;
        final int low = (int) number & LOW_MASK;
        final int hash = hash(owner, key);
        final int slot = find(hash, owner, key);
        if (slot < 0) {
            if (!create) {
                return MISSING;
            }
            final Chunk chunk = blocks.allocate(0);
            chunk.value(0, low);
            chunk.value(1, 0);
            insert(hash, owner, key, BlockPools.address(chunk), meta(RUN, 0, 1));
            ++size;
            return ADDED;
        }
        final int meta = field(slot, META);
        final int sizeClass = meta >>> CLASS_SHIFT & CLASS_MASK;
        final Chunk chunk = blocks.get(sizeClass, field(slot, ADDRESS));
        final boolean added = switch (meta & 3) {
            case RUN -> addRun(slot, chunk, sizeClass, meta >>> COUNT_SHIFT, low);
            case ARRAY -> addArray(slot, chunk, sizeClass, meta >>> COUNT_SHIFT, low);
            case ELIAS_FANO -> addEliasFano(slot, chunk, sizeClass, meta >>> COUNT_SHIFT, low);
            default -> addBit(slot, chunk, meta, low);
        };
        if (added) {
            ++size;
            return ADDED;
        }
        return DUPLICATE;
    }

    /**
     * Check if a number is present
     * @param prefix prefix bytes
     * @param position prefix start
     * @param length prefix length
     * @param number non-negative number
     * @return true when present
     */
    boolean contains(final byte[] prefix, final int position, final int length, final long number) {
        final int id = prefixes.find(prefix, position, length);
        if (id < 0) {
            return false;
        }
        final long key = number >>> CHUNK_BITS;
        final int slot = find(hash(id, key), id, key);
        if (slot < 0) {
            return false;
        }
        final int meta = field(slot, META);
        final int count = meta >>> COUNT_SHIFT;
        final Chunk chunk = blocks.get(meta >>> CLASS_SHIFT & CLASS_MASK, field(slot, ADDRESS));
        final int low = (int) number & LOW_MASK;
        return switch (meta & 3) {
            case RUN -> {
                final int run = lastRun(chunk, count, low);
                yield run >= 0 && low - chunk.value(run << 1) <= chunk.value((run << 1) + 1);
            }
            case ARRAY -> search(chunk, count, low) >= 0;
            case ELIAS_FANO -> containsEliasFano(chunk, low);
            default -> (chunk.word(low >>> 6) & 1L << low) != 0;
        };
    }

    /**
     * Number of numbers in the set
     * @return size
     */
    long size() {
        return size;
    }

    /**
     * Number of distinct prefixes
     * @return prefixes
     */
    int prefixes() {
        return prefixes.size();
    }

    /**
     * Number of containers
     * @return containers
     */
    int containers() {
        return directory.size();
    }

    /**
     * Off-heap bytes allocated by the pools, including unused blocks in their segments
     * @return bytes
     */
    long offHeapBytes() {
        return blocks.allocatedBytes();
    }

    /**
     * Off-heap bytes in blocks holding containers
     * @return bytes
     */
    long usedBytes() {
        return blocks.usedBytes();
    }

    /**
     * Heap bytes of the directory and the prefix table
     * @return bytes
     */
    long heapBytes() {
        return directory.heapBytes() + prefixes.heapBytes() + (long) Long.BYTES * BITMAP_WORDS;
    }

    /**
     * Free the off-heap memory of the whole set
     */
    @Override
    public void close() {
        arena.close();
    }

    private boolean addRun(final int slot, final Chunk chunk, final int sizeClass, final int count, final int value) {
        final int run = lastRun(chunk, count, value);
        final int next = run + 1;
        final boolean joinsNext = next < count && chunk.value(next << 1) == value + 1;
        if (run >= 0) {
            final int start = chunk.value(run << 1);
            final int end = start + chunk.value((run << 1) + 1);
            if (value <= end) {
                return false;
            }
            if (value == end + 1) {
                if (joinsNext) {
                    final int nextEnd = value + 1 + chunk.value((next << 1) + 1);
                    chunk.value((run << 1) + 1, nextEnd - start);
                    chunk.move((next + 1) << 1, next << 1, (count - next - 1) << 1);
                    field(slot, META, meta(RUN, sizeClass, count - 1));
                } else {
                    chunk.value((run << 1) + 1, value - start);
                }
                return true;
            }
        }
        if (joinsNext) {
            chunk.value(next << 1, value);
            chunk.value((next << 1) + 1, chunk.value((next << 1) + 1) + 1);
            return true;
        }
        if ((count + 1) << 2 > blockLength(sizeClass)) {
            return convert(slot, chunk, RUN, sizeClass, count, value, next);
        }
        chunk.move(next << 1, (next + 1) << 1, (count - next) << 1);
        chunk.value(next << 1, value);
        chunk.value((next << 1) + 1, 0);
        field(slot, META, meta(RUN, sizeClass, count + 1));
        return true;
    }

    private boolean addArray(final int slot, final Chunk chunk, final int sizeClass, final int count, final int value) {
        final int index = search(chunk, count, value);
        if (index >= 0) {
            return false;
        }
        final int insert = -index - 1;
        if ((count + 1) << 1 > blockLength(sizeClass)) {
            return convert(slot, chunk, ARRAY, sizeClass, count, value, insert);
        }
        chunk.move(insert, insert + 1, count - insert);
        chunk.value(insert, value);
        field(slot, META, meta(ARRAY, sizeClass, count + 1));
        return true;
    }

    private boolean addBit(final int slot, final Chunk chunk, final int meta, final int value) {
        final long word = chunk.word(value >>> 6);
        final long bit = 1L << value;
        if ((word & bit) != 0) {
            return false;
        }
        chunk.word(value >>> 6, word | bit);
        field(slot, META, meta + (1 << COUNT_SHIFT));
        return true;
    }

    private boolean addEliasFano(final int slot, final Chunk chunk, final int sizeClass, final int count,
                                 final int value) {
        final int low = chunk.value(EF_LOW);
        final int highWords = chunk.value(EF_HIGH_WORDS);
        if (value > chunk.value(EF_LAST)) {
            final int position = (value >>> low) + count;
            final int lowBit = (1 + highWords << 6) + count * low;
            if (position < highWords << 6 && lowBit + low <= blockLength(sizeClass) << 3) {
                final int word = 1 + (position >>> 6);
                chunk.word(word, chunk.word(word) | 1L << position);
                lowBits(chunk, lowBit, low, value & (1 << low) - 1);
                chunk.value(EF_LAST, value);
                field(slot, META, meta(ELIAS_FANO, sizeClass, count + 1));
                return true;
            }
        } else if (containsEliasFano(chunk, value)) {
            return false;
        }
        return convert(slot, chunk, ELIAS_FANO, sizeClass, count, value, 0);
    }

    private static boolean containsEliasFano(final Chunk chunk, final int value) {
        final int low = chunk.value(EF_LOW);
        final int high = value >>> low;
        final int lowValue = value & (1 << low) - 1;
        final int lowStart = 1 + chunk.value(EF_HIGH_WORDS) << 6;
        for (int position = bucketStart(chunk, high);
             (chunk.word(1 + (position >>> 6)) & 1L << position) != 0; ++position) {
            final int found = lowBits(chunk, lowStart + (position - high) * low, low);
            if (found >= lowValue) {
                return found == lowValue;
            }
        }
        return false;
    }

    /**
     * Position of the first high bit of the values with a high part, just after the high-th clear bit. The high
     * words always hold a clear bit for every high part, so the scan ends within them.
     */
    private static int bucketStart(final Chunk chunk, final int high) {
        if (high == 0) {
            return 0;
        }
        int remaining = high;
        for (int word = 0; ; ++word) {
            long zeros = ~chunk.word(1 + word);
            final int count = Long.bitCount(zeros);
            if (remaining <= count) {
                return (word << 6) + select(zeros, remaining) + 1;
            }
            remaining -= count;
        }
    }

    /**
     * Position of the n-th set bit of a word, narrowed down by halves
     */
    private static int select(long word, int n) {
        int position = 0;
        for (int width = 32; width >= 8; width >>>= 1) {
            final int count = Long.bitCount(word & (1L << width) - 1);
            if (n > count) {
                n -= count;
                word >>>= width;
                position += width;
            }
        }
        while (--n > 0) {
            word &= word - 1;
        }
        return position + Long.numberOfTrailingZeros(word);
    }

    private static int lowBits(final Chunk chunk, final int bit, final int width) {
        if (width == 0) {
            return 0;
        }
        final int word = bit >>> 6;
        final int shift = bit & 63;
        long bits = chunk.word(word) >>> shift;
        if (shift + width > 64) {
            bits |= chunk.word(word + 1) << 64 - shift;
        }
        return (int) bits & (1 << width) - 1;
    }

    /**
     * Write low bits to a cleared position
     */
    private static void lowBits(final Chunk chunk, final int bit, final int width, final int value) {
        if (width == 0) {
            return;
        }
        final int word = bit >>> 6;
        final int shift = bit & 63;
        chunk.word(word, chunk.word(word) | (long) value << shift);
        if (shift + width > 64) {
            chunk.word(word + 1, chunk.word(word + 1) | (long) value >>> 64 - shift);
        }
    }

    /**
     * Move a full container and the new value to the kind taking the smallest block for them. A run or array
     * container keeping its kind is copied, other containers go through a bitmap.
     * @param index where the value goes: the new run of a run container, the value of an array container
     */
    private boolean convert(final int slot, final Chunk chunk, final int kind, final int sizeClass,
                            final int count, final int value, final int index) {
        int cardinality = count + 1;
        int runs;
        if (kind == ELIAS_FANO) {
            Arrays.fill(bits, 0);
            readEliasFano(chunk, count);
            bits[value >>> 6] |= 1L << value;
            runs = countRuns();
        } else if (kind == RUN) {
            for (int run = 0; run < count; ++run) {
                cardinality += chunk.value((run << 1) + 1);
            }
            runs = count + 1;   // the value starts a run of its own
        } else {
            runs = 2;   // the runs of the array plus one for the value, less the runs it joins
            for (int i = 1; i < count; ++i) {
                if (chunk.value(i) != chunk.value(i - 1) + 1) {
                    ++runs;
                }
            }
            if (index > 0 && chunk.value(index - 1) + 1 == value) {
                --runs;
            }
            if (index < count && chunk.value(index) == value + 1) {
                --runs;
            }
        }
        final int runBytes = runs << 2;
        final int arrayBytes = cardinality << 1;
        int newKind;
        int newCount;
        int bytes;
        if (runBytes <= arrayBytes && runBytes < BITMAP_BYTES) {
            newKind = RUN;
            newCount = runs;
            bytes = runBytes;
        } else if (arrayBytes < BITMAP_BYTES) {
            newKind = ARRAY;
            newCount = cardinality;
            bytes = arrayBytes;
        } else {
            newKind = BITMAP;
            newCount = cardinality;
            bytes = BITMAP_BYTES;
        }
        final int eliasFanoBytes = eliasFanoBytes(cardinality);
        if (eliasFanoBytes < bytes && sizeClass(eliasFanoBytes) < sizeClass(bytes)) {
            newKind = ELIAS_FANO;
            newCount = cardinality;
            bytes = eliasFanoBytes;
        }
        final int newClass = sizeClass(bytes);
        final Chunk target = blocks.allocate(newClass);
        if (newKind == kind && kind != ELIAS_FANO) {
            final int width = kind == RUN ? 2 : 1;
            chunk.copy(0, target, 0, index * width);
            chunk.copy(index * width, target, (index + 1) * width, (count - index) * width);
            if (kind == RUN) {
                target.value(index << 1, value);
                target.value((index << 1) + 1, 0);
            } else {
                target.value(index, value);
            }
        } else {
            if (kind == RUN) {
                Arrays.fill(bits, 0);
                for (int run = 0; run < count; ++run) {
                    final int start = chunk.value(run << 1);
                    setRange(start, start + chunk.value((run << 1) + 1));
                }
                bits[value >>> 6] |= 1L << value;
            } else if (kind == ARRAY) {
                Arrays.fill(bits, 0);
                for (int i = 0; i < count; ++i) {
                    final int bit = chunk.value(i);
                    bits[bit >>> 6] |= 1L << bit;
                }
                bits[value >>> 6] |= 1L << value;
            }
            switch (newKind) {
                case RUN -> writeRuns(target);
                case ARRAY -> writeValues(target);
                case ELIAS_FANO -> writeEliasFano(target, newClass, cardinality);
                default -> {
                    for (int i = 0; i < BITMAP_WORDS; ++i) {
                        target.word(i, bits[i]);
                    }
                }
            }
        }
        blocks.free(sizeClass, chunk);
        field(slot, ADDRESS, BlockPools.address(target));
        field(slot, META, meta(newKind, newClass, newCount));
        return true;
    }

    private void writeRuns(final Chunk target) {
        int run = 0;
        int start = nextBit(0, 0);
        while (start >= 0) {
            final int end = nextBit(start, -1L) - 1;
            target.value(run << 1, start);
            target.value((run << 1) + 1, end - start);
            ++run;
            start = end + 1 < (1 << CHUNK_BITS) ? nextBit(end + 1, 0) : -1;
        }
    }

    /**
     * Write the bitmap as an Elias-Fano container, with as many high words as leave the block room for the most
     * values
     */
    private void writeEliasFano(final Chunk target, final int sizeClass, final int cardinality) {
        final int low = eliasFanoLow(cardinality);
        final int highParts = (1 << CHUNK_BITS) >>> low;
        final int words = blockLength(sizeClass) >>> 3;
        int capacity = ((words - 1 << 6) - highParts) / (low + 1);
        int highWords = capacity + highParts + 63 >>> 6;
        while (1 + highWords + (capacity * low + 63 >>> 6) > words) {
            highWords = --capacity + highParts + 63 >>> 6;
        }
        for (int i = 0; i < words; ++i) {
            target.word(i, 0);
        }
        target.value(EF_LOW, low);
        target.value(EF_HIGH_WORDS, highWords);
        final int lowStart = 1 + highWords << 6;
        final int lowMask = (1 << low) - 1;
        int index = 0;
        int value = 0;
        for (int i = 0; i < BITMAP_WORDS; ++i) {
            for (long word = bits[i]; word != 0; word &= word - 1) {
                value = i << 6 | Long.numberOfTrailingZeros(word);
                final int position = (value >>> low) + index;
                target.word(1 + (position >>> 6), target.word(1 + (position >>> 6)) | 1L << position);
                lowBits(target, lowStart + index * low, low, value & lowMask);
                ++index;
            }
        }
        target.value(EF_LAST, value);
    }

    private void readEliasFano(final Chunk chunk, final int count) {
        final int low = chunk.value(EF_LOW);
        final int lowStart = 1 + chunk.value(EF_HIGH_WORDS) << 6;
        int index = 0;
        for (int i = 0; index < count; ++i) {
            for (long word = chunk.word(1 + i); word != 0; word &= word - 1) {
                final int position = i << 6 | Long.numberOfTrailingZeros(word);
                final int value = (position - index) << low | lowBits(chunk, lowStart + index * low, low);
                bits[value >>> 6] |= 1L << value;
                ++index;
            }
        }
    }

    private int countRuns() {
        int runs = 0;
        long carry = 0;
        for (int i = 0; i < BITMAP_WORDS; ++i) {
            final long word = bits[i];
            runs += Long.bitCount(word & ~(word << 1 | carry));
            carry = word >>> 63;
        }
        return runs;
    }

    private void writeValues(final Chunk target) {
        int index = 0;
        for (int i = 0; i < BITMAP_WORDS; ++i) {
            for (long word = bits[i]; word != 0; word &= word - 1) {
                target.value(index++, i << 6 | Long.numberOfTrailingZeros(word));
            }
        }
    }

    /**
     * Next set bit, or clear bit with invert = -1, from index
     * @return bit index, or -1 (set) or 65536 (clear) when there is none
     */
    private int nextBit(final int from, final long invert) {
        int i = from >>> 6;
        long word = (bits[i] ^ invert) & -1L << from;
        while (word == 0) {
            if (++i == BITMAP_WORDS) {
                return invert == 0 ? -1 : 1 << CHUNK_BITS;
            }
            word = bits[i] ^ invert;
        }
        return i << 6 | Long.numberOfTrailingZeros(word);
    }

    private void setRange(final int start, final int end) {
        final int first = start >>> 6;
        final int last = end >>> 6;
        final long startMask = -1L << start;
        final long endMask = -1L >>> 63 - (end & 63);
        if (first == last) {
            bits[first] |= startMask & endMask;
            return;
        }
        bits[first] |= startMask;
        for (int i = first + 1; i < last; ++i) {
            bits[i] = -1L;
        }
        bits[last] |= endMask;
    }

    /**
     * Last run starting at or before value
     * @return run index or -1
     */
    private static int lastRun(final Chunk chunk, final int count, final int value) {
        int low = 0;
        int high = count - 1;
        while (low <= high) {
            final int middle = low + high >>> 1;
            if (chunk.value(middle << 1) <= value) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return high;
    }

    /**
     * Binary search of a sorted array container
     * @return index, or -(insertion point) - 1
     */
    private static int search(final Chunk chunk, final int count, final int value) {
        int low = 0;
        int high = count - 1;
        while (low <= high) {
            final int middle = low + high >>> 1;
            final int found = chunk.value(middle);
            if (found < value) {
                low = middle + 1;
            } else if (found > value) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        return -low - 1;
    }

    /**
     * Find the container of a chunk
     * @return container or -1
     */
    private int find(final int hash, final int owner, final long key) {
        for (int slot = directory.first(hash); slot >= 0; slot = directory.next(slot)) {
            if (directory.hash(slot) == hash && field(slot, KEY_LOW) == (int) key &&
                field(slot, KEY_HIGH) == (int) (key >>> 32) && field(slot, OWNER) == owner) {
                return slot;
            }
        }
        return -1;
    }

    private void insert(final int hash, final int owner, final long key, final int address, final int meta) {
        final int slot = directory.add(hash);
        field(slot, KEY_LOW, (int) key);
        field(slot, KEY_HIGH, (int) (key >>> 32));
        field(slot, OWNER, owner);
        field(slot, ADDRESS, address);
        field(slot, META, meta);
    }

    private int field(final int slot, final int field) {
        return directory.field(slot, field);
    }

    private void field(final int slot, final int field, final int value) {
        directory.field(slot, field, value);
    }

    private static int meta(final int kind, final int sizeClass, final int count) {
        return count << COUNT_SHIFT | sizeClass << CLASS_SHIFT | kind;
    }

    /**
     * Low bits per value of an Elias-Fano container: log2(65536 / values)
     */
    private static int eliasFanoLow(final int cardinality) {
        return 31 - Integer.numberOfLeadingZeros((1 << CHUNK_BITS) / cardinality);
    }

    /**
     * Smallest Elias-Fano container: header word, high words and low bits
     */
    private static int eliasFanoBytes(final int cardinality) {
        final int low = eliasFanoLow(cardinality);
        final int highWords = cardinality + ((1 << CHUNK_BITS) >>> low) + 63 >>> 6;
        return 1 + highWords + (cardinality * low + 63 >>> 6) << 3;
    }

    private static int blockLength(final int sizeClass) {
        return BLOCK_BYTES[sizeClass];
    }

    /**
     * Smallest size class holding a number of bytes, at most {@value #BITMAP_BYTES}
     */
    private static int sizeClass(final int bytes) {
        int sizeClass = 0;
        while (BLOCK_BYTES[sizeClass] < bytes) {
            ++sizeClass;
        }
        return sizeClass;
    }

    private static int hash(final int owner, final long key) {
        long hash = (key + owner) * 0x9e3779b97f4a7c15L + owner;
        hash ^= hash >>> 32;
        hash *= 0xd6e8feb86659fd93L;
        return (int) (hash ^ hash >>> 32);
    }
}
