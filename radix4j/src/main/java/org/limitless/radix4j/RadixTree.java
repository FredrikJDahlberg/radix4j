package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockPool;

import java.lang.foreign.Arena;
import java.util.function.Consumer;

public class RadixTree {

    private static final String BLOCKS_PER_SEGMENT_PROP = "radix4j.blocks.per.segment";

    private static final int BLOCKS_PER_SEGMENT = Integer.getInteger(BLOCKS_PER_SEGMENT_PROP, 256 * 1024);

    private final BlockPool<Node3> pool;
    private final Node3 node;
    private final NodeContext context;

    private final int[] indices = new int[16];

    private Node3 root;
    private final Node3 child;

    private int size;
    private int allocated;

    public RadixTree() {
        pool = new BlockPool.Builder<>(Arena.ofShared(), Node3.class).blocksPerSegment(256).build();
        child = pool.allocate();
        node = pool.allocate();
        context = new NodeContext();
        context.found = pool.allocate();
        allocated += 3;
    }

    public boolean add(String string) {
        if (root == null) {
            root = pool.allocate().completeString(false);
            ++allocated;
        }
        context.found.wrap(root);

        final byte[] bytes = string.getBytes();
        if (!mismatch(bytes, bytes.length, context)) {
            return false;
        }
        ++size;

        addNode(context.index, bytes.length - context.index, bytes, context);
        return true;
    }

    public boolean contains(String string) {
        if (isEmpty()) {
            return false;
        }
        context.found.wrap(root);

        final byte[] bytes = string.getBytes();
        return !mismatch(bytes, bytes.length, context);
    }

    public RadixTree remove(String value) {
        return this;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    public void clear() {
        size = 0;
        pool.close();
    }

    public void forEach(Consumer<Node3> consumer) {
        node.wrap(root);

        indices[0] = node.block();
        int remaining = 1;
        while (remaining >= 1) {
            if (indices[--remaining] != 0) {
                node.wrap(node.memorySegment(), node.segment(), indices[remaining]);
                consumer.accept(node);

                int count = node.indexCount();
                if (count >= 1) {
                    indices[remaining] = node.block(0);
                    ++remaining;
                }
                if (count >= 2) {
                    indices[remaining] = node.block(1);
                    ++remaining;
                }
                if (count >= 3) {
                    indices[remaining] = node.block(2);
                    ++remaining;
                }
            }
        }
    }

    @Override
    public String toString() {
        return "RadixTree{ size = " + size + ", blocks = " + allocated + " }";
    }

    private void addNode(final int offset, final int length, final byte[] string, NodeContext context) {
        int remaining = length;
        int position = offset;

        if (context.mismatch >= 1) {
            // FIXME: split string
            int count = context.found.indexCount();
            if (count < Node3.KEY_COUNT) {
                context.found
                    .indexCount(count + 1)
                    .index(count, string[position], 0)
                    .completeKey(count, true);
            } else {
                pool.allocate(child).completeString(false);
                ++allocated;

                final byte key = context.found.key(2);
                final int index = context.found.block(2);
                final boolean complete = context.found.completeKey(2);
                context.found
                    .index(2, (byte) Node.EMPTY_KEY, child.block())
                    .completeKey(2, false);
                child
                    .completeString(false)
                    .indexCount(2)
                    .index(0, key, index).completeKey(0, complete)
                    .index(1, string[position], 0).completeKey(1, true);
                context.found.wrap(child);
            }
            --remaining;
            ++position;
        }
        while (remaining >= Node3.STRING_LENGTH + 1) {
            context.found
                .stringLength(Node3.STRING_LENGTH)
                .string(string, position, Node3.STRING_LENGTH);
            remaining -= Node3.STRING_LENGTH;
            position += Node3.STRING_LENGTH;

            int block = 0;
            if (remaining >= 2) {
                block = pool.allocate(child).block();
                ++allocated;
            }
            context.found.setIndex(string[position], block);
            if (remaining >= 2) {
                context.found.wrap(child).completeString(false);
            }
            --remaining;
            ++position;
        }
        if (remaining == Node3.STRING_LENGTH) {
            context.found.completeString(string, position, remaining);
            remaining -= Node3.STRING_LENGTH;
            position -= Node3.STRING_LENGTH;
        }
        if (remaining == 2) {
            context.found.setIndex(string[position], 0).completeKey(0, true);
            --remaining;
            ++position;
        }
        if (remaining == 1) {
            context.found.completeString(string, position, remaining);
        }
    }

    /**
     * Find the insertion point for a new string
     * @param string UTF-8 bytes
     * @param length string length
     * @param context the insertion point data
     * @return true when mismatch exists
     */
    private static boolean mismatch(final byte[] string, final int length, final NodeContext context) {
        int remaining = length;
        context.mismatch = 0;
        context.index = 0;
        while (remaining >= 1) {
            int stringLength = context.found.stringLength();
            if (stringLength >= 1) {
                context.mismatch = context.found.mismatch(string, context.index, remaining);
                if (context.mismatch != -1 && context.mismatch != Node3.STRING_LENGTH) {
                    context.index += context.mismatch;
                    return true;
                }
                remaining -= stringLength;
                context.index += stringLength;
                if (remaining == 0) {
                    return !context.found.completeString();
                }
            }

            final int found = context.found.position(string[context.index]);  // mismatch
            if (found == Node3.NOT_FOUND) {
                return true;
            }
            if (context.found.key(found) != Node3.EMPTY_KEY) {
                if (remaining == 1 && context.found.completeKey(found)) {
                    return false;
                }
                ++context.index;
                --remaining;
            }
            context.found.wrap(context.found.memorySegment(), context.found.segment(), context.found.block(found));
        }
        return true;
    }

    private static final class NodeContext {
        Node3 found;
        int mismatch;
        int index;
    }
}