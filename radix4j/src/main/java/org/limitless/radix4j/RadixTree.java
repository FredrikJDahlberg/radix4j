package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockPool;

import java.lang.foreign.Arena;
import java.security.InvalidParameterException;
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
     * Constructs an emtpy tree with the given segment size
     * @param blocksPerSegment segment size
     */
    public RadixTree(final int blocksPerSegment) {
        if (blocksPerSegment < 64 || blocksPerSegment > MAX_BLOCKS_PER_SEGMENT) {
            throw new InvalidParameterException("invalid number of blocks per segment");
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
        if (Header.stringLength(header) == 0 && Header.indexCount(header) == 0) {
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
            final int index = node.index(position);
            if (node.containsKey(position)) {
                node.containsKey(position, false);
            }
            if (index == EMPTY_BLOCK) {
                node.removeIndex(position);
            }
        }
        header = node.header();
        if (Header.indexCount(header) == 0 && !Header.containsString(header)) {
            free(node);

            for (int i = search.pathCount - 2; i >= 0; --i) {
                pool.get(Address.fromOffset(search.pathOffsets[i]), node);

                header = node.header();
                final int count = Header.indexCount(header);
                final boolean contains = Header.containsString(header);
                final int position = search.pathKeyPositions[i + 1];
                node.removeIndex(position);
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

    @Override
    public String toString() {
        return "RadixTree{ size = " + size + ", " + pool + "}";
    }

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
        search.pathCount = 0;
        parent.wrap(root);
        forEach(parent, consumer);
    }

    private void forEach(final Node node, final Consumer<Node> consumer) {
        search.pathOffsets[0] = node.offset();
        ++search.pathCount;

        while (search.pathCount >= 1) {
            final int nodeOffset = search.pathOffsets[--search.pathCount];
            final long address = Address.fromOffset(nodeOffset);
            pool.get(address, node);

            consumer.accept(node);

            final byte header = node.header();
            final int count = Header.indexCount(header);
            for (int i = 0; i < count; ++i) {
                final int childOffset = node.index(i);
                if (childOffset != EMPTY_BLOCK) {
                    if (search.pathCount >= search.pathOffsets.length) {
                        search.pathOffsets = Arrays.copyOf(search.pathOffsets, search.pathOffsets.length * 2);
                    }
                    search.pathOffsets[search.pathCount++] = node.index(i);
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
            context.parent.index(position, parent.offset());
        }

        final byte header = node.header();
        final int count = Header.indexCount(header);
        final int foundOffset = remainingNode == 1 && count == 0 ? EMPTY_BLOCK : node.offset();
        final int childOffset = remainingString >= 2 ? allocate(child).offset() : EMPTY_BLOCK;
        if (remainingNode >= 1) {
            final byte foundKey = node.charAt(0);
            final boolean containsKey = Header.containsString(header) && Header.stringLength(header) == 1;
            parent
                .header(0, false, 0)
                .addIndex(foundKey, foundOffset,  containsKey)
                .addIndex(key, childOffset, remainingString == 1);

        }
        if (foundOffset == 0) {
            free(node);
            node.wrap(parent);
        } else {
            node.removePrefix(1, remainingNode - 1);
            if (remainingNode == 1) {
                node.header(Header.containsString(node.header(), false));
            }
        }
        if (childOffset != 0) {
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
        final int count = Header.indexCount(header);
        int offset = EMPTY_BLOCK;
        if ((remainingString >= 2 && remainingNode >= 2) || (count >= 1 && key != NOT_FOUND)) {
            offset = allocate(parent).offset();
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
            .index(0, node.charAt(mismatch), offset, remainingNode == 1);
        return addKey(remainingString, key, keyPos, node);
    }

    private void addChild(final byte key, final int keyPos, final Node node) {
        final int childOffset = allocate(child).offset();
        child.header(0, false, 0);
        node.index(keyPos, key, childOffset, true);
        node.wrap(child);
    }

    private int addKey(final int remaining, final byte key, final int keyPos, final Node node) {
        final int offset = remaining >= 2 ? allocate(parent).offset() : 0;
        final byte header = node.header();
        final int count = Header.indexCount(header);
        final int consumed;
        if (count < INDEX_COUNT) {
            if (remaining == 0) {
                node.containsKey(keyPos, true);
                consumed = 0;
            } else {
                node.addIndex(key, offset, remaining == 1);
                consumed = 1;
            }
        } else {
            final int last = INDEX_COUNT - 1;
            final int childOffset = allocate(child).offset();
            child
                .header(0, false, 0)
                .addIndex(node.key(last), node.index(last), node.containsKey(last))
                .addIndex(key, offset, true);
            node.index(last, EMPTY_KEY, childOffset, false);
            node.wrap(child);
            consumed = 1;
        }
        if (offset != EMPTY_BLOCK) {
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
                .header(stringLength, remaining == stringLength, Header.indexCount(header))
                .string(string, position, stringLength);
            position += stringLength;
            remaining -= stringLength;
            if (remaining >= 1) {
                final int childOffset = remaining == 1 ? 0 : allocate(child).offset();
                node
                    .header(stringLength, false, 1)
                    .index(0, string[position], childOffset, remaining == 1);
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
            throw new IllegalStateException("too many segments " + segments);
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

        final Node node;
        final Node parent;

        int[] pathOffsets = new int[INITIAL_PATH_SIZE];
        int[] pathKeyPositions = new int[INITIAL_PATH_SIZE];
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
        private boolean mismatch(final int offset,
                                 int length,
                                 final byte[] string,
                                 final boolean withPath,
                                 final Node root,
                                 final BlockPool<Node> pool) {
            position = 0;
            mismatch = 0;
            key = NOT_FOUND;
            keyPos = NOT_FOUND;
            mismatchType = TYPE_NULL;
            node.wrap(root);
            parent.wrap(root);
            pathOffsets[0] = root.offset();
            pathKeyPositions[0] = 0;
            pathCount = 1;

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
                    final int count = Header.indexCount(header);
                    final int foundPos = node.keyPosition(count, string[position + offset]);
                    if (foundPos == NOT_FOUND) {
                        return true;
                    }

                    keyPos = foundPos;
                    key = node.key(foundPos);
                    if (key != EMPTY_KEY) {  // empty keys do not advance the input string
                        --length;
                        ++position;
                        if (length == 0) {
                            return !node.containsKey(foundPos);
                        }
                        processString = true;
                    }
                    parent.wrap(node);

                    final int childOffset = node.index(foundPos);
                    if (childOffset != EMPTY_BLOCK) {
                        if (withPath) {
                            updatePath(childOffset, foundPos);
                        }
                        pool.get(Address.fromOffset(childOffset), node);
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

        void updatePath(int offset, int keyPosition) {
            if (pathOffsets.length == pathCount + 1) {
                pathOffsets = Arrays.copyOf(pathOffsets, pathOffsets.length * 2);
                pathKeyPositions = Arrays.copyOf(pathKeyPositions, pathKeyPositions.length * 2);
            }
            pathOffsets[pathCount] = offset;
            pathKeyPositions[pathCount] = keyPosition;
            ++pathCount;
        }
    }
}
