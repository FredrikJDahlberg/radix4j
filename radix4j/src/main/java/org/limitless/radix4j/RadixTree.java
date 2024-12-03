package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockPool;

import java.lang.foreign.Arena;
import java.security.InvalidParameterException;
import java.util.Arrays;
import java.util.function.Consumer;

import static org.limitless.radix4j.Node.*;

public class RadixTree {

    public static final int MAX_BLOCK_COUNT = 1 << 16;
    public static final int DEFAULT_BLOCK_COUNT = 1 << 8;

    protected static final int MAX_SEGMENT_COUNT = 1 << 8;

    private static final int INITIAL_PATH_SIZE = 32;

    private static final int TYPE_NULL = 0;
    private static final int TYPE_SUBSTRING = 1;
    private static final int TYPE_COMMON_PREFIX = 2;
    private static final int TYPE_COMMON_PREFIX_AND_KEY = 3;
    private static final int TYPE_NO_COMMON_PREFIX = 4;
    private static final int TYPE_MISSING_KEY = 5;

    private final BlockPool<Node> pool;
    private final Node root;
    private final Node child;
    private final Node parent;
    private final SearchState search;

    private int size;
    private int allocatedBlocks;

    public RadixTree() {
        this(DEFAULT_BLOCK_COUNT);
    }

    public RadixTree(final int blocksPerSegment) {
        if (blocksPerSegment < 64 || blocksPerSegment > MAX_BLOCK_COUNT) {
            throw new InvalidParameterException("invalid number of blocks per segment");
        }
        pool = new BlockPool.Builder<>(Arena.ofShared(), Node.class).blocksPerSegment(blocksPerSegment).build();
        parent = allocate(new Node());
        root = allocate(new Node());
        child = allocate(new Node());
        search = new SearchState(allocate(new Node()), allocate(new Node()));
    }

    /**
     * Add a string to the collection.
     * @param string value
     * @return true when value is inserted
     */
    public boolean add(final String string) {
        return add(string.getBytes());
    }

    /**
     * Add a string to the collection.
     * @param string value
     * @return true when value is inserted
     */
    public boolean add(final byte[] string) {
        final byte header = root.header();
        if (Header.stringLength(header) == 0 && Header.indexCount(header) == 0) {
            addString(string.length, string, 0, search.found.wrap(root));
            ++size;
            return true;
        }
        if (!search.mismatch(pool, root, string, string.length, false)) {
            return false;
        }
        ++size;

        addString(search.position, string.length, string, search);
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
        return !search.mismatch(pool, root, string, string.length, false);
    }

    /**
     * Remove string form collection
     * @param string value
     */
    public boolean remove(final String string) {
        return remove(string.getBytes());
    }

    /**
     * Remove string form collection
     * @param string value
     */
    public boolean remove(final byte[] string) {
        if (isEmpty() || search.mismatch(pool, root, string, string.length, true)) {
            return false;
        }
        --size;

        final Node found = search.found;
        byte header = found.header();
        if (search.key == EMPTY_KEY) {
            found.header(Header.completeString(header, false));
        } else {
            final int position = search.keyPos;
            final int index = found.index(position);
            if (Index.completeKey(index)) {
                found.index(position, Index.completeKey(index, false));
            }
            if (Index.offset(index) == EMPTY_BLOCK) {
                found.removeIndex(position);
            }
        }
        header = found.header();
        if (Header.indexCount(header) == 0 && !Header.completeString(header)) {
            free(found);

            for (int i = search.pathCount - 2; i >= 0; --i) {
                pool.get(Address.fromOffset(search.pathOffsets[i]), found);

                header = found.header();
                final int count = Header.indexCount(header);
                final boolean complete = Header.completeString(header);
                final int position = search.pathKeyPositions[i + 1];
                found.removeIndex(position);
                if (count >= 2 || complete) {
                    break;
                } else {
                    free(found);
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
     * Erase all elements in the collection
     */
    public void clear() {
        size = 0;
        pool.close();
    }

    /**
     * The number of allocated blocks in the collection.
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
                final int childOffset = Index.offset(node.index(i));
                if (childOffset != EMPTY_BLOCK) {
                    if (search.pathCount >= search.pathOffsets.length) {
                        search.pathOffsets = Arrays.copyOf(search.pathOffsets, search.pathOffsets.length * 2);
                    }
                    search.pathOffsets[search.pathCount++] = node.index(i);
                }
            }
        }
    }

    @Override
    public String toString() {
        return "RadixTree{ size = " + size + ", bytes = " + pool.allocatedBytes() + " }";
    }

    private void addString(final int offset, final int length, final byte[] string, final SearchState context) {
        final byte foundHeader = context.found.header();
        final int nodeLength = Header.stringLength(foundHeader);
        final int remainingNode = nodeLength - context.mismatch;
        int remainingString = length - offset;
        int consumed = 0;
        final byte key = remainingString >= 1 ? string[offset] : context.key;
        switch (context.mismatchType) {
            case TYPE_COMMON_PREFIX:
                consumed = splitNode(remainingNode, key, offset, remainingString, context.found,
                    context.mismatch);
                break;
            case TYPE_NO_COMMON_PREFIX:
                addParent(remainingNode, key, remainingString, context);
                consumed = 1;
                break;
            case TYPE_SUBSTRING:
                context.found.header(Header.completeString(foundHeader, true));
                break;
            case TYPE_MISSING_KEY:
                consumed = addKey(remainingString, key, context.keyPos, context.found);
                break;
            case TYPE_COMMON_PREFIX_AND_KEY:
                addChild(context.key, context.keyPos, context.found);
                break;
            default:
                break;
        }
        if (remainingString - consumed >= 1) {
            addString(remainingString - consumed, string, offset + consumed, context.found);
        }
    }

    private void addParent(int remainingNode, final byte key, int remainingString, final SearchState context) {
        allocate(parent);
        final Node found = context.found;
        if (found.equals(root)) {
            root.wrap(parent);
        }
        final int position = context.keyPos;
        if (position >= 0) {
            int index = context.parent.index(position);
            boolean complete = Index.completeKey(index);
            byte parentKey = Index.key(index);
            context.parent.index(position, parentKey, parent.offset(), complete);
        }

        final byte foundHeader = found.header();
        final int foundOffset = remainingNode == 1 && Header.indexCount(foundHeader) == 0 ? 0 : found.offset();
        final int childOffset = remainingString >= 2 ? allocate(child).offset() : 0;
        if (remainingNode >= 1) {
            final byte foundKey = found.string(0);
            parent
                .header(0, false, 0)
                .addIndex(foundKey, foundOffset, foundOffset == 0)
                .addIndex(key, childOffset, remainingString == 1);

        }
        if (foundOffset == 0) {
            free(found);
            found.wrap(parent);
        } else {
            found.moveString(1, remainingNode - 1);
        }
        if (childOffset != 0) {
            found.wrap(child);
        }
    }

    private int splitNode(final int remainingNode,
                          final byte key,
                          final int keyPos,
                          final int remainingString,
                          final Node found,
                          final int mismatch) {
        final byte foundHeader = found.header();
        final int count = Header.indexCount(foundHeader);
        int parentOffset = 0;
        if (remainingNode != 1 || remainingString != 1 || count != 0) {
            parentOffset = allocate(parent).offset();
            parent.copy(found);
        }
        if (remainingNode >= 2) {
            parent.moveString(mismatch + 1, remainingNode - 1);
        } else {
            if (count == 1) {
                int foundIndex = found.index(0);
                parent
                    .header(1, true, 0)
                    .string(0, Index.key(foundIndex));
            } else {
                parent.header(Header.stringLength(parent.header(), 0));
            }
        }
        found
            .header(mismatch, remainingString == 0, 1)
            .index(0, found.string(mismatch), parentOffset, remainingString == 1);
        return addKey(remainingString, key, keyPos, found);
    }

    private void addChild(final byte key, final int keyPos, final Node found) {
        final int childOffset = allocate(child).offset();
        child.header(0, false, 0);
        found.index(keyPos, key, childOffset, true);
        found.wrap(child);

    }

    private int addKey(final int length, final byte key, final int keyPos, final Node found) {
        final int offset = length >= 2 ? allocate(parent).offset() : 0;
        final byte foundHeader = found.header();
        final int count = Header.indexCount(foundHeader);
        final int consumed;
        if (count < MAX_INDEX_COUNT) {
            if (length == 0) {
                final int index = found.index(keyPos);
                found.index(keyPos, Index.completeKey(index, true));
                consumed = 0;
            } else {
                found.addIndex(key, offset, length == 1);
                consumed = 1;
            }
        } else {
            final int index = found.index(MAX_INDEX_COUNT - 1);
            final int childOffset = allocate(child).offset();
            found
                .index(MAX_INDEX_COUNT - 1, EMPTY_KEY, childOffset, false);
            child
                .header(0, false, 0)
                .index(0, index)
                .addIndex(key, offset, true);
            consumed = 1;
            found.wrap(child);
        }
        if (offset != 0) {
            search.found.wrap(parent);
        }
        return consumed;
    }

    private void addString(final int length, final byte[] string, final int offset, final Node found) {
        int remaining = length;
        int position = offset;
        while (remaining >= 1) {
            final int stringLength = Math.min(STRING_LENGTH, remaining);
            final byte foundHeader = found.header();
            found
                .header(stringLength, remaining == stringLength, Header.indexCount(foundHeader))
                .string(string, position, stringLength);
            position += stringLength;
            remaining -= stringLength;
            if (remaining >= 1) {
                final int childOffset = remaining == 1 ? 0 : allocate(child).offset();
                found
                    .header(stringLength, false, 1)
                    .index(0, string[position], childOffset, remaining == 1);
                found.wrap(child);
            }
            --remaining;
            ++position;
        }
    }

    private Node allocate(final Node node) {
        ++allocatedBlocks;

        final int segments = allocatedBlocks / MAX_BLOCK_COUNT;
        if (segments >= MAX_SEGMENT_COUNT) {
            throw new IllegalStateException("too many segments " + segments);
        }

        pool.allocate(node);
        node.header((byte) 0);
        return node;
    }

    private void free(final Node node) {
        if (root.address() == search.found.address()) {
            root.header(0, false, 0);
        } else {
            --allocatedBlocks;
            pool.free(node);
        }
    }

    private static final class SearchState {
        int mismatchType;
        int mismatch;
        int position;

        byte key;
        int keyPos;

        final Node found;
        final Node parent;

        int[] pathOffsets = new int[INITIAL_PATH_SIZE];
        int[] pathKeyPositions = new int[INITIAL_PATH_SIZE];
        int pathCount;

        SearchState(final Node found, final Node parent) {
            this.found = found;
            this.parent = parent;
        }

        /**
         * Find the insertion point for a new string
         * @param pool block pool
         * @param string UTF-8 bytes
         * @param length string length
         * @param root the insertion point data
         * @return true when mismatch exists
         */
        private boolean mismatch(final BlockPool<Node> pool,
                                 final Node root,
                                 final byte[] string,
                                 final int length,
                                 final boolean withPath) {
            mismatch = 0;
            position = 0;
            key = NOT_FOUND;
            keyPos = NOT_FOUND;
            mismatchType = TYPE_NULL;
            found.wrap(root);
            parent.wrap(root);
            pathOffsets[0] = root.offset();
            pathKeyPositions[0] = 0;
            pathCount = 1;

            boolean processString = true;
            int remaining = length;
            byte foundHeader = found.header();
            int nodeLength = Header.stringLength(foundHeader);
            while (remaining >= 1) {
                if (processString) {
                    if (nodeLength >= 1) {
                        mismatch = found.mismatch(string, position, length);
                        if (mismatch == -1) {
                            key = EMPTY_KEY;
                            return false;
                        }
                        remaining -= mismatch;
                        position += mismatch;
                    }
                    if (mismatch < nodeLength) {
                        mismatchType = nodeLength >=1 && mismatch == 0 ? TYPE_NO_COMMON_PREFIX : TYPE_COMMON_PREFIX;
                        return true;
                    }
                    processString = false;
                } else {
                    mismatchType = TYPE_MISSING_KEY;
                    final int count = Header.indexCount(foundHeader);
                    final int foundPos = found.position(count, string[position]);
                    if (foundPos == NOT_FOUND) {
                        return true;
                    }

                    final int foundIndex = found.index(foundPos);
                    keyPos = foundPos;
                    key = Index.key(foundIndex);
                    if (key != EMPTY_KEY) {  // empty keys do not advance the input string
                        --remaining;
                        ++position;
                        if (remaining == 0) {
                            return !Index.completeKey(foundIndex);
                        }
                        processString = true;
                    }
                    parent.wrap(found);

                    final int childOffset = Index.offset(foundIndex);
                    if (childOffset != EMPTY_BLOCK) {
                        if (withPath) {
                            updatePath(childOffset, foundPos);
                        }
                        pool.get(Address.fromOffset(childOffset), found);
                        foundHeader = found.header();
                        nodeLength = Header.stringLength(foundHeader);
                    } else {
                        if (mismatch < position) {
                            mismatchType = TYPE_COMMON_PREFIX_AND_KEY;
                            return true;
                        }
                    }
                }
            }
            if (mismatch == nodeLength && remaining == 0) {
                mismatchType = TYPE_SUBSTRING;
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
