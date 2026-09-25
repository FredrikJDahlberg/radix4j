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
        nodePool = BlockPool.builder(arena, Node::new).blocksPerSegment(blocksPerSegment).build();
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
     * the string equal to the prefix is stored in the key's node and is not visited. An empty prefix
     * visits the whole tree.
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
        if (match == Search.PREFIX_KEY) {
            // the prefix ends at a key: remove the key's string and subtree
            final int keyPos = search.keyPos;
            if (node.containsKey(keyPos)) {
                node.containsKey(keyPos, false);
                --size;
            }
            final int childBlock = node.child(keyPos);
            if (childBlock != EMPTY_BLOCK) {
                freeSubtree(childBlock);
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
                if (childBlock != EMPTY_BLOCK) {
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

        // detach the freed node from its parent and free ancestors left without strings
        for (; level >= 1; --level) {
            final int keyPos = Path.position(search.path[level]);
            nodePool.get(Address.fromOffset(Path.offset(search.path[level - 1])), node);
            if (node.containsKey(keyPos)) {
                node.child(keyPos, EMPTY_BLOCK);
                return true;
            }
            node.removeChild(keyPos);
            if (!isUnused(node)) {
                return true;
            }
            freeNode(node);
        }
        return true;
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
            if (Header.containsString(header)) {
                --size;
            }
            final int count = Header.children(header);
            for (int i = 0; i < count; ++i) {
                if (child.containsKey(i)) {
                    --size;
                }
                final int childBlock = child.child(i);
                if (childBlock != EMPTY_BLOCK) {
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
            final int childBlock = allocate(child).offset();
            child
                .header(0, false, 0)
                .addChild(current.key(last), current.child(last), current.containsKey(last))
                .addChild(key, block, remaining == 1);
            current.child(last, EMPTY_KEY, childBlock, false);
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

        private static final int PREFIX_NOT_FOUND = 0;
        private static final int PREFIX_NODE = 1;
        private static final int PREFIX_KEY = 2;

        int mismatchType;
        int mismatch;
        int position;
        byte key;
        int keyPos;
        int reuseKeyNodeOffset;
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
                if (nodeLength >= 1) {
                    mismatch = Node.mismatch(headerAndString, position + stringPosition, length, string);
                    if (mismatch == EQUAL) {
                        key = EMPTY_KEY;
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
                    if (key != EMPTY_KEY) {  // empty keys do not advance the input string
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
                    if (childBlock != EMPTY_BLOCK) {
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
         *         the key at keyPos, PREFIX_NOT_FOUND when no string starts with the prefix
         */
        int findPrefix(int length, final byte[] prefix, final Node node, final BlockPool<Node> pool) {
            keyPos = NOT_FOUND;
            pathCount = 0;
            pushPath(Path.offset(Path.EMPTY, node.offset()));
            int position = 0;
            while (true) {
                final long headerAndString = node.headerAndString();
                final byte header = Node.headerOf(headerAndString);
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
                if (nodeKey != EMPTY_KEY) {
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
