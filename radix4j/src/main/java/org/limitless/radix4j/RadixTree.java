package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockPool;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.function.Consumer;

public class RadixTree {

    // FIXME: add segment to key index (more than one segment)
    // FIXME: remove set node to the start node of long strings

    private static final String BLOCKS_PER_SEGMENT_PROP = "radix4j.blocks.per.segment";
    private static final int BLOCKS_PER_SEGMENT = Integer.getInteger(BLOCKS_PER_SEGMENT_PROP, 128 * 1024);

    private final BlockPool<Node3> pool;
    private Node3 root;
    private final Node3 child;
    private final Node3 parent;
    private final NodeState state;

    private int size;

    private int allocatedBlocks;

    private static final int TYPE_NULL = 0;
    private static final int TYPE_SUBSTRING = 1;
    private static final int TYPE_COMMON_PREFIX = 2;
    private static final int TYPE_COMMON_PREFIX_AND_KEY = 3;
    private static final int TYPE_NO_COMMON_PREFIX = 4;
    private static final int TYPE_MISSING_KEY = 5;

    public RadixTree() {
        pool = new BlockPool.Builder<>(Arena.ofShared(), Node3.class).blocksPerSegment(BLOCKS_PER_SEGMENT).build();
        parent = pool.allocate();
        child = pool.allocate();
        state = new NodeState(pool.allocate(), pool.allocate(), pool.allocate());
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
        if (root == null) {
            root = pool.allocate().completeString(false);
            state.found.wrap(root);
            addString(string.length, string, 0, state.found);
            ++size;
            return true;
        }
        if (!state.mismatch(root, string, string.length)) {
            return false;
        }
        ++size;

        addString(state.position, string.length, string, state);
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
        return !state.mismatch(root, string, string.length);
    }

    /**
     * Remove string form collection
     * @param string value
     */
    public void remove(final String string) {
        remove(string.getBytes());
    }

    /**
     * Remove string form collection
     * @param string value
     */
    public void remove(final byte[] string) {
        if (isEmpty()) {
            return;
        }
        if (state.mismatch(root, string, string.length))
        {
            return;
        }
        --size;

        final Node3 node = state.found;
        final int position = state.keyPosition;
        int count = node.keyCount();
        if (state.key == Node.EMPTY_KEY || count == 0) {
            node.completeString(false);
            return;
        }
        if (count >= 1 && node.completeKey(position) && node.child(position) != Node.EMPTY_KEY) {
            node.completeKey(position, false);
            return;
        }
        if (count >= 1) {
            final int last = count - 1;
            if (count >= 2 && position < last) {
                final boolean complete = node.completeKey(last);
                node.index(position, node.key(last), node.child(last)).completeKey(position, complete);
            }
            node.keyCount(count - 1);
        }
        System.out.println("Node = " + node);
        if (count == 1 && !node.completeString()) {
            child.wrap(node);
            while (count == 1) {
                final MemorySegment memory = child.memorySegment();
                final int segment = child.segment();
                int childBlock = child.child(0);
                count = child.keyCount();
                free(child);
                child.wrap(memory, segment, childBlock);
            }
        }
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
    protected void forEach(final Consumer<Node3> consumer) {
        if (root == null) {
            return;
        }
        parent.wrap(root);

        final int[] indices = new int[256];
        indices[0] = parent.block();
        int remaining = 1;
        while (remaining >= 1) {
            if (indices[--remaining] != 0) {
                parent.wrap(parent.memorySegment(), parent.segment(), indices[remaining]);
                consumer.accept(parent);

                final int count = parent.keyCount();
                if (count >= 1) {
                    indices[remaining] = parent.child(0);
                    ++remaining;
                }
                if (count >= 2) {
                    indices[remaining] = parent.child(1);
                    ++remaining;
                }
                if (count >= 3) {
                    indices[remaining] = parent.child(2);
                    ++remaining;
                }
            }
        }
    }

    @Override
    public String toString() {
        return "RadixTree{ size = " + size + ", bytes = " + pool.allocatedBytes() + " }";
    }

    private void addString(final int offset, final int length, final byte[] string, final NodeState context) {
        final int nodeLength = context.found.stringLength();
        final int remainingNode = nodeLength - context.mismatch;
        int remainingString = length - offset;
        int consumed = 0;
        final byte key = remainingString >= 1 ? string[offset] : context.key;
        switch (context.type) {
            case TYPE_COMMON_PREFIX:
                consumed = splitNode(remainingNode, string[offset], offset,
                    remainingString, context.found, context.mismatch);
                break;
            case TYPE_NO_COMMON_PREFIX:
                addParent(remainingNode, key, remainingString, context);
                consumed = 1;
                break;
            case TYPE_SUBSTRING:
                context.found.completeString(true);
                break;
            case TYPE_MISSING_KEY:
                consumed = addKey(remainingString,key, context.keyPosition, context.found);
                break;
            case TYPE_COMMON_PREFIX_AND_KEY:
                addChild(context.key, context.keyPosition, context.found);
                break;
            default:
                break;
        }
        if (remainingString - consumed >= 1) {
            addString(remainingString - consumed, string, offset + consumed, context.found);
        }
    }

    private void addParent(int remainingNode, final byte key, int remainingString, final NodeState context) {
        allocate(parent);
        if (context.found.equals(root)) {
            root.wrap(parent);
        }
        if (context.keyPosition >= 0) {
            context.parent.index(context.keyPosition, context.parent.key(context.keyPosition), parent.block());
        }

        final Node3 node = context.found;
        final int foundBlock = remainingNode == 1 && node.keyCount() == 0 ? 0 : node.block();
        final int childBlock = remainingString >= 2 ? allocate(child).block() : 0;
        if (remainingNode >= 1) {
            final byte foundKey = context.found.string(0);
            parent
                .stringLength(0).completeString(false)
                .keyCount(2)
                .index(0, foundKey, foundBlock).completeKey(0, remainingNode == 1)
                .index(1, key, childBlock).completeKey(1, remainingString == 1);
        }
        if (foundBlock == 0) {
            free(node);
            node.wrap(parent);
        } else {
            node.copyString(1, remainingNode - 1);
        }
        if (childBlock != 0) {
            node.wrap(child);
        }
    }

    private int splitNode(final int remainingNode,
                          final byte key,
                          final int keyPos,
                          final int remainingString,
                          final Node3 found,
                          final int mismatch) {
        int parentBlock = 0;
        final int count = found.keyCount();
        if (remainingNode != 1 || remainingString != 1 || count != 0) {
            parentBlock = allocate(parent).block();
            parent.copyNode(found);
        }
        if (remainingNode >= 2) {
            parent.copyString(mismatch + 1, remainingNode - 1);
        } else {
            if (count == 1) {
                parent
                    .stringLength(1).string(0, found.key(0)).completeString(true)
                    .keyCount(0).completeKey(0, false);
            } else {
                parent.stringLength(0);
            }
        }
        found
            .stringLength(mismatch).completeString(false)
            .keyCount(1)
            .index(0, found.string(mismatch), parentBlock)
            .completeKey(0, remainingString == 1);
        return addKey(remainingString, key, keyPos, found);
    }

    private void addChild(final byte key, final int keyPos, final Node3 found) {
        final int childBlock = allocate(child).block();
        child.keyCount(0).stringLength(0);
        found.index(keyPos, key, childBlock);
        found.wrap(child);
    }

    private int addKey(final int length, final byte key, final int keyPos, final Node3 found) {
        final int block = length >= 2 ? allocate(parent).block() : 0;
        final int count = found.keyCount();
        final int consumed;
        if (count < Node3.KEY_COUNT) {
            if (length == 0) {
                found.completeKey(keyPos, true);
                consumed = 0;
            } else {
                found.keyCount(count + 1).index(count, key, block).completeKey(count, length == 1);
                consumed = 1;
            }
        } else {
            final byte foundKey = found.key(2);
            final int foundChild = found.child(2);
            final boolean complete = found.completeKey(2);
            final int childBlock = allocate(child).block();
            found.index(2, Node.EMPTY_KEY, childBlock).completeKey(2, false);
            child
                .stringLength(0).completeString(false)
                .index(0, foundKey, foundChild).completeKey(0, complete)
                .keyCount(2).index(1, key, block).completeKey(1, true);
            consumed = 1;
            found.wrap(child);
        }
        if (block != 0) {
            state.found.wrap(parent);
        }
        return consumed;
    }

    private void addString(final int length, final byte[] string, final int offset, final Node3 found) {
        int remaining = length;
        int position = offset;
        while (remaining >= 1) {
            final int stringLength = Math.min(Node3.STRING_LENGTH, remaining);
            found
                .stringLength(stringLength).string(string, position, stringLength)
                .completeString(remaining == stringLength);
            position += stringLength;
            remaining -= stringLength;
            if (remaining == 1) {
                found
                    .keyCount(1).index(0, string[position], 0)
                    .completeKey(0, true);
            } else if (remaining >= 2) {
                final int childBlock = allocate(child).block();
                found.keyCount(1).index(0, string[position], childBlock);
                found.wrap(child).completeString(false);
            }
            --remaining;
            ++position;
        }
    }

    private Node3 allocate(final Node3 node) {
        ++allocatedBlocks;
        return pool.allocate(node);
    }

    private void free(final Node3 node) {
        --allocatedBlocks;
        pool.free(node);
    }

    private static final class NodeState {
        int type;
        int mismatch;
        int position;

        byte key;
        int keyPosition;

        Node3 found;
        Node3 parent;
        Node3 node;


        NodeState(Node3 found, Node3 parent, Node3 node)
        {
            this.found = found;
            this.parent = parent;
            this.node = node;
        }

        /**
         * Find the insertion point for a new string
         * @param string UTF-8 bytes
         * @param length string length
         * @param root the insertion point data
         * @return true when mismatch exists
         */
        private boolean mismatch(final Node root, final byte[] string, final int length) {
            mismatch = 0;
            position = 0;
            key = -1;
            keyPosition = -1;
            type = TYPE_NULL;
            found.wrap(root);
            parent.wrap(root);
            node.wrap(root);

            int remaining = length;
            boolean process = true;
            int nodeLength = found.stringLength();
            while (remaining >= 1) {
                if (process) {
                    if (nodeLength >= 1) {
                        mismatch = found.mismatch(string, position, length);
                        if (mismatch == -1) {
                            key = Node3.EMPTY_KEY;
                            return false;
                        }
                        remaining -= mismatch;
                        position += mismatch;
                    }
                    if (mismatch < nodeLength) {
                        type = nodeLength >=1 && mismatch == 0 ? TYPE_NO_COMMON_PREFIX : TYPE_COMMON_PREFIX;
                        return true;
                    }
                    process = false;
                } else {
                    final int count = found.keyCount();
                    type = TYPE_MISSING_KEY;
                    if (count == 0) {
                        return true;
                    }

                    final int foundPos = found.position(string[position]);
                    keyPosition = foundPos;
                    if (foundPos == Node3.NOT_FOUND) {
                        return true;
                    }

                    key = found.key(foundPos);
                    if (key != Node3.EMPTY_KEY) {  // empty keys do not advance the input string
                        --remaining;
                        ++position;
                        if (remaining == 0) {
                            return !found.completeKey(foundPos);
                        }
                        process = true;
                    }
                    parent.wrap(found);

                    final int childBlock = found.child(foundPos);
                    if (childBlock != 0) {
                        found.wrap(found.memorySegment(), found.segment(), childBlock);
                        if (count >= 2) {
                            node.wrap(found);
                        }
                        nodeLength = found.stringLength();
                    } else {
                        if (mismatch < position) {
                            type = TYPE_COMMON_PREFIX_AND_KEY;
                            return true;
                        }
                    }
                }
            }
            if (mismatch == nodeLength) {  // substring
                type = TYPE_SUBSTRING;
            }
            return true;
        }
    }
}
