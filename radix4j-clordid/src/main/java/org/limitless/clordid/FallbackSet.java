package org.limitless.clordid;

import java.lang.foreign.Arena;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Append-only sets of byte strings, one per scope, for the ids of a {@link ClientOrderIdSet} that match no pattern.
 * <p>
 * Each scope has a linear hash table of buckets. A bucket is one block holding its keys, each with a slot of a
 * fingerprint byte and a length byte, in the smallest of the size classes from 32 to 1024 bytes that fits; a full
 * bucket moves to the next size class, and only a bucket outgrowing the largest class chains another block. When a
 * scope's keys average {@value #TARGET_BYTES} bytes per bucket, an add splits the next bucket in turn, moving only
 * that bucket's keys. The table so grows one bucket at a time, and the bucket directory grows in 8 KB
 * {@link Chunk}s of {@value #DIRECTORY_ENTRIES} entries, so no add ever rehashes the whole table. Buckets and
 * directory blocks come from {@link BlockPools} in one arena, shared by all scopes. Only an array of directory
 * block addresses per scope is on the heap, so adds allocate no heap memory beyond growing those arrays. The hash
 * is seeded per set. Nothing is removed: {@link #close()} frees all scopes at once.
 * <p>
 * The keys come from {@link ClientOrderIdSet}, which checks the scope and the length.
 */
final class FallbackSet implements AutoCloseable {

    static final int MAX_KEY_LENGTH = 255;
    static final int TARGET_BYTES = 96;
    static final int DIRECTORY_SHIFT = 10;
    static final int DIRECTORY_ENTRIES = 1 << DIRECTORY_SHIFT;

    private static final int MAX_SCOPES = ClientOrderIdSet.MAX_SCOPES;
    private static final int[] CLASS_BYTES = {32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024};
    private static final int MAX_CLASS = CLASS_BYTES.length - 1;
    private static final int DIRECTORY_MASK = DIRECTORY_ENTRIES - 1;

    private final Arena arena = Arena.ofShared();
    private final BlockPools<Bucket> buckets;
    private final BlockPools<Chunk> directoryPool;
    private final long seed = ThreadLocalRandom.current().nextLong();
    private final int targetBytes;

    // per scope: directory block addresses, level and split pointer of the linear hash table, key bytes and count
    private final int[][] directories = new int[MAX_SCOPES][];
    private final int[] directoryBlocks = new int[MAX_SCOPES];
    private final int[] levels = new int[MAX_SCOPES];
    private final int[] splits = new int[MAX_SCOPES];
    private final long[] bytes = new long[MAX_SCOPES];
    private final int[] sizes = new int[MAX_SCOPES];

    private byte[] entries = new byte[4 * CLASS_BYTES[MAX_CLASS]];
    private byte[] sides = new byte[256];
    private long tailAddress;      // last block of the chain searched by find, 0 for an empty bucket
    private long tailPrevious;     // the block before it, 0 when it is the first
    private long size;
    private long heapBytes;

    /**
     * Constructs a set in a shared arena
     * @param segmentBytes bytes per segment of each size class, at least 8192
     * @throws IllegalArgumentException invalid segment size
     */
    FallbackSet(final int segmentBytes) {
        this(segmentBytes, TARGET_BYTES);
    }

    /**
     * Constructs a set in a shared arena, splitting buckets at another average size
     * @param segmentBytes bytes per segment of each size class, at least 8192
     * @param targetBytes average bytes per bucket at which buckets are split
     * @throws IllegalArgumentException invalid segment size
     */
    FallbackSet(final int segmentBytes, final int targetBytes) {
        this.targetBytes = targetBytes;
        buckets = new BlockPools<>(arena, segmentBytes, CLASS_BYTES, Bucket::new);
        directoryPool = new BlockPools<>(arena, segmentBytes, new int[]{DIRECTORY_ENTRIES * Long.BYTES}, Chunk::new);
    }

    /**
     * Add a key
     * @param scope scope in 0..{@value #MAX_SCOPES} - 1
     * @param key bytes
     * @param position key start
     * @param length key length, at most {@value #MAX_KEY_LENGTH}
     * @return true when added, false when already present
     * @throws IllegalStateException block address space exhausted
     */
    boolean add(final int scope, final byte[] key, final int position, final int length) {
        assert length <= MAX_KEY_LENGTH;
        if (directories[scope] == null) {
            directories[scope] = new int[1];
            heapBytes += Integer.BYTES;
            directory(scope, 0, 0);
        }
        final long hash = hash(key, position, length);
        final int index = index(scope, hash);
        final byte fingerprint = fingerprint(hash);
        if (find(directory(scope, index), fingerprint, key, position, length)) {
            return false;
        }
        append(scope, index, fingerprint, key, position, length);
        ++sizes[scope];
        ++size;
        bytes[scope] += Bucket.ENTRY_HEADER + length;
        if (bytes[scope] > (long) targetBytes * buckets(scope)) {
            split(scope);
        }
        return true;
    }

    /**
     * Check if a key is present
     * @param scope scope in 0..{@value #MAX_SCOPES} - 1
     * @param key bytes
     * @param position key start
     * @param length key length
     * @return true when present
     */
    boolean contains(final int scope, final byte[] key, final int position, final int length) {
        if (directories[scope] == null) {
            return false;
        }
        final long hash = hash(key, position, length);
        return find(directory(scope, index(scope, hash)), fingerprint(hash), key, position, length);
    }

    /**
     * Number of keys in all scopes
     * @return size
     */
    long size() {
        return size;
    }

    /**
     * Number of keys in a scope
     * @param scope scope
     * @return size
     */
    int size(final int scope) {
        return sizes[scope];
    }

    /**
     * Number of buckets in a scope
     * @param scope scope
     * @return buckets
     */
    int buckets(final int scope) {
        return directories[scope] == null ? 0 : (1 << levels[scope]) + splits[scope];
    }

    /**
     * Off-heap bytes allocated by the pools, including unused blocks in their segments
     * @return bytes
     */
    long offHeapBytes() {
        return buckets.allocatedBytes() + directoryPool.allocatedBytes();
    }

    /**
     * Off-heap bytes in blocks holding buckets and directories
     * @return bytes
     */
    long usedBytes() {
        return buckets.usedBytes() + directoryPool.usedBytes();
    }

    /**
     * Heap bytes of the directory block addresses
     * @return bytes
     */
    long heapBytes() {
        return heapBytes;
    }

    /**
     * Free the off-heap memory of all scopes
     */
    @Override
    public void close() {
        arena.close();
    }

    /**
     * Look for a key in a bucket, remembering the last block of its chain and the block before it
     * @return true when found
     */
    private boolean find(final long head, final byte fingerprint, final byte[] key, final int position, final int length) {
        long previous = 0;
        long address = head;
        while (address != 0) {
            final Bucket block = get(address);
            if (block.contains(fingerprint, key, position, length)) {
                return true;
            }
            final long next = block.next();
            if (next == 0) {
                break;
            }
            previous = address;
            address = next;
        }
        tailPrevious = previous;
        tailAddress = address;
        return false;
    }

    /**
     * Append an entry to the last block of the bucket found by {@link #find}: in place when it fits, in the next
     * size class that fits, or in a new block chained to a block of the largest class
     */
    private void append(final int scope, final int index, final byte fingerprint,
                        final byte[] key, final int position, final int length) {
        final int entry = Bucket.ENTRY_HEADER + length;
        if (tailAddress == 0) {
            final int sizeClass = classFor(entry);
            final Bucket created = allocate(sizeClass);
            created.append(fingerprint, key, position, length);
            directory(scope, index, address(sizeClass, created));
            return;
        }
        final Bucket block = get(tailAddress);
        final int used = block.used();
        if (used + entry <= block.capacity()) {
            block.append(fingerprint, key, position, length);
            return;
        }
        final int sizeClass = sizeClass(tailAddress);
        if (sizeClass == MAX_CLASS) {
            final int chainedClass = classFor(entry);
            final Bucket chained = allocate(chainedClass);
            chained.append(fingerprint, key, position, length);
            block.next(address(chainedClass, chained));
            return;
        }
        block.read(entries, 0);
        final int grownClass = classFor(used + entry);
        final Bucket grown = allocate(grownClass);
        grown.append(entries, 0, used);
        grown.append(fingerprint, key, position, length);
        buckets.free(sizeClass, block);
        final long address = address(grownClass, grown);
        if (tailPrevious == 0) {
            directory(scope, index, address);
        } else {
            get(tailPrevious).next(address);
        }
    }

    /**
     * Split the next bucket of a scope: its keys stay, or move to a new bucket at the end of the table
     */
    private void split(final int scope) {
        final int level = levels[scope];
        final int split = splits[scope];

        // gather the entries of the bucket and free its blocks
        int count = 0;
        long address = directory(scope, split);
        while (address != 0) {
            final Bucket block = get(address);
            final int used = block.used();
            if (count + used > entries.length) {
                entries = Arrays.copyOf(entries, Math.max(entries.length << 1, count + used));
            }
            block.read(entries, count);
            count += used;
            final long next = block.next();
            buckets.free(sizeClass(address), block);
            address = next;
        }

        // decide where each entry goes
        int stayBytes = 0;
        int entryCount = 0;
        for (int entry = 0; entry < count; ++entryCount) {
            final int length = Bucket.ENTRY_HEADER + (entries[entry + 1] & 0xff);
            if (entryCount == sides.length) {
                sides = Arrays.copyOf(sides, sides.length << 1);
            }
            final byte side = (byte) (hash(entries, entry + Bucket.ENTRY_HEADER, length - Bucket.ENTRY_HEADER) >>> level & 1);
            sides[entryCount] = side;
            if (side == 0) {
                stayBytes += length;
            }
            entry += length;
        }
        directory(scope, split, build(0, stayBytes, count));
        directory(scope, split + (1 << level), build(1, count - stayBytes, count));

        if (split + 1 == 1 << level) {
            levels[scope] = level + 1;
            splits[scope] = 0;
        } else {
            splits[scope] = split + 1;
        }
    }

    /**
     * Write the gathered entries of one side to blocks sized for them
     * @return address of the first block, 0 when there are none
     */
    private long build(final int side, final int sideBytes, final int count) {
        long head = 0;
        long last = 0;
        int left = sideBytes;
        for (int entry = 0, entryCount = 0; entry < count; ++entryCount) {
            final int length = Bucket.ENTRY_HEADER + (entries[entry + 1] & 0xff);
            if (sides[entryCount] == side) {
                Bucket block = last == 0 ? null : get(last);
                if (block == null || block.used() + length > block.capacity()) {
                    final int sizeClass = classFor(Math.min(left, CLASS_BYTES[MAX_CLASS] - Bucket.SLOTS_OFFSET));
                    final Bucket created = allocate(sizeClass);
                    final long address = address(sizeClass, created);
                    if (block == null) {
                        head = address;
                    } else {
                        block.next(address);
                    }
                    last = address;
                    block = created;
                }
                block.append(entries, entry, length);
                left -= length;
            }
            entry += length;
        }
        return head;
    }

    private int index(final int scope, final long hash) {
        final int level = levels[scope];
        final int index = (int) hash & (1 << level) - 1;
        return index < splits[scope] ? (int) hash & (1 << level + 1) - 1 : index;
    }

    private long directory(final int scope, final int index) {
        return directoryPool.get(0, directories[scope][index >>> DIRECTORY_SHIFT]).word(index & DIRECTORY_MASK);
    }

    /**
     * Set a directory entry, allocating the next directory block of the scope when the index is past the last
     */
    private void directory(final int scope, final int index, final long address) {
        final int block = index >>> DIRECTORY_SHIFT;
        if (block == directoryBlocks[scope]) {
            int[] blocks = directories[scope];
            if (block == blocks.length) {
                blocks = directories[scope] = Arrays.copyOf(blocks, block << 1);
                heapBytes += (long) Integer.BYTES * block;
            }
            final Chunk allocated = directoryPool.allocate(0);
            blocks[block] = BlockPools.address(allocated);
            ++directoryBlocks[scope];
            allocated.word(index & DIRECTORY_MASK, address);
            return;
        }
        directoryPool.get(0, directories[scope][block]).word(index & DIRECTORY_MASK, address);
    }

    /**
     * Smallest size class holding the entry bytes, at most the largest class
     */
    private static int classFor(final int entryBytes) {
        int sizeClass = 0;
        while (sizeClass < MAX_CLASS && CLASS_BYTES[sizeClass] - Bucket.SLOTS_OFFSET < entryBytes) {
            ++sizeClass;
        }
        return sizeClass;
    }

    private Bucket allocate(final int sizeClass) {
        return buckets.allocate(sizeClass).reset();
    }

    /**
     * Address of a bucket block: the size class above the 32-bit block address
     */
    private static long address(final int sizeClass, final Bucket block) {
        return (long) sizeClass << 32 | BlockPools.address(block);
    }

    private Bucket get(final long address) {
        return buckets.get(sizeClass(address), (int) address);
    }

    private static int sizeClass(final long address) {
        return (int) (address >>> 32);
    }

    private static byte fingerprint(final long hash) {
        return (byte) (hash >>> 56);
    }

    private long hash(final byte[] key, final int position, final int length) {
        long hash = seed ^ length * 0x9e3779b97f4a7c15L;
        int i = 0;
        for (; i + Long.BYTES <= length; i += Long.BYTES) {
            hash = (hash ^ Bucket.word(key, position + i)) * 0xbf58476d1ce4e5b9L;
            hash ^= hash >>> 29;
        }
        long tail = 0;
        for (int j = length - 1; j >= i; --j) {
            tail = tail << 8 | key[position + j] & 0xff;
        }
        hash = (hash ^ tail) * 0x94d049bb133111ebL;
        hash ^= hash >>> 31;
        hash *= 0xbf58476d1ce4e5b9L;
        return hash ^ hash >>> 32;
    }
}
