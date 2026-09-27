package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockPool;

import java.lang.foreign.Arena;
import java.util.Arrays;
import java.util.function.Consumer;

import static org.limitless.radix4j.Node.*;

public class RadixTree {

    public static final int DEFAULT_BLOCKS_PER_SEGMENT = 256;
    public static final int MAX_BLOCKS_PER_SEGMENT = Address.MAX_BLOCKS;
    public static final int DEFAULT_BUCKET_STRINGS = MAX_BUCKET_STRINGS;
    private static final int INITIAL_PATH_SIZE = 32;
    private static final int SHARE_CANDIDATES = 2;

    private final BlockPool<Node> nodePool;
    private final Node node;
    private final Node root;
    private final Node child;
    private final Node parent;
    private final Node bucket;
    private final Search search;

    // tails of a bucket being split, sorted through the order
    private byte[] tails = new byte[2 * BUCKET_BYTES];
    private final int[] tailOffsets = new int[MAX_BUCKET_STRINGS + 1];
    private final int[] tailLengths = new int[MAX_BUCKET_STRINGS + 1];
    private final int[] order = new int[MAX_BUCKET_STRINGS + 1];

    private final int blocksPerSegment;
    private final int bucketStrings;
    private int size;
    private int allocatedNodes;

    /**
     * Constructs an empty tree with the default segment size using a shared arena.
     */
    public RadixTree() {
        this(DEFAULT_BLOCKS_PER_SEGMENT);
    }

    /**
     * Constructs an empty tree with the given segment size using a shared arena.
     * @param blocksPerSegment segment size
     * @throws IllegalArgumentException invalid blocks per segment
     */
    public RadixTree(final int blocksPerSegment) {
        this(blocksPerSegment, Arena.ofShared());
    }

    /**
     * Constructs a tree with the given properties.
     * @param blocksPerSegment blocks per segment
     * @param arena memory arena
     * @throws IllegalArgumentException invalid number of blocks or segments or null arena
     */
    public RadixTree(final int blocksPerSegment, final Arena arena) {
        this(blocksPerSegment, arena, DEFAULT_BUCKET_STRINGS);
    }

    /**
     * Constructs a tree with the given properties. A bucket holds the tails of up to bucketStrings strings; with
     * one, each bucket is a leaf holding the tail of a single string longer than an inline node string.
     * @param blocksPerSegment blocks per segment
     * @param arena memory arena
     * @param bucketStrings maximum strings per bucket, 1 to {@link Node#MAX_BUCKET_STRINGS}
     * @throws IllegalArgumentException invalid number of blocks, segments or bucket strings, or null arena
     */
    protected RadixTree(final int blocksPerSegment, final Arena arena, final int bucketStrings) {
        if (arena == null || blocksPerSegment < 64 || blocksPerSegment > MAX_BLOCKS_PER_SEGMENT) {
            throw new IllegalArgumentException("invalid number of blocks per segment");
        }
        if (bucketStrings < 1 || bucketStrings > MAX_BUCKET_STRINGS) {
            throw new IllegalArgumentException("invalid number of bucket strings");
        }
        this.blocksPerSegment = blocksPerSegment;
        this.bucketStrings = bucketStrings;
        size = 0;
        allocatedNodes = 0;
        nodePool = BlockPool.builder(arena, Node::new).blocksPerSegment(blocksPerSegment).build();
        parent = allocate(new Node());
        root = allocate(new Node());
        child = allocate(new Node());
        node = allocate(new Node());
        bucket = new Node();
        search = new Search(allocate(new Node()));
    }

    /**
     * Allocates an empty tree with the given segment size with a shared arena.
     * @param blocksPerSegment segment size
     * @param arena memory arena
     * @return tree
     */
    public static RadixTree allocate(final int blocksPerSegment, final Arena arena) {
        return new RadixTree(blocksPerSegment, arena);
    }

    /**
     * Add a string to the tree
     * @param string value
     * @return true when value is inserted, false for null or empty strings
     */
    public boolean add(final String string) {
        if (string == null || string.isEmpty()) {
            return false;
        }
        final byte[] bytes = string.getBytes();
        return addString(0, bytes.length, bytes);
    }

    /**
     * Add a string to the tree.
     * @param string value
     * @return true when value is inserted, false for null or empty strings
     */
    public boolean add(final byte[] string) {
        if (string == null || string.length == 0) {
            return false;
        }
        return addString(0, string.length, string);
    }

    /**
     * Add string to the tree.
     * @param position string offset
     * @param length string length
     * @param string value
     * @return true when value is inserted
     */
    public boolean add(final int position, final int length, final byte[] string) {
        if (position < 0 || length <= 0 || string == null || position + length > string.length) {
            return false;
        }
        return addString(position, length, string);
    }

    /**
     * Check value presence
     * @param string value
     * @return true if the string is present
     */
    public boolean contains(final String string) {
        if (string == null || isEmpty()) {
            return false;
        }
        final byte[] bytes = string.getBytes();
        return search.contains(0, bytes.length, bytes, node.wrap(root), nodePool);
    }

    /**
     * Check value presence
     * @param string value
     * @return true if the string is present
     */
    public boolean contains(final byte[] string) {
        if (string == null || isEmpty()) {
            return false;
        }
        return search.contains(0, string.length, string, node.wrap(root), nodePool);
    }

    /**
     * Check the value presence
     * @param position value offset
     * @param length value length
     * @param string value
     * @return true when string is present
     */
    public boolean contains(final int position, final int length, final byte[] string) {
        if (isEmpty()) {
            return false;
        }
        if (position < 0 || length <= 0 || string == null || position + length > string.length) {
            return false;
        }
        return search.contains(position, length, string, node.wrap(root), nodePool);
    }

    /**
     * Remove string form collection
     * @param string value
     * @return true if removed
     */
    public boolean remove(final String string) {
        if (string == null) {
            return false;
        }

        final byte[] bytes = string.getBytes();
        return removeString(0, bytes.length, bytes, true);
    }

    /**
     * Remove string form collection
     * @param string value
     * @return true if removed
     */
    public boolean remove(final byte[] string) {
        if (string == null) {
            return false;
        }
        return removeString(0, string.length, string, true);
    }

    /**
     * Remove string from tree
     * @param position value offset
     * @param length value length
     * @param string value
     * @return if removed
     */
    public boolean remove(final int position, final int length, final byte[] string) {
        if (position < 0 || length <= 0 || string == null || position + length > string.length) {
            return false;
        }
        return removeString(position, length, string, true);
    }

    /**
     * Check emptiness
     * @return true if this collection contains any strings
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Number of elements in the collection
     * @return the current number of elements
     */
    public int size() {
        return size;
    }

    /**
     * Destroys the backing memory store.
     */
    public void close() {
        size = 0;
        nodePool.close();
    }

    /**
     * Returns a string representation of the object.
     * @return string
     */
    @Override
    public String toString() {
        return String.format("RadixTree{ size = %,d, %s}", size, nodePool);
    }

    /**
     * Returns the number of allocated blocks
     * @return block count
     */
    protected int allocatedBlocks() {
        return allocatedNodes;
    }

    /**
     * Iterates over the nodes in the tree
     * @param consumer node consumer
     * @throws IllegalArgumentException for null consumers
     */
    protected void forEach(final Consumer<Node> consumer) {
        if (consumer == null) {
            throw new IllegalArgumentException("null consumer");
        }
        if (!isEmpty()) {
            var _ = search.contains(0, 0, null, node.wrap(root), nodePool);
            search.forEach(node, nodePool, consumer);
        }
    }

    /**
     * Iterates over the nodes whose strings all start with the prefix. When the prefix ends at a key,
     * the string equal to the prefix is stored in the key's node and is not visited. When it ends inside
     * a bucket, the bucket is visited and may also hold strings not starting with the prefix. An empty
     * prefix visits the whole tree.
     * @param length prefix length
     * @param prefix prefix
     * @param consumer node consumer
     * @throws IllegalArgumentException for null consumers or an invalid prefix
     */
    protected void forEach(final int length, final byte[] prefix, final Consumer<Node> consumer) {
        if (consumer == null) {
            throw new IllegalArgumentException("null consumer");
        }
        if (length < 0 || (length >= 1 && (prefix == null || length > prefix.length))) {
            throw new IllegalArgumentException("invalid prefix");
        }
        if (isEmpty()) {
            return;
        }

        node.wrap(root);
        if (length >= 1) {
            final int match = search.findPrefix(length, prefix, node, nodePool);
            if (match == Search.PREFIX_NOT_FOUND) {
                return;
            }
            if (match == Search.PREFIX_KEY) {
                final int childBlock = node.child(search.keyPos);
                if (childBlock == EMPTY_BLOCK) {
                    return;
                }
                nodePool.get(Address.fromOffset(childBlock), node);
            }
        }
        search.forEach(node, nodePool, consumer);
    }

    /**
     * Remove all strings starting with the prefix, including the prefix itself.
     * @param length prefix length
     * @param prefix prefix
     * @return true when at least one string was removed
     */
    protected boolean removeStrings(final int length, final byte[] prefix) {
        if (isEmpty() || prefix == null || length <= 0 || length > prefix.length) {
            return false;
        }

        final int match = search.findPrefix(length, prefix, node.wrap(root), nodePool);
        if (match == Search.PREFIX_NOT_FOUND) {
            return false;
        }
        int level = search.pathCount - 1;
        if (match == Search.PREFIX_BUCKET) {
            // the prefix ends inside a bucket: remove its matching tails
            size -= node.bucketRemove(search.bucketOffset, length - search.bucketOffset, prefix);
            if (node.isHybrid()) {
                removeKey(prefix[search.bucketOffset], level);
                return true;
            }
            if (node.bucketLength() >= 1) {
                return true;
            }
            freeNode(node);
        } else if (match == Search.PREFIX_KEY) {
            // the prefix ends at a key: remove the key's string and subtree
            final int keyPos = search.keyPos;
            if (node.containsKey(keyPos)) {
                node.containsKey(keyPos, false);
                --size;
            }
            final int childBlock = node.child(keyPos);
            if (childBlock != EMPTY_BLOCK) {
                nodePool.get(Address.fromOffset(childBlock), bucket);
                if (bucket.isHybrid()) {
                    size -= bucket.bucketRemove(node.key(keyPos));
                    if (bucket.bucketLength() == 0) {
                        freeNode(bucket);
                    }
                } else {
                    freeSubtree(childBlock);
                }
            }
            node.removeChild(keyPos);
            if (!isUnused(node)) {
                return true;
            }
            freeNode(node);
        } else if (level == 0) {
            // the prefix ends inside the root string: every string matches
            final int count = Header.children(node.header());
            for (int i = 0; i < count; ++i) {
                final int childBlock = node.child(i);
                if (childBlock != EMPTY_BLOCK && !node.sharesChild(i)) {
                    freeSubtree(childBlock);
                }
            }
            node.header(0, false, 0);
            size = 0;
            return true;
        } else {
            // the prefix ends inside the node string: remove the node and its subtree
            freeSubtree(node.offset());
        }

        detach(level);
        return true;
    }

    /**
     * Detach the block at the level of the search path from its parent key and free the ancestors left
     * without strings.
     * @param level path level
     */
    private void detach(int level) {
        for (; level >= 1; --level) {
            final int keyPos = Path.position(search.path[level]);
            nodePool.get(Address.fromOffset(Path.offset(search.path[level - 1])), node);
            if (node.containsKey(keyPos)) {
                node.child(keyPos, EMPTY_BLOCK);
                return;
            }
            node.removeChild(keyPos);
            if (!isUnused(node)) {
                return;
            }
            freeNode(node);
        }
    }

    /**
     * After removing tails from the hybrid bucket in node at the level of the search path, detach the key
     * from the bucket when none of its tails are left, and free the bucket when it is empty.
     * @param key key of the removed tails
     * @param level path level of the bucket
     */
    private void removeKey(final byte key, final int level) {
        if (node.bucketHas(key)) {
            return;
        }
        if (node.bucketLength() == 0) {
            freeNode(node);
        }
        detach(level);
    }

    private static boolean isUnused(final Node node) {
        final byte header = node.header();
        return Header.children(header) == 0 && !Header.containsString(header);
    }

    /**
     * Free the node at the given block and all of its descendants, updating the string and node counts.
     * Uses the search path above its current count as a stack.
     * @param block subtree root
     */
    private void freeSubtree(final int block) {
        final int stop = search.pathCount;
        search.ensureCapacity();
        search.pushPath(Path.offset(Path.EMPTY, block));
        while (search.pathCount > stop) {
            nodePool.get(Address.fromOffset(Path.offset(search.popPath())), child);
            final byte header = child.header();
            if (Header.isBucket(header)) {
                size -= child.bucketCount();
            } else if (Header.containsString(header)) {
                --size;
            }
            final int count = Header.children(header);
            for (int i = 0; i < count; ++i) {
                if (child.containsKey(i)) {
                    --size;
                }
                final int childBlock = child.child(i);
                if (childBlock != EMPTY_BLOCK && !child.sharesChild(i)) {
                    search.ensureCapacity();
                    search.pushPath(Path.offset(Path.EMPTY, childBlock));
                }
            }
            --allocatedNodes;
            nodePool.free(child);
        }
    }

    private boolean addString(int position, int length, final byte[] string) {
        final byte rootHeader = root.header();
        if (Header.stringLength(rootHeader) == 0 && Header.children(rootHeader) == 0) {
            addString(position, length, string, node.wrap(root));
            ++size;
            return true;
        }
        if (!search.mismatch(position, length, string, node.wrap(root), nodePool)) {
            return false;
        }
        length -= search.position;
        position += search.position;
        ++size;

        final byte header = node.header();
        final int stringLength = Header.stringLength(header);
        final int remaining = stringLength - search.mismatch;
        int consumed = 0;
        final byte key = length >= 1 ? string[position] : search.key;
        if (search.reuseKeyNodeOffset != EMPTY_BLOCK) {
            nodePool.get(Address.fromOffset(search.reuseKeyNodeOffset), node);
        }
        switch (search.mismatchType) {
            case Search.COMMON_PREFIX:
                consumed = splitNode(remaining, length, key, search.mismatch, node);
                break;
            case Search.NO_COMMON_PREFIX:
                addParent(remaining, key, search.keyPos, length, node,
                    allocate(parent), search.parent);
                consumed = 1;
                break;
            case Search.SUBSTRING:
                node.header(Header.containsString(header, true));
                break;
            case Search.MISSING_KEY:
                if (search.keyPos == NOT_FOUND && length >= 2 && shareBucket(position, length, string, node)) {
                    consumed = length;
                } else {
                    consumed = addKey(length, key, search.keyPos, node);
                }
                break;
            case Search.COMMON_PREFIX_AND_KEY:
                addChild(search.key, search.keyPos, node);
                break;
            case Search.BUCKET:
                if (node.isHybrid()) {
                    addHybridTail(position - 1, length + 1, string, node);
                } else {
                    addTail(position, length, string, node);
                }
                consumed = length;
                break;
            default:
                break;
        }
        if (length - consumed >= 1) {
            addString(position + consumed, length - consumed, string, node);
        }
        return true;
    }

    private boolean removeString(final int position, final int length, final byte[] string, final boolean find) {
        if (find) {
            if (isEmpty()) {
                return false;
            }
            if (search.mismatch(position, length, string, node.wrap(root), nodePool)) {
                return false;
            }
        }
        --size;

        byte header = node.header();
        if (Header.isHybrid(header)) {
            final byte key = node.tailByte(search.bucketEntry, 0);
            node.bucketRemove(search.bucketEntry);
            removeKey(key, search.pathCount - 1);
            return true;
        } else if (Header.isBucket(header)) {
            node.bucketRemove(search.bucketEntry);
            if (node.bucketLength() >= 1) {
                return true;
            }
            node.header(0, false, 0);
        } else if (search.keyPos == NOT_FOUND) {
            node.header(Header.containsString(header, false));
        } else {
            node.containsKey(search.keyPos, false);
            if (node.child(search.keyPos) == EMPTY_BLOCK) {
                node.removeChild(search.keyPos);
            }
        }

        header = node.header();
        if (Header.children(header) == 0 && !Header.containsString(header)) {
            freeNode(node);

            boolean freeNode = true;
            for (int i = search.pathCount - 2; freeNode &&  i >= 0; --i) {
                nodePool.get(Address.fromOffset(Path.offset(search.path[i])), node);
                header = node.header();
                final int keyPos = Path.position(search.path[i + 1]);
                if (node.containsKey(keyPos)) {
                    node.child(keyPos, EMPTY_BLOCK);
                } else {
                    node.removeChild(keyPos);
                }
                freeNode = Header.children(header) <= 1 && !Header.containsString(header) && !node.containsKey(keyPos);
                if (freeNode) {
                    freeNode(node);
                }
            }
        }
        return true;
    }

    private void addParent(final int remainingNode,
                           final byte key,
                           final int keyPos,
                           final int remainingString,
                           final Node current,
                           final Node newParent,
                           final Node currentParent) {
        if (current.equals(root)) {
            root.wrap(newParent);
        }
        if (keyPos != NOT_FOUND) {
            currentParent.child(keyPos, newParent.offset());
        }

        final byte header = current.header();
        final int block = remainingNode == 1 && Header.children(header) == 0 ? EMPTY_BLOCK : current.offset();
        final int childBlock = remainingString >= 2 ? allocate(child).offset() : EMPTY_BLOCK;
        if (remainingNode >= 1) {
            final byte foundKey = current.charAt(0);
            final boolean containsKey = Header.containsString(header) && Header.stringLength(header) == 1;
            newParent
                .header(0, false, 0)
                .addChild(foundKey, block,  containsKey)
                .addChild(key, childBlock, remainingString == 1);

        }
        if (block == EMPTY_BLOCK) {
            freeNode(current);
            current.wrap(newParent);
        } else {
            current.removePrefix(1, remainingNode - 1);
            if (remainingNode == 1) {
                current.header(Header.containsString(current.header(), false));
            }
        }
        if (childBlock != 0) {
            current.wrap(child);
        }
    }

    /**
     * Split the current node at the mismatch position. The node keeps the common prefix and gets the
     * first tail character as its only key. The rest of the tail, the string flag and the children move
     * to a new node unless the tail is a single character without children. A string ending at the key
     * is recorded in the key's contains flag, never in a string-less child node.
     */
    private int splitNode(final int remainingNode,
                          final int remainingString,
                          final byte key,
                          final int mismatch,
                          final Node current) {
        final byte header = current.header();
        final int count = Header.children(header);
        final int tailLength = remainingNode - 1;
        int block = EMPTY_BLOCK;
        if (tailLength >= 1 || count >= 1) {
            block = allocate(parent).offset();
            parent.copy(current);
            parent.removePrefix(mismatch + 1, tailLength);
            if (tailLength == 0) {
                parent.header(Header.containsString(parent.header(), false));
            }
        }
        current
            .header(mismatch, remainingString == 0, 1)
            .child(0, current.charAt(mismatch), block, tailLength == 0 && Header.containsString(header));
        return remainingString == 0 ? 0 : addKey(remainingString, key, NOT_FOUND, current);
    }

    /**
     * Add the tail of a string to the bucket it reached. When the bucket is full, its tails and the new one are
     * sorted and the bucket block becomes the root of a subtree holding them, see {@link #build}.
     */
    private void addTail(final int position, final int length, final byte[] string, final Node current) {
        if (length <= TAIL_LENGTH && current.bucketFits(length) && -1 - search.bucketEntry < bucketStrings) {
            current.bucketAdd(string, position, length);
            return;
        }
        build(current.offset(), 0, sortTails(position, length, string, current), 0);
    }

    /**
     * Add a tail starting with its key to a hybrid bucket. When the bucket is full, its tails and the new one
     * are sorted and shared out among the keys again, see {@link #split}.
     */
    private void addHybridTail(final int position, final int length, final byte[] string, final Node current) {
        if (length <= TAIL_LENGTH && current.bucketFits(length) && -1 - search.bucketEntry < bucketStrings) {
            current.bucketAdd(string, position, length);
            return;
        }
        final int count = sortTails(position, length, string, current);
        nodePool.get(Address.fromOffset(Path.offset(search.path[search.pathCount - 2])), parent);
        split(current.offset(), count);
    }

    /**
     * Copy the tails of the bucket and the new one to the tail buffer, sorted through the order
     * @return number of tails
     */
    private int sortTails(final int position, final int length, final byte[] string, final Node current) {
        final int used = current.bucketLength();
        if (tails.length < used + length) {
            tails = Arrays.copyOf(tails, used + length);
        }
        int count = 0;
        for (int entry = 0; entry < used; entry += 1 + current.tailLength(entry)) {
            tailOffsets[count] = entry;
            tailLengths[count] = current.tailLength(entry);
            current.tail(entry, tails, entry);
            ++count;
        }
        tailOffsets[count] = used;
        tailLengths[count] = length;
        System.arraycopy(string, position, tails, used, length);
        ++count;
        for (int i = 0; i < count; ++i) {
            int j = i;
            for (; j >= 1 && compareTails(order[j - 1], i) > 0; --j) {
                order[j] = order[j - 1];
            }
            order[j] = i;
        }
        return count;
    }

    /**
     * Share out the sorted tails of a full hybrid bucket, which start with their key, among the keys of the
     * parent node: groups of keys whose tails fit together get a hybrid bucket, the first reusing the block,
     * the tails of a single key a bucket or subtree of their own.
     */
    private void split(final int block, final int count) {
        int target = block;
        for (int i = 0; i < count; ) {
            final int next = groupEnd(i, count, 0);
            final int end = packEnd(i, next, count, 0, NOT_FOUND);
            if (target == EMPTY_BLOCK) {
                target = allocate(child).offset();
            }
            if (end > next) {
                writeBucket(target, i, end, 0, true);
            } else {
                build(target, i, next, 1);
            }
            final int keys = Header.children(parent.header());
            for (int group = i; group < end; group = groupEnd(group, count, 0)) {
                parent.child(parent.keyPosition(keys, (byte) tailByte(order[group], 0)), target);
            }
            target = EMPTY_BLOCK;
            i = end;
        }
    }

    /**
     * Add a key to the current node for a string continuing after it, sharing the bucket of the nearest key
     * with room for the tail, which becomes a hybrid bucket.
     * @return true when the key was added
     */
    private boolean shareBucket(final int position, final int length, final byte[] string, final Node current) {
        // the tail keeps its key
        final int count = Header.children(current.header());
        if (bucketStrings < 2 || count >= BLOCK_COUNT || length > TAIL_LENGTH) {
            return false;
        }
        // try the nearest keys first, reading at most SHARE_CANDIDATES buckets
        final int key = string[position] & 0xff;
        final int overflow = current.overflow();
        int tried = 0;
        int best = NOT_FOUND;
        for (int attempt = 0; attempt < SHARE_CANDIDATES && best == NOT_FOUND; ++attempt) {
            int candidate = NOT_FOUND;
            int candidateDistance = Integer.MAX_VALUE;
            for (int i = 0; i < count; ++i) {
                final int distance = Math.abs((current.key(i) & 0xff) - key);
                if (i != overflow && (tried & 1 << i) == 0 && current.child(i) != EMPTY_BLOCK
                    && distance < candidateDistance) {
                    candidate = i;
                    candidateDistance = distance;
                }
            }
            if (candidate == NOT_FOUND) {
                return false;
            }
            tried |= 1 << candidate;
            nodePool.get(Address.fromOffset(current.child(candidate)), bucket);
            final byte header = bucket.header();
            if (Header.isBucket(header)) {
                final int strings = bucket.bucketCount();
                final int used = bucket.bucketLength() + (Header.isHybrid(header) ? 0 : strings);
                if (strings < bucketStrings && used + 1 + length <= BUCKET_BYTES) {
                    best = candidate;
                }
            }
        }
        if (best == NOT_FOUND) {
            return false;
        }
        final int block = current.child(best);
        nodePool.get(Address.fromOffset(block), bucket);
        if (!bucket.isHybrid()) {
            // prefix the tails with their key
            final int used = bucket.bucketLength();
            final byte bestKey = current.key(best);
            int offset = 0;
            for (int entry = 0; entry < used; entry += 1 + bucket.tailLength(entry)) {
                tails[offset] = (byte) (bucket.tailLength(entry) + 1);
                tails[offset + 1] = bestKey;
                bucket.tail(entry, tails, offset + 2);
                offset += 2 + bucket.tailLength(entry);
            }
            bucket.hybrid();
            for (int entry = 0; entry < offset; entry += 1 + tails[entry]) {
                bucket.bucketAdd(tails, entry + 1, tails[entry]);
            }
        }
        bucket.bucketAdd(string, position, length);
        current.addChild(string[position], block, false);
        return true;
    }

    /**
     * Give the key at the position, which shares a hybrid bucket with other keys, a bucket of its own
     */
    private void unshare(final Node current, final int position) {
        final byte key = current.key(position);
        nodePool.get(Address.fromOffset(current.child(position)), bucket);
        // copy the key's tails as their length without the key, the key and the rest
        final int used = bucket.bucketLength();
        int offset = 0;
        for (int entry = 0; entry < used; entry += 1 + bucket.tailLength(entry)) {
            if (bucket.tailByte(entry, 0) == key) {
                final int length = bucket.tailLength(entry);
                tails[offset] = (byte) (length - 1);
                bucket.tail(entry, tails, offset + 1);
                offset += 1 + length;
            }
        }
        bucket.bucketRemove(key);
        allocate(bucket).bucket(tails, 2, tails[0]);
        for (int entry = 2 + tails[0]; entry < offset; entry += 2 + tails[entry]) {
            bucket.bucketAdd(tails, entry + 2, tails[entry]);
        }
        current.child(position, bucket.offset());
    }

    private int compareTails(final int a, final int b) {
        return Arrays.compareUnsigned(tails, tailOffsets[a], tailOffsets[a] + tailLengths[a],
            tails, tailOffsets[b], tailOffsets[b] + tailLengths[b]);
    }

    private int tailByte(final int tail, final int position) {
        return tails[tailOffsets[tail] + position];
    }

    /**
     * Write the sorted tails from..to, longer than depth and sharing their first depth bytes, from depth on to
     * the block: a bucket when they fit, otherwise a node holding their common prefix, up to an inline string,
     * with a key for each next byte. A tail ending at the node string or at a key is recorded in its contains
     * flag, the rest of the tails below a key are written to a new block. Keys beyond a full node go to an
     * overflow node.
     */
    private void build(final int block, final int from, final int to, final int depth) {
        if (fitsBucket(from, to, depth)) {
            writeBucket(block, from, to, depth, false);
            return;
        }
        nodePool.get(Address.fromOffset(block), bucket);
        final int first = order[from];
        final int last = order[to - 1];
        final int common = Math.min(tailLengths[first], tailLengths[last]) - depth;
        int prefix = 0;
        while (prefix < STRING_LENGTH && prefix < common && tailByte(first, depth + prefix) == tailByte(last, depth + prefix)) {
            ++prefix;
        }
        final boolean containsString = tailLengths[first] == depth + prefix;
        bucket
            .header(prefix, containsString, 0)
            .string(tails, tailOffsets[first] + depth, prefix);
        final int position = depth + prefix;
        int nodeBlock = block;
        int i = containsString ? from + 1 : from;
        while (i < to) {
            final byte key = (byte) tailByte(order[i], position);
            final int next = groupEnd(i, to, position);
            nodePool.get(Address.fromOffset(nodeBlock), bucket);
            int keys = Header.children(bucket.header());
            if (keys == BLOCK_COUNT - 1 && next < to) {
                final int overflowBlock = allocate(child).offset();
                child.header(0, false, 0);
                bucket.addChild(EMPTY_KEY, overflowBlock, false);
                bucket.overflow(BLOCK_COUNT - 1);
                bucket.wrap(child);
                nodeBlock = overflowBlock;
                keys = 0;
            }
            final int end = packEnd(i, next, to, position, keys);
            if (end > next) {
                // keys whose tails fit together share a hybrid bucket
                final int hybridBlock = allocate(child).offset();
                for (int group = i; group < end; group = groupEnd(group, to, position)) {
                    bucket.addChild((byte) tailByte(order[group], position), hybridBlock,
                        tailLengths[order[group]] == position + 1);
                }
                writeBucket(hybridBlock, i, end, position, true);
                i = end;
                continue;
            }
            final boolean containsKey = tailLengths[order[i]] == position + 1;
            final int rest = containsKey ? i + 1 : i;
            if (rest < next) {
                final int childBlock = allocate(child).offset();
                bucket.addChild(key, childBlock, containsKey);
                build(childBlock, rest, next, position + 1);
            } else {
                bucket.addChild(key, EMPTY_BLOCK, true);
            }
            i = next;
        }
    }

    private int groupEnd(final int from, final int to, final int position) {
        final int key = tailByte(order[from], position);
        int next = from + 1;
        while (next < to && tailByte(order[next], position) == key) {
            ++next;
        }
        return next;
    }

    /**
     * End of the groups of tails from the group from..next on, grouped by their byte at the position, that fit
     * in a hybrid bucket: tails continuing after their key, at most bucketStrings of them in its bytes. Every
     * group must have such tails, and their keys must fit in the node holding keys, without taking the last
     * slot when more groups follow, unless keys is {@link Node#NOT_FOUND} for existing keys.
     * @return end of the last group that fits, next when it is the only one
     */
    private int packEnd(final int from, final int next, final int to, final int position, final int keys) {
        if (bucketStrings < 2) {
            return next;
        }
        int strings = 0;
        int bytes = 0;
        for (int i = from; i < next; ++i) {
            final int length = tailLengths[order[i]] - position;
            if (length >= 2) {
                ++strings;
                bytes += 1 + length;
            }
        }
        if (strings == 0 || strings > bucketStrings || bytes > BUCKET_BYTES) {
            return next;
        }
        int end = next;
        int groups = 1;
        while (end < to) {
            final int groupEnd = groupEnd(end, to, position);
            int groupStrings = 0;
            for (int i = end; i < groupEnd; ++i) {
                final int length = tailLengths[order[i]] - position;
                if (length >= 2) {
                    ++groupStrings;
                    bytes += 1 + length;
                }
            }
            strings += groupStrings;
            if (groupStrings == 0 || strings > bucketStrings || bytes > BUCKET_BYTES
                || (keys != NOT_FOUND && keys + groups + 1 > (groupEnd < to ? BLOCK_COUNT - 1 : BLOCK_COUNT))) {
                break;
            }
            ++groups;
            end = groupEnd;
        }
        return end;
    }

    /**
     * Write the sorted tails from..to from the position on to the block as a bucket, or as a hybrid bucket
     * keeping their key, where tails ending at their key are left out
     */
    private void writeBucket(final int block, final int from, final int to, final int position, final boolean hybrid) {
        nodePool.get(Address.fromOffset(block), bucket);
        if (hybrid) {
            bucket.hybrid();
        } else {
            bucket.header(Header.BUCKET);
            bucket.bucketClear();
        }
        for (int i = from; i < to; ++i) {
            final int tail = order[i];
            final int length = tailLengths[tail] - position;
            if (length >= (hybrid ? 2 : 1)) {
                bucket.bucketAdd(tails, tailOffsets[tail] + position, length);
            }
        }
    }

    private boolean fitsBucket(final int from, final int to, final int depth) {
        final int count = to - from;
        if (count > bucketStrings) {
            return false;
        }
        if (count == 1) {
            final int length = tailLengths[order[from]] - depth;
            return length <= TAIL_LENGTH && (bucketStrings >= 2 || length > STRING_LENGTH);
        }
        int bytes = 0;
        for (int i = from; i < to; ++i) {
            bytes += 1 + tailLengths[order[i]] - depth;
        }
        return bytes <= BUCKET_BYTES;
    }

    private void addChild(final byte key, final int keyPos, final Node current) {
        final int block = allocate(child).offset();
        child.header(0, false, 0);
        current.child(keyPos, key, block, true);
        current.wrap(child);
    }

    private int addKey(final int remaining, final byte key, final int keyPos, final Node current) {
        if (remaining == 0) { // the string ends at an existing key
            current.containsKey(keyPos, true);
            return 0;
        }
        final int block = remaining >= 2 ? allocate(parent).offset() : 0;
        if (Header.children(current.header()) < BLOCK_COUNT) {
            current.addChild(key, block, remaining == 1);
        } else {
            final int last = BLOCK_COUNT - 1;
            if (current.sharesChild(last)) {
                unshare(current, last);
            }
            final int childBlock = allocate(child).offset();
            child
                .header(0, false, 0)
                .addChild(current.key(last), current.child(last), current.containsKey(last))
                .addChild(key, block, remaining == 1);
            current.child(last, EMPTY_KEY, childBlock, false);
            current.overflow(last);
            current.wrap(child);
        }
        if (block != EMPTY_BLOCK) {
            current.wrap(parent);
        }
        return 1;
    }

    private void addString(final int offset, final int length, final byte[] string, final Node node) {
        int remaining = length;
        int position = offset;
        while (remaining >= 1) {
            if (remaining <= TAIL_LENGTH && (bucketStrings >= 2 || remaining > STRING_LENGTH)) {
                node.bucket(string, position, remaining);
                return;
            }
            final int stringLength = Math.min(STRING_LENGTH, remaining);
            final byte header = node.header();
            node
                .header(stringLength, remaining == stringLength, Header.children(header))
                .string(string, position, stringLength);
            position += stringLength;
            remaining -= stringLength;
            if (remaining >= 1) {
                final int childBlock = remaining == 1 ? 0 : allocate(child).offset();
                node
                    .header(stringLength, false, 1)
                    .child(0, string[position], childBlock, remaining == 1);
                node.wrap(child);
            }
            --remaining;
            ++position;
        }
    }

    private Node allocate(final Node node) {
        ++allocatedNodes;
        final int segments = allocatedNodes / blocksPerSegment;
        if (segments >= Address.MAX_SEGMENTS) {
            throw new IllegalStateException("out of segments " + segments);
        }

        nodePool.allocate(node);
        node.header((byte) 0);
        node.overflow(NOT_FOUND);
        return node;
    }

    private void freeNode(final Node node) {
        if (root.address() == node.address()) {
            root.header(0, false, 0);
        } else {
            --allocatedNodes;
            nodePool.free(node);
        }
    }

    private static final class Search {
        private static final int TYPE_NULL = 0;
        private static final int SUBSTRING = 1;
        private static final int COMMON_PREFIX = 2;
        private static final int COMMON_PREFIX_AND_KEY = 3;
        private static final int NO_COMMON_PREFIX = 4;
        private static final int MISSING_KEY = 5;
        private static final int BUCKET = 6;

        private static final int PREFIX_NOT_FOUND = 0;
        private static final int PREFIX_NODE = 1;
        private static final int PREFIX_KEY = 2;
        private static final int PREFIX_BUCKET = 3;

        int mismatchType;
        int mismatch;
        int position;
        byte key;
        int keyPos;
        int reuseKeyNodeOffset;
        int bucketEntry;
        int bucketOffset;
        boolean found;

        final Node parent;

        long[] path = new long[INITIAL_PATH_SIZE];
        int pathCount;

        Search(final Node parent) {
            this.parent = parent;
        }

        /**
         * Find the insertion point for a new string
         * @param stringPosition string offset
         * @param length string length
         * @param string bytes
         * @param node   the insertion point data
         * @param pool   block pool
         * @return true when mismatch exists
         */
        boolean mismatch(final int stringPosition,
                         int length,
                         final byte[] string,
                         final Node node,
                         final BlockPool<Node> pool) {
            key = NOT_FOUND;
            keyPos = NOT_FOUND;
            reuseKeyNodeOffset = EMPTY_BLOCK;
            position = 0;
            mismatch = 0;
            mismatchType = TYPE_NULL;
            pathCount = 0;
            found = false;
            pushPath(Path.offset(Path.EMPTY, node.offset()));
            long headerAndString = node.headerAndString();
            byte header = Node.headerOf(headerAndString);
            int nodeLength = Header.stringLength(header);
            while (length >= 1) {
                if (Header.isBucket(header)) {
                    // the tails of a hybrid bucket start with the key
                    final int keyLength = Header.isHybrid(header) ? 1 : 0;
                    bucketEntry = node.bucketFind(position + stringPosition - keyLength, length + keyLength, string);
                    keyPos = NOT_FOUND;
                    reuseKeyNodeOffset = EMPTY_BLOCK;
                    if (bucketEntry >= 0) {
                        key = EMPTY_KEY;
                        found = true;
                        return false;
                    }
                    mismatchType = BUCKET;
                    return true;
                }
                if (nodeLength >= 1) {
                    mismatch = Node.mismatch(headerAndString, position + stringPosition, length, string);
                    if (mismatch == EQUAL) {
                        key = EMPTY_KEY;
                        keyPos = NOT_FOUND;
                        found = true;
                        return false;
                    }
                    position += mismatch;
                    length -= mismatch;
                    if (nodeLength > mismatch) {
                        if (mismatch == 0) {
                            mismatchType = NO_COMMON_PREFIX;
                            // only a new parent needs the current parent, wrap it from the path
                            if (pathCount >= 2) {
                                pool.get(Address.fromOffset(Path.offset(path[pathCount - 2])), parent);
                            } else {
                                parent.wrap(node);
                            }
                        } else {
                            mismatchType = COMMON_PREFIX;
                        }
                        return true;
                    }
                }
                if (length >= 1) {
                    mismatchType = MISSING_KEY;
                    final int count = Header.children(header);
                    keyPos = node.keyPosition(count, string[position + stringPosition]);
                    if (keyPos == NOT_FOUND) {
                        return true;
                    }
                    path[pathCount-1] |= Path.position(path[pathCount-1], keyPos);
                    key = node.key(keyPos);
                    if (!node.isOverflow(keyPos)) {  // overflow keys do not advance the input string
                        --length;
                        ++position;
                        if (length == 0) {
                            reuseKeyNodeOffset = EMPTY_BLOCK; // the key exists, update it in place
                            found = node.containsKey(keyPos);
                            return !found;
                        }
                    } else if (length == 1 && count < BLOCK_COUNT) {
                        reuseKeyNodeOffset = node.offset();
                    }

                    final int childBlock = node.child(keyPos);
                    if (childBlock != EMPTY_BLOCK) {
                        ensureCapacity();
                        pushPath(Path.path(key, keyPos, childBlock));
                        pool.get(Address.fromOffset(childBlock), node);
                        headerAndString = node.headerAndString();
                        header = Node.headerOf(headerAndString);
                        nodeLength = Header.stringLength(header);
                    } else {
                        mismatchType = COMMON_PREFIX_AND_KEY;
                        return true;
                    }
                }
            }
            mismatchType = SUBSTRING;
            return true;
        }

        /**
         * Fast-path for contains operation
         * @param offset prefix offset
         * @param length prefix length
         * @param string buffer
         * @param current node
         * @param pool block pool
         * @return true when found
         */
        boolean contains(final int offset,
                         int length,
                         final byte[] string,
                         final Node current,
                         final BlockPool<Node> pool) {
            key = NOT_FOUND;
            keyPos = NOT_FOUND;
            position = 0;
            pathCount = 0;
            found = false;
            long headerAndString = current.headerAndString();
            byte header = Node.headerOf(headerAndString);
            int nodeLength = Header.stringLength(header);
            while (length >= 1) {
                if (Header.isBucket(header)) {
                    final int keyLength = Header.isHybrid(header) ? 1 : 0;
                    found = current.bucketFind(position + offset - keyLength, length + keyLength, string) >= 0;
                    return found;
                }
                if (nodeLength >= 1) {
                    final int matched = Node.mismatch(headerAndString, position + offset, length, string);
                    if (matched == EQUAL) {
                        found = true;
                        return true;
                    }
                    if (matched < nodeLength && matched < length) {
                        return false; // diverges inside the node string
                    }
                    position += matched;
                    length -= matched;
                }
                if (length >= 1) {
                    keyPos = current.keyPosition(Header.children(header), string[position + offset]);
                    if (keyPos == NOT_FOUND) {
                        return false;
                    }
                    key = current.key(keyPos);
                    if (!current.isOverflow(keyPos)) {
                        ++position;
                        --length;
                        if (length == 0) {
                            found = current.containsKey(keyPos);
                            return found;
                        }
                    }
                    final int childOffset = current.child(keyPos);
                    if (childOffset != EMPTY_BLOCK) {
                        pool.get(Address.fromOffset(childOffset), current);
                        headerAndString = current.headerAndString();
                        header = Node.headerOf(headerAndString);
                        nodeLength = Header.stringLength(header);
                    } else {
                        return false;
                    }
                }
            }
            return false;
        }

        /**
         * Traverses the tree from the starting point and applying the consumer.
         * @param node     starting point
         * @param pool     memory pool
         * @param consumer node consumer, null will erase the subtree
         */
        void forEach(final Node node, final BlockPool<Node> pool, final Consumer<Node> consumer) {
            pathCount = 0;
            pushPath(Path.offset(Path.EMPTY, node.offset()));
            while (pathCount >= 1) {
                final int offset = Path.offset(popPath());
                pool.get(Address.fromOffset(offset), node);
                consumer.accept(node);

                final int children = Header.children(node.header());
                for (int i = 0; i < children; ++i) {
                    final int childBlock = node.child(i);
                    if (childBlock != EMPTY_BLOCK && !node.sharesChild(i)) {
                        ensureCapacity();
                        pushPath(Path.offset(Path.EMPTY, childBlock));
                    }
                }
            }
        }

        /**
         * Find where the prefix ends, recording the visited nodes in the path.
         * @param length prefix length
         * @param prefix bytes
         * @param node   start node, left at the node where the prefix ends
         * @param pool   block pool
         * @return PREFIX_NODE when the prefix ends inside the node string, PREFIX_KEY when it ends at
         *         the key at keyPos, PREFIX_BUCKET when it ends inside tails of the bucket, which continue
         *         the prefix from bucketOffset, PREFIX_NOT_FOUND when no string starts with the prefix
         */
        int findPrefix(int length, final byte[] prefix, final Node node, final BlockPool<Node> pool) {
            keyPos = NOT_FOUND;
            pathCount = 0;
            pushPath(Path.offset(Path.EMPTY, node.offset()));
            int position = 0;
            while (true) {
                final long headerAndString = node.headerAndString();
                final byte header = Node.headerOf(headerAndString);
                if (Header.isBucket(header)) {
                    final int keyLength = Header.isHybrid(header) ? 1 : 0;
                    bucketOffset = position - keyLength;
                    return node.bucketStartsWith(bucketOffset, length + keyLength, prefix) ? PREFIX_BUCKET : PREFIX_NOT_FOUND;
                }
                if (Header.stringLength(header) >= 1) {
                    final int matched = Node.mismatch(headerAndString, position, length, prefix);
                    if (matched == EQUAL || matched == length) {
                        return PREFIX_NODE;
                    }
                    if (matched < Header.stringLength(header)) {
                        return PREFIX_NOT_FOUND;
                    }
                    position += matched;
                    length -= matched;
                }
                keyPos = node.keyPosition(Header.children(header), prefix[position]);
                if (keyPos == NOT_FOUND) {
                    return PREFIX_NOT_FOUND;
                }
                final byte nodeKey = node.key(keyPos);
                if (!node.isOverflow(keyPos)) {
                    ++position;
                    if (--length == 0) {
                        return PREFIX_KEY;
                    }
                }
                final int childBlock = node.child(keyPos);
                if (childBlock == EMPTY_BLOCK) {
                    return PREFIX_NOT_FOUND;
                }
                ensureCapacity();
                pushPath(Path.path(nodeKey, keyPos, childBlock));
                pool.get(Address.fromOffset(childBlock), node);
            }
        }

        void pushPath(long value) {
            path[pathCount++] = value;
        }

        long popPath() {
            return path[--pathCount];
        }

        void ensureCapacity() {
            if (pathCount >= path.length) {
                path = Arrays.copyOf(path, path.length * 2);
            }
        }
    }

    private static final class Path {

        private static final int BLOCK_OFFSET = 0;
        private static final int BLOCK_LENGTH = Integer.SIZE;
        private static final int POSITION_OFFSET = BLOCK_OFFSET + BLOCK_LENGTH;
        private static final int POSITION_LENGTH = Byte.SIZE;
        private static final int KEY_OFFSET = POSITION_OFFSET + POSITION_LENGTH;
        private static final int KEY_LENGTH = Byte.SIZE;

        private static final long BLOCK_MASK = -1L >>> (Long.SIZE - BLOCK_LENGTH);
        private static final long KEY_MASK = -1L >>> (Long.SIZE - KEY_LENGTH);
        private static final long POSITION_MASK = -1L >>> (Long.SIZE - POSITION_LENGTH);

        private static final long EMPTY = 0;

        public static int position(final long path) {
            return (int) ((path >>> POSITION_OFFSET) & POSITION_MASK);
        }

        public static long position(final long path, final int position) {
            return path | ((long) position << POSITION_OFFSET);
        }

        public static long offset(final long  path, final int block) {
            return path | ((block & BLOCK_MASK) << BLOCK_OFFSET);
        }

        public static int offset(final long path) {
            return (int) ((path >>> BLOCK_OFFSET) & BLOCK_MASK);
        }

        public static long path(final byte key, final int position, final int block) {
            long value = 0;
            value |= ((long) position) << POSITION_OFFSET;
            value |= ((block & BLOCK_MASK) << BLOCK_OFFSET);
            value |= (key & KEY_MASK) << KEY_OFFSET;
            return value;
        }
    }
}
