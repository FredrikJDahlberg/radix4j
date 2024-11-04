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
    private final Node3 parent;
    private final NodeContext context;

    private final int[] indices = new int[16];
    private int size;

    public RadixTree() {
        pool = new BlockPool.Builder<>(Arena.ofShared(), Node3.class).blocksPerSegment(256).build();
        parent = pool.allocate();
        child = pool.allocate();
        context = new NodeContext();
        context.found = pool.allocate();
    }

    public boolean add(String string) {
        final byte[] bytes = string.getBytes();
        if (root == null) {
            root = pool.allocate().completeString(false);
            context.found.wrap(root);
            addStringSuffix(true, bytes.length, bytes, 0, context.found);
            ++size;
            return true;
        }

        context.found.wrap(root);
        if (!mismatch(bytes, bytes.length, context)) {
            return false;
        }
        ++size;

        addString(context.position, bytes.length - context.position, bytes, context);
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
        parent.wrap(root);

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

    private void addString(final int offset, final int length, final byte[] string, NodeContext context) {
        int remaining = length;
        int position = offset;
        final boolean processString = remaining >= 2;
        final int stringLength = context.found.stringLength() - context.mismatch;
        final int nodeLength = context.found.stringLength();
        final int count = context.found.keyCount();
        if  (context.mismatch == 0) { // no common prefix - insert new parent
            pool.allocate(parent).block();
            if (context.found.equals(root)) {
                root.wrap(parent);
            }

            final int childBlock = processString ? pool.allocate(child).block() : 0;
            parent
                .completeString(false)
                .keyCount(2)
                .index(0, context.found.getStringByte(0), context.found.block())
                .index(1, string[position], childBlock)
                .completeKey(0, stringLength == 1)  // ??
                .completeKey(1, remaining == 1);  // ??

            context.found.shiftStringLeft(1, stringLength - 1);
            if (processString) {
                context.found.wrap(child);
            }
            --remaining;
            ++position;
        } else {
            if (context.mismatch < nodeLength) {   // split string
                final int childBlock = processString ? pool.allocate(child).block() : 0;
                final int nodeBlock = stringLength >= 2 ? pool.allocate(parent).block() : 0;
                if (nodeBlock != 0) {
                    parent
                        .copy(context.found)
                        .string(context.found, context.mismatch + 1, stringLength - 1);
                }
                context.found
                    .keyCount(2)
                    .completeString(false)
                    .stringLength(context.mismatch)
                    .index(0,  context.found.getStringByte(context.mismatch), nodeBlock)
                    .index(1, string[position], childBlock)
                    .completeKey(0, stringLength == 1)
                    .completeKey(1, remaining == 1);
                if (processString) {
                    context.found.wrap(child);
                }
                --remaining;
                ++position;
            } else {  // add key
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
        context.mismatch = 0;
        context.position = 0;
        boolean checkString = true;
        int remaining = length;
        while (remaining >= 1) {
            if (checkString) {
                int nodeLength = context.found.stringLength();
                if (nodeLength >= 1) {
                    context.mismatch = context.found.mismatch(string, context.position, length);
                    if (context.mismatch == 0) {
                        return true;
                    }
                    if (context.mismatch == -1) {
                        return false;
                    }
                    remaining -= context.mismatch;
                    context.position += context.mismatch;
                }
            } else {
                if (context.found.keyCount() == 0) {
                    return true;
                }
                final int found = context.found.position(string[context.position]);  // mismatch
                if (found == Node3.NOT_FOUND) {
                    return true;
                }
                if (context.found.key(found) != Node3.EMPTY_KEY) {
                    if (remaining == 1 && context.found.completeKey(found)) {
                        return false;
                    }
                    --remaining;
                    ++context.position;
                }
                context.found.wrap(context.found.memorySegment(), context.found.segment(), context.found.child(found));
            }
            checkString = !checkString;
        }
        return true;
    }

    private static final class NodeContext {
        Node3 found;
        int mismatch;
        int position;
    }
}
