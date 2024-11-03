package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockPool;

import java.lang.foreign.Arena;
import java.util.function.Consumer;

public class RadixTree {

    private static final String BLOCKS_PER_SEGMENT_PROP = "radix4j.blocks.per.segment";

    private static final int BLOCKS_PER_SEGMENT = Integer.getInteger(BLOCKS_PER_SEGMENT_PROP, 256 * 1024);

    private final BlockPool<Node3> pool;
    private Node3 root;
    private final Node3 child;
    private final Node3 node;
    private final NodeContext context;

    private final int[] indices = new int[16];
    private int size;

    public RadixTree() {
        pool = new BlockPool.Builder<>(Arena.ofShared(), Node3.class).blocksPerSegment(256).build();
        node = pool.allocate();
        child = pool.allocate();
        context = new NodeContext();
        context.found = pool.allocate();
    }

    public boolean add(String string) {
        if (root == null) {
            root = pool.allocate().completeString(false);
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

    protected void forEach(Consumer<Node3> consumer) {
        if (root == null) {
            return;
        }
        node.wrap(root);

        indices[0] = node.block();
        int remaining = 1;
        while (remaining >= 1) {
            if (indices[--remaining] != 0) {
                node.wrap(node.memorySegment(), node.segment(), indices[remaining]);
                consumer.accept(node);

                final int count = node.keyCount();
                if (count >= 1) {
                    indices[remaining] = node.child(0);
                    ++remaining;
                }
                if (count >= 2) {
                    indices[remaining] = node.child(1);
                    ++remaining;
                }
                if (count >= 3) {
                    indices[remaining] = node.child(2);
                    ++remaining;
                }
            }
        }
    }

    @Override
    public String toString() {
        return "RadixTree{ size = " + size + ", bytes = " + pool.allocatedBytes() + " }";
    }

    private void addNode(final int offset, final int length, final byte[] string, NodeContext context) {
        int remaining = length;
        int position = offset;
        boolean processString = true;
        if (context.mismatch >= 1) {
            processString = remaining >= 2;
            if (context.mismatch < Node3.STRING_LENGTH) {
                final int stringLength = context.found.stringLength() - context.mismatch;
                final int block = processString ? pool.allocate(child).block() : 0;
                context.found
                    .keyCount(2)
                    .completeString(false)
                    .stringLength(context.mismatch)
                    .index(0,  context.found.getStringByte(context.mismatch), context.found.child(0))
                    .index(1, string[position], block)
                    .completeKey(0, stringLength == 1)
                    .completeKey(1, remaining == 1);

                if (processString) {
                    context.found.wrap(child);
                }
                remaining -= stringLength;
                position += stringLength;
            } else {
                final int count = context.found.keyCount();
                if (count < Node3.KEY_COUNT) {
                    final int block = processString ? pool.allocate(child).block() : 0;
                    context.found
                        .keyCount(count + 1)
                        .index(count, string[position], block)
                        .completeKey(count, remaining == 1);
                    if (processString) {
                        context.found.wrap(child);
                    }
                } else {
                    final byte key = context.found.key(2);
                    final int index = context.found.child(2);
                    final boolean complete = context.found.completeKey(2);
                    pool.allocate(child).completeString(false);
                    context.found
                        .index(2, (byte) Node.EMPTY_KEY, child.block())
                        .completeKey(2, false);
                    child
                        .completeString(false)
                        .keyCount(2)
                        .index(0, key, index).completeKey(0, complete)
                        .index(1, string[position], 0).completeKey(1, true);
                    context.found.wrap(child);
                }
                --remaining;
                ++position;
            }
        }
        addStringSuffix(processString, remaining, string, position, context.found);
    }

    private void addStringSuffix(boolean processString, int remaining, final byte[] string, int position, Node3 found) {
        while (remaining >= 1) {
            if (processString) {
                final int stringLength = Math.min(Node3.STRING_LENGTH, remaining);
                found
                    .stringLength(stringLength)
                    .string(string, position, stringLength)
                    .completeString(remaining == stringLength);
                position += stringLength;
                remaining -= stringLength;
                processString = false;
            } else {
                if (remaining >= 2) {
                    final int block = pool.allocate(child).block();
                    found.setIndex(string[position], block);
                    found.wrap(child).completeString(false);
                } else {
                    found
                        .setIndex(string[position], 0)
                        .completeKey(0, true);
                }
                --remaining;
                ++position;
                processString = true;
            }
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
            context.found.wrap(context.found.memorySegment(), context.found.segment(), context.found.child(found));
        }
        return true;
    }

    private static final class NodeContext {
        Node3 found;
        int mismatch;
        int index;
    }
}