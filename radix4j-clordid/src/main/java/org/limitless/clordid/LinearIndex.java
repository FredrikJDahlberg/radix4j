package org.limitless.clordid;

import java.util.Arrays;

/**
 * Hash index of dense ids from 0 that never rehashes the whole table: a linear hash table of chained buckets.
 * Each id keeps the next id in its bucket, its hash and the caller's int fields. When the ids outnumber the
 * buckets, an add splits the next bucket in turn, moving only that bucket's ids. Buckets and ids live in pages of
 * {@value #PAGE} entries, so growing allocates one page at a time and copies only the small page directories.
 * <p>
 * The caller walks a bucket with {@link #first} and {@link #next}.
 */
final class LinearIndex {

    private static final int PAGE_SHIFT = 10;
    private static final int PAGE = 1 << PAGE_SHIFT;
    private static final int PAGE_MASK = PAGE - 1;
    private static final int NEXT = 0;
    private static final int HASH = 1;
    private static final int FIELDS = 2;

    private final int stride;
    private int[][] heads = new int[1][];     // per bucket: first id + 1, 0 when empty
    private int[][] entries = new int[1][];   // per id: next id + 1, hash and the fields
    private int level;
    private int split;
    private int size;
    private long heapBytes;

    /**
     * Constructs an index
     * @param fields int fields per id
     */
    LinearIndex(final int fields) {
        stride = FIELDS + fields;
        heads[0] = page(PAGE);
    }

    /**
     * Number of ids
     * @return size
     */
    int size() {
        return size;
    }

    /**
     * Number of buckets
     * @return buckets
     */
    int buckets() {
        return (1 << level) + split;
    }

    /**
     * Heap bytes of the pages and page directories
     * @return bytes
     */
    long heapBytes() {
        return heapBytes + (long) Integer.BYTES * (heads.length + entries.length);
    }

    /**
     * First id in the bucket of a hash
     * @return id or -1
     */
    int first(final int hash) {
        final int bucket = bucket(hash);
        return heads[bucket >>> PAGE_SHIFT][bucket & PAGE_MASK] - 1;
    }

    /**
     * Next id in the bucket of an id
     * @return id or -1
     */
    int next(final int id) {
        return entries[id >>> PAGE_SHIFT][slot(id) + NEXT] - 1;
    }

    /**
     * Hash of an id
     * @return hash
     */
    int hash(final int id) {
        return entries[id >>> PAGE_SHIFT][slot(id) + HASH];
    }

    /**
     * Field of an id
     * @return value
     */
    int field(final int id, final int field) {
        return entries[id >>> PAGE_SHIFT][slot(id) + FIELDS + field];
    }

    /**
     * Set a field of an id
     */
    void field(final int id, final int field, final int value) {
        entries[id >>> PAGE_SHIFT][slot(id) + FIELDS + field] = value;
    }

    /**
     * Add the next id, {@link #size()}, splitting a bucket when the ids outnumber the buckets
     * @param hash hash of the id
     * @return id
     */
    int add(final int hash) {
        final int id = size;
        final int page = id >>> PAGE_SHIFT;
        if (page == entries.length) {
            entries = Arrays.copyOf(entries, page << 1);
        }
        if (entries[page] == null) {
            entries[page] = page(PAGE * stride);
        }
        final int bucket = bucket(hash);
        final int slot = slot(id);
        entries[page][slot + NEXT] = heads[bucket >>> PAGE_SHIFT][bucket & PAGE_MASK];
        entries[page][slot + HASH] = hash;
        head(bucket, id + 1);
        if (++size > buckets()) {
            split();
        }
        return id;
    }

    /**
     * Split the next bucket: its ids stay, or move to a new bucket at the end of the table
     */
    private void split() {
        final int low = split;
        final int high = split + (1 << level);
        int lowHead = 0;
        int highHead = 0;
        for (int id = heads[low >>> PAGE_SHIFT][low & PAGE_MASK] - 1; id >= 0; ) {
            final int[] page = entries[id >>> PAGE_SHIFT];
            final int slot = slot(id);
            final int next = page[slot + NEXT] - 1;
            if ((page[slot + HASH] >>> level & 1) == 0) {
                page[slot + NEXT] = lowHead;
                lowHead = id + 1;
            } else {
                page[slot + NEXT] = highHead;
                highHead = id + 1;
            }
            id = next;
        }
        head(low, lowHead);
        head(high, highHead);
        if (++split == 1 << level) {
            ++level;
            split = 0;
        }
    }

    private int slot(final int id) {
        return (id & PAGE_MASK) * stride;
    }

    private int bucket(final int hash) {
        final int bucket = hash & (1 << level) - 1;
        return bucket < split ? hash & (1 << level + 1) - 1 : bucket;
    }

    private void head(final int bucket, final int head) {
        final int page = bucket >>> PAGE_SHIFT;
        if (page == heads.length) {
            heads = Arrays.copyOf(heads, page << 1);
        }
        if (heads[page] == null) {
            heads[page] = page(PAGE);
        }
        heads[page][bucket & PAGE_MASK] = head;
    }

    private int[] page(final int length) {
        heapBytes += (long) Integer.BYTES * length;
        return new int[length];
    }
}
