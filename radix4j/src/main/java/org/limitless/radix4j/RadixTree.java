package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockPool;

import java.lang.foreign.Arena;
import java.util.Arrays;
import java.util.function.Consumer;

import static org.limitless.radix4j.Node.*;

public class RadixTree {

    public static final int DEFAULT_BLOCK_COUNT = 256;
    public static final int MAX_BLOCKS_PER_SEGMENT = Address.MAX_BLOCKS;

    private static final int INITIAL_PATH_SIZE = 32;

    private BlockPool<Node> pool;
    private Node root;
    private Node child;
    private Node parent;
    private Search search;

    private int size;
    private int allocatedBlocks;

    private final int blocksPerSegment;

    /**
     * Constructs an empty tree with the default segment size
     */
    public RadixTree() {
        this(DEFAULT_BLOCK_COUNT);
    }

    /**
     * Constructs an empty tree with the given segment size
     * @param blocksPerSegment segment size
     */
    public RadixTree(final int blocksPerSegment) {
        if (blocksPerSegment < 64 || blocksPerSegment > MAX_BLOCKS_PER_SEGMENT) {
            throw new IllegalArgumentException("invalid number of blocks per segment");
        }
        this.blocksPerSegment = blocksPerSegment;
        initiate(blocksPerSegment);
    }

    /**
     * Allocates an empty tree with the given segment size
     * @param blocksPerSegment segment size
     * @return tree
     */
    public static RadixTree allocate(final int blocksPerSegment) {
        return new RadixTree(blocksPerSegment);
    }

    /**
     * Add a string to the tree
     * @param string value
     * @return true when value is inserted
     */
    public boolean add(final String string) {
        return add(string.getBytes());
    }

    /**
     * Add a string to the tree.
     * @param string value
     * @return true when value is inserted
     */
    public boolean add(final byte[] string) {
        return add(0, string.length, string);
    }

    /**
     * Add string to the tree.
     * @param offset string offset
     * @param length string length
     * @param string value
     * @return true when value is inserted
     */
    public boolean add(final int offset, final int length, final byte[] string) {
        if (offset < 0 || length <= 0 || string == null || offset + length > string.length) {
            return false;
        }

        final byte header = root.header();
        if (Header.stringLength(header) == 0 && Header.children(header) == 0) {
            addString(offset, length, string, search.node.wrap(root));
            ++size;
            return true;
        }
        if (!search.mismatch(offset, length, string, false, root, pool)) {
            return false;
        }
        addString(search.position + offset, length - search.position, string, search);
        ++size;

        return true;
    }

    /**
     * Check value presence
     * @param string value
     * @return true if the string is present
     */
    public boolean contains(final String string) {
        if (isEmpty()) {
            return false;
        }
        return contains(string.getBytes());
    }

    /**
     * Check value presence
     * @param string value
     * @return true if the string is present
     */
    public boolean contains(final byte[] string) {
        return contains(0, string.length, string);
    }

    /**
     * Check the value presence
     * @param offset value offset
     * @param length value length
     * @param string value
     * @return true when string is present
     */
    public boolean contains(final int offset, final int length, final byte[] string) {
        if (offset < 0 || length <= 0 || string == null || offset + length > string.length) {
            return false;
        }
        return !search.mismatch(offset, length, string, false, root, pool);
    }

    /**
     * Remove string form collection
     * @param string value
     * @return true if removed
     */
    public boolean remove(final String string) {
        return remove(string.getBytes());
    }

    /**
     * Remove string form collection
     * @param string value
     * @return true if removed
     */
    public boolean remove(final byte[] string) {
        return remove(0, string.length, string);
    }

    /**
     * Remove string from tree
     * @param offset value offset
     * @param length value length
     * @param string value
     * @return if removed
     */
    public boolean remove(final int offset, final int length, final byte[] string) {
        if (offset < 0 || length <= 0 || string == null || offset + length > string.length) {
            return false;
        }
        if (isEmpty() || search.mismatch(offset, length, string, true, root, pool)) {
            return false;
        }
        --size;

        final Node node = search.node;
        byte header = node.header();
        if (search.key == EMPTY_KEY) {
            node.header(Header.containsString(header, false));
        } else {
            final int position = search.keyPos;
            final int block = node.child(position);
            if (node.containsKey(position)) {
                node.containsKey(position, false);
            }
            if (block == EMPTY_BLOCK) {
                node.removeChild(position);
            }
        }
        header = node.header();
        if (Header.children(header) == 0 && !Header.containsString(header)) {
            free(node);

            for (int i = search.pathCount - 2; i >= 0; --i) {
                final long path = search.path[i];
                pool.get(Address.fromOffset(Path.block(path)), node);

                header = node.header();
                final int count = Header.children(header);
                final boolean contains = Header.containsString(header);
                final int position = Path.position(search.path[i + 1]);
                final boolean containsKey = node.containsKey(position);
                if (containsKey) {
                    node.child(position, EMPTY_BLOCK);
                } else {
                    node.removeChild(position);
                }
                if (count >= 2 || contains) {
                    break;
                } else {
                    free(node);
                }
            }
        }
        return true;
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
     * Resets the tree to its initial state.
     */
    public void clear() {
        pool.close();
        initiate(blocksPerSegment);
    }

    /**
     * Destroys the backing memory store.
     */
    public void close() {
        size = 0;
        pool.close();
    }

    /**
     * Returns a string representation of the object.
     * @return string
     */
    @Override
    public String toString() {
        return "RadixTree{ size = " + size + ", " + pool + "}";
    }

    /**
     * Returns the number of allocated blocks
     * @return block count
     */
    protected int allocatedBlocks() {
        return allocatedBlocks;
    }

    /**
     * Iterate of the nodes in the collection
     * @param consumer node consumer
     */
    protected void forEach(final Consumer<Node> consumer) {
        if (consumer == null) {
            throw new IllegalArgumentException("null consumer");
        }
        if (root == null) {
            return;
        }
        parent.wrap(root);
        forEach(parent, consumer);
    }

    private void forEach(final Node node, final Consumer<Node> consumer) {
        search.path[0] = Path.block(0, node.offset());
        search.pathCount = 1;

        while (search.pathCount >= 1) {
            final int block = Path.block(search.path[--search.pathCount]);
            final long address = Address.fromOffset(block);
            pool.get(address, node);

            consumer.accept(node);

            final byte header = node.header();
            final int count = Header.children(header);
            for (int i = 0; i < count; ++i) {
                final int childBlock = node.child(i);
                if (childBlock != EMPTY_BLOCK) {
                    search.ensureCapacity();
                    search.path[search.pathCount++] = Path.block(0, childBlock);
                }
            }
        }
    }

    private void addString(final int offset, final int length, final byte[] string, final Search search) {
        final byte header = search.node.header();
        final int stringLength = Header.stringLength(header);
        final int remaining = stringLength - search.mismatch;
        int consumed = 0;
        final byte key = length >= 1 ? string[offset] : search.key;

        if (search.emptyOffset != EMPTY_BLOCK) {
            pool.get(Address.fromOffset(search.emptyOffset), search.node);
        }
        switch (search.mismatchType) {
            case Search.COMMON_PREFIX:
                consumed = splitNode(remaining, length, key, search.keyPos, search.node, search.mismatch);
                break;
            case Search.NO_COMMON_PREFIX:
                addParent(remaining, key, length, search);
                consumed = 1;
                break;
            case Search.SUBSTRING:
                search.node.header(Header.containsString(header, true));
                break;
            case Search.MISSING_KEY:
                consumed = addKey(length, key, search.keyPos, search.node);
                break;
            case Search.COMMON_PREFIX_AND_KEY:
                addChild(search.key, search.keyPos, search.node);
                break;
            default:
                break;
        }
        if (length - consumed >= 1) {
            addString(offset + consumed, length - consumed, string, search.node);
        }
    }

    private void addParent(int remainingNode, final byte key, int remainingString, final Search context) {
        allocate(parent);
        final Node node = context.node;
        if (node.equals(root)) {
            root.wrap(parent);
        }

        final int position = context.keyPos;
        if (position >= 0) {
            context.parent.child(position, parent.offset());
        }

        final byte header = node.header();
        final int count = Header.children(header);
        final int block = remainingNode == 1 && count == 0 ? EMPTY_BLOCK : node.offset();
        final int childBlock = remainingString >= 2 ? allocate(child).offset() : EMPTY_BLOCK;
        if (remainingNode >= 1) {
            final byte foundKey = node.charAt(0);
            final boolean containsKey = Header.containsString(header) && Header.stringLength(header) == 1;
            parent
                .header(0, false, 0)
                .addChild(foundKey, block,  containsKey)
                .addChild(key, childBlock, remainingString == 1);

        }
        if (block == 0) {
            free(node);
            node.wrap(parent);
        } else {
            node.removePrefix(1, remainingNode - 1);
            if (remainingNode == 1) {
                node.header(Header.containsString(node.header(), false));
            }
        }
        if (childBlock != 0) {
            node.wrap(child);
        }
    }

    private int splitNode(final int remainingNode,
                          final int remainingString,
                          final byte key,
                          final int keyPos,
                          final Node node,
                          final int mismatch) {
        final byte header = node.header();
        final int count = Header.children(header);
        int block = EMPTY_BLOCK;
        if ((remainingString >= 2 && remainingNode >= 2) || (count >= 1 && key != NOT_FOUND)) {
            block = allocate(parent).offset();
            parent.copy(node);
        }
        if (remainingNode >= 2) {
            parent.removePrefix(mismatch + 1, remainingNode - 1);
        } else {
            if (count == 1) {
                parent.header(1, true, 0);
                parent.charAt(0, node.key(0));
            } else {
                final byte parentHeader = parent.header();
                parent.header(Header.clearStringLength(parentHeader));
            }
        }
        node
            .header(mismatch, remainingString == 0, 1)
            .child(0, node.charAt(mismatch), block, remainingNode <= 1 && count == 0);
        return addKey(remainingString, key, keyPos, node);
    }

    private void addChild(final byte key, final int keyPos, final Node node) {
        final int block = allocate(child).offset();
        child.header(0, false, 0);
        node.child(keyPos, key, block, true);
        node.wrap(child);
    }

    private int addKey(final int remaining, final byte key, final int keyPos, final Node node) {
        final int block = remaining >= 2 ? allocate(parent).offset() : 0;
        final byte header = node.header();
        final int count = Header.children(header);
        final int consumed;
        if (count < BLOCK_COUNT) {
            if (remaining == 0) {
                node.containsKey(keyPos, true);
                consumed = 0;
            } else {
                node.addChild(key, block, remaining == 1);
                consumed = 1;
            }
        } else {
            final int last = BLOCK_COUNT - 1;
            final int childBlock = allocate(child).offset();
            child
                .header(0, false, 0)
                .addChild(node.key(last), node.child(last), node.containsKey(last))
                .addChild(key, block, true);
            node.child(last, EMPTY_KEY, childBlock, false);
            node.wrap(child);
            consumed = 1;
        }
        if (block != EMPTY_BLOCK) {
            search.node.wrap(parent);
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
        ++allocatedBlocks;
        final int segments = allocatedBlocks / blocksPerSegment;
        if (segments >= Address.MAX_SEGMENTS) {
            throw new IllegalStateException("out of segments " + segments);
        }

        pool.allocate(node);
        node.header((byte) 0);
        return node;
    }

    private void free(final Node node) {
        if (root.address() == search.node.address()) {
            root.header(0, false, 0);
        } else {
            --allocatedBlocks;
            pool.free(node);
        }
    }

    private void initiate(final int blocksPerSegment) {
        size = 0;
        allocatedBlocks = 0;
        pool = new BlockPool.Builder<>(Arena.ofShared(), Node.class).blocksPerSegment(blocksPerSegment).build();
        parent = allocate(new Node());
        root = allocate(new Node());
        child = allocate(new Node());
        search = new Search(allocate(new Node()), allocate(new Node()));
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
        int emptyOffset;

        final Node node;
        final Node parent;

        long[] path = new long[INITIAL_PATH_SIZE];
        int pathCount;

        Search(final Node node, final Node parent) {
            this.node = node;
            this.parent = parent;
        }

        /**
         * Find the insertion point for a new string
         * @param length string length
         * @param string bytes
         * @param root   the insertion point data
         * @param pool   block pool
         * @return true when mismatch exists
         */
        boolean mismatch(final int offset, int length,
                         final byte[] string,
                         final boolean withPath,
                         final Node root,
                         final BlockPool<Node> pool) {
            mismatchType = TYPE_NULL;
            key = NOT_FOUND;
            keyPos = NOT_FOUND;
            position = 0;
            mismatch = 0;
            pathCount = 1;
            emptyOffset = EMPTY_BLOCK;
            node.wrap(root);
            parent.wrap(root);
            path[0] = Path.block(0, root.offset());
            boolean processString = true;
            byte header = node.header();
            int nodeLength = Header.stringLength(header);
            while (length >= 1) {
                if (processString) {
                    if (nodeLength >= 1) {
                        mismatch = node.mismatch(position + offset, length, string);
                        if (mismatch == -1) {
                            key = EMPTY_KEY;
                            return false;
                        }
                        length -= mismatch;
                        position += mismatch;
                    }
                    if (mismatch < nodeLength) {
                        mismatchType = nodeLength >=1 && mismatch == 0 ? NO_COMMON_PREFIX : COMMON_PREFIX;
                        return true;
                    }
                    processString = false;
                } else {
                    mismatchType = MISSING_KEY;
                    final int count = Header.children(header);
                    keyPos = node.keyPosition(count, string[position + offset]);
                    if (keyPos == NOT_FOUND) {
                        return true;
                    }
                    key = node.key(keyPos);
                    if (key != EMPTY_KEY) {  // empty keys do not advance the input string
                        --length;
                        ++position;
                        if (length == 0) {
                            return !node.containsKey(keyPos);
                        }
                        processString = true;
                    } else if (count < BLOCK_COUNT) {
                        emptyOffset = node.offset();
                    }
                    parent.wrap(node);

                    final int childBlock = node.child(keyPos);
                    if (childBlock != EMPTY_BLOCK) {
                        if (withPath) {
                            ensureCapacity();
                            path[pathCount++] = Path.path((byte) 0, keyPos, childBlock);
                        }
                        pool.get(Address.fromOffset(childBlock), node);
                        header = node.header();
                        nodeLength = Header.stringLength(header);
                    } else {
                        if (mismatch < position) {
                            mismatchType = COMMON_PREFIX_AND_KEY;
                            return true;
                        }
                    }
                }
            }
            if (mismatch == nodeLength && length == 0) {
                mismatchType = SUBSTRING;
            }
            keyPos = -1;
            return true;
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

        public static int position(final long path) {
            return (int) ((path >>> POSITION_OFFSET) & POSITION_MASK);
        }

        public static long block(final long  path, final int block) {
            return path | ((block & BLOCK_MASK) << BLOCK_OFFSET);
        }

        public static int block(final long path) {
            return (int) ((path >>> BLOCK_OFFSET) & BLOCK_MASK);
        }

        public static long path(final byte key, final int position, final int block) {
            long value = 0;
            value |= (position & POSITION_MASK) << POSITION_OFFSET;
            value |= ((block & BLOCK_MASK) << BLOCK_OFFSET);
            value |= (key & KEY_MASK) << KEY_OFFSET;
            return value;
        }
    }
}
