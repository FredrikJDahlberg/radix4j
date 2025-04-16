package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockPool;

import java.lang.foreign.Arena;
import java.util.Arrays;
import java.util.function.Consumer;

import static org.limitless.radix4j.Node.*;

public class RadixTree {

    public static final int DEFAULT_BLOCKS_PER_SEGMENT = 256;
    public static final int MAX_BLOCKS_PER_SEGMENT = Address.MAX_BLOCKS;
    private static final int INITIAL_PATH_SIZE = 32;

    private final BlockPool<Node> nodePool;
    private final Node node;
    private final Node root;
    private final Node child;
    private final Node parent;
    private final Search search;

    private final int blocksPerSegment;
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
        if (arena == null || blocksPerSegment < 64 || blocksPerSegment > MAX_BLOCKS_PER_SEGMENT) {
            throw new IllegalArgumentException("invalid number of blocks per segment");
        }
        this.blocksPerSegment = blocksPerSegment;
        size = 0;
        allocatedNodes = 0;
        nodePool = new BlockPool.Builder<>(arena, Node.class).blocksPerSegment(blocksPerSegment).build();
        parent = allocate(new Node());
        root = allocate(new Node());
        child = allocate(new Node());
        node = allocate(new Node());
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
     * @return true when value is inserted
     */
    public boolean add(final String string) {
        if (string == null) {
            return false;
        }
        final byte[] bytes = string.getBytes();
        return addString(0, bytes.length, bytes);
    }

    /**
     * Add a string to the tree.
     * @param string value
     * @return true when value is inserted
     */
    public boolean add(final byte[] string) {
        if (string == null) {
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
     * Remove all string matching the prefix
     * @param length prefix length
     * @param prefix prefix
     * @param consumer node consumer
     * @throws IllegalArgumentException for null consumers
     */
    protected void forEach(final int length, final byte[] prefix, final Consumer<Node> consumer) {
        if (consumer == null) {
            throw new IllegalArgumentException("null consumer");
        }
        if (isEmpty()) {
            return;
        }

        final boolean found = search.contains(0, length, prefix, node.wrap(root), nodePool);
        if (found) {
            search.forEach(node, nodePool, consumer);
        }
   }

    /**
     * Remove the subtree matching the prefix
     * @param length prefix length
     * @param prefix prefix
     * @return matched
     */
    protected boolean removeStrings(final int length, final byte[] prefix) {
        if (isEmpty()) {
            return false;
        }

        var _ = search.mismatch(0, length, prefix, node.wrap(root), nodePool);
        if (search.found) {
             var _ = removeString(0, length, prefix, false);
         } else {
             final int treeOffset = search.removeStrings(prefix[length - 1] == search.key, node, nodePool);
             size -= search.removedStrings;
             allocatedNodes -= search.removedNodes;
             if (treeOffset == root.offset()) {
                 root.clear();
                 size = 0;
             }
        }
        return true;
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
                consumed = splitNode(remaining, length, key, search.keyPos, search.mismatch, node);
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
                consumed = addKey(length, key, search.keyPos, node);
                break;
            case Search.COMMON_PREFIX_AND_KEY:
                addChild(search.key, search.keyPos, node);
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
        if (search.key == EMPTY_KEY) {
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
                freeNode = Header.children(header) <= 1 && !Header.containsString(header);
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

    private int splitNode(final int remainingNode,
                          final int remainingString,
                          final byte key,
                          final int keyPos,
                          final int mismatch,
                          final Node current) {
        final byte header = current.header();
        final int count = Header.children(header);
        int block = EMPTY_BLOCK;
        if ((remainingString >= 2 && remainingNode >= 2) || (count >= 1 && key != NOT_FOUND)) {
            block = allocate(parent).offset();
            parent.copy(current);
        }
        if (remainingNode >= 2) {
            parent.removePrefix(mismatch + 1, remainingNode - 1);
        } else {
            if (count == 1) {
                parent.header(1, true, 0);
                parent.charAt(0, current.key(0));
            } else {
                final byte parentHeader = parent.header();
                parent.header(Header.clearStringLength(parentHeader));
            }
        }
        current
            .header(mismatch, remainingString == 0, 1)
            .child(0, current.charAt(mismatch), block, remainingNode <= 1 && count <= 1);
        return addKey(remainingString, key, keyPos, current);
    }

    private void addChild(final byte key, final int keyPos, final Node current) {
        final int block = allocate(child).offset();
        child.header(0, false, 0);
        current.child(keyPos, key, block, true);
        current.wrap(child);
    }

    private int addKey(final int remaining, final byte key, final int keyPos, final Node current) {
        final int block = remaining >= 2 ? allocate(parent).offset() : 0;
        final int consumed;
        if (Header.children(current.header()) < BLOCK_COUNT) {
            if (remaining == 0) {
                current.containsKey(keyPos, true);
                consumed = 0;
            } else {
                current.addChild(key, block, remaining == 1);
                consumed = 1;
            }
        } else {
            final int last = BLOCK_COUNT - 1;
            final int childBlock = allocate(child).offset();
            child
                .header(0, false, 0)
                .addChild(current.key(last), current.child(last), current.containsKey(last))
                .addChild(key, block, true);
            current.child(last, EMPTY_KEY, childBlock, false);
            current.wrap(child);
            consumed = 1;
        }
        if (block != EMPTY_BLOCK) {
            current.wrap(parent);
        }
        return consumed;
    }

    private void addString(final int offset, final int length, final byte[] string, final Node node) {
        int remaining = length;
        int position = offset;
        while (remaining >= 1) {
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

        int mismatchType;
        int mismatch;
        int position;
        byte key;
        int keyPos;
        int reuseKeyNodeOffset;
        boolean found;

        int removedStrings;
        int removedNodes;

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
            parent.wrap(node);
            pathCount = 0;
            found = false;
            pushPath(Path.offset(Path.EMPTY, node.offset()));
            byte header = node.header();
            int nodeLength = Header.stringLength(header);
            while (length >= 1) {
                if (nodeLength >= 1) {
                    mismatch = node.mismatch(position + stringPosition, length, string);
                    if (mismatch == EQUAL) {
                        key = EMPTY_KEY;
                        found = true;
                        return false;
                    }
                    position += mismatch;
                    length -= mismatch;
                    if (nodeLength > mismatch) {
                        mismatchType = mismatch == 0 ? NO_COMMON_PREFIX : COMMON_PREFIX;
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
                    if (key != EMPTY_KEY) {  // empty keys do not advance the input string
                        --length;
                        ++position;
                        if (length == 0) {
                            found = node.containsKey(keyPos);
                            return !found;
                        }
                    } else if (length == 1 && count < BLOCK_COUNT) {
                        reuseKeyNodeOffset = node.offset();
                    }
                    parent.wrap(node);

                    final int childBlock = node.child(keyPos);
                    if (childBlock != EMPTY_BLOCK) {
                        ensureCapacity();
                        pushPath(Path.path(key, keyPos, childBlock));
                        pool.get(Address.fromOffset(childBlock), node);
                        header = node.header();
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
            byte header = current.header();
            int nodeLength = Header.stringLength(header);
            while (length >= 1) {
                if (nodeLength >= 1) {
                    final int matched = current.mismatch(position + offset, length, string);
                    if (matched == EQUAL) {
                        found = true;
                        return true;
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
                    if (key != EMPTY_KEY) {
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
                        header = current.header();
                        nodeLength = Header.stringLength(header);
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
                    if (childBlock != EMPTY_BLOCK) {
                        ensureCapacity();
                        pushPath(Path.offset(Path.EMPTY, childBlock));
                    }
                }
            }
        }

        /**
         * Remove the strings in the subtree starting at node
         * @param matchedKey the prefix matched a key
         * @param node       subtree root node
         * @param pool       memory pool
         * @return subtree offset
         */
        int removeStrings(final boolean matchedKey, final Node node, final BlockPool<Node> pool) {
            removedNodes = 0;
            removedStrings = 0;

            final int treeOffset = node.offset();
            int keyPosition = keyPos;
            int childBlock = node.child(keyPosition);
            if (matchedKey && childBlock != EMPTY_BLOCK) {
                parent.wrap(node);
                pool.get(Address.fromOffset(childBlock), node);
                pushPath(Path.path(key, keyPosition, childBlock));
            }

            final int stop = pathCount;
            while (pathCount >= stop) { // remove subtree
                pool.get(Address.fromOffset(Path.offset(path[--pathCount])), node);
                final int children = Header.children(node.header());
                for (int i = 0; i < children; ++i) {
                    childBlock = node.child(i);
                    if (childBlock != EMPTY_BLOCK) {
                        ensureCapacity();
                        pushPath(Path.path(node.key(i), i, childBlock));
                    }
                }
                ++removedNodes;
                removedStrings += node.containsStringCount();
                pool.free(node);
            }

            // cleanup parent path
            boolean freeNode = true;
            for (int i = stop - 2; freeNode && i >= 0; --i) {
                final long current = path[i];
                final int offset = Path.offset(current);
                keyPosition = Path.position(current);
                pool.get(Address.fromOffset(offset), parent);

                final byte header = parent.header();
                freeNode = Header.children(header) <= 1 && (offset != EMPTY_BLOCK && !Header.containsString(header) && !parent.containsKey(keyPosition));
                if (freeNode) {
                    ++removedNodes;
                    removedStrings += parent.containsStringCount();
                    pool.free(parent);
                }
            }

            if (parent.memorySegment() != null && position != NOT_FOUND)  {
                byte parentHeader = parent.header();
                if (parent.containsKey(keyPosition)) {
                    parent.child(keyPosition, EMPTY_BLOCK);
                } else {
                    parent.removeChild(keyPosition);
                }
                if (Header.children(parentHeader) == 0 && Header.stringLength(parentHeader) == 0) {
                    ++removedNodes;
                    removedStrings += parent.containsStringCount();
                    pool.free(parent);
                }
            }

            return treeOffset;
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
