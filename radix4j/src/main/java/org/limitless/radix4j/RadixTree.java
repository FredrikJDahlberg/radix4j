package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockPool;

import java.lang.foreign.Arena;
import java.util.function.Consumer;

public class RadixTree {

    // FIXME: more than one segment per tree?

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
        context.parent = pool.allocate();
    }

    public boolean add(String string) {
        final byte[] bytes = string.getBytes();
        if (root == null) {
            root = pool.allocate().completeString(false);
            context.found.wrap(root);
            addString(bytes.length, bytes, 0, context.found);
            ++size;
            return true;
        }

        context.found.wrap(root);
        context.parent.wrap(root);
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
        context.parent.wrap(root);

        final byte[] bytes = string.getBytes();
        return !mismatch(bytes, bytes.length, context);
    }

    public RadixTree remove(String string) {
        if (contains(string)) {
            // do stuff
        }
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
        final int nodeLength = context.found.stringLength();
        final int stringLength = nodeLength - context.mismatch;
        final int consumed;
        if  (context.mismatch == 0) { // no common prefix - insert new parent
            addParent(stringLength, string, offset, length);
            consumed = 1;
        } else {
            if (context.mismatch == nodeLength && length == 0) {  // substring
                context.found.completeString(true);
                consumed = 0;
            } else if (context.mismatch < nodeLength) {
                consumed = splitString(stringLength, string[offset], length);
            } else {  // add key
                final int newBlock = length >= 2 ? pool.allocate(parent).block() : 0;
                consumed = addKey(length, string[offset], newBlock, context.found);
                if (newBlock != 0) {
                    context.found.wrap(parent);
                }
            }
        }
        final int remaining = length - consumed;
        if (remaining >= 1) {
            addString(remaining, string, offset + consumed, context.found);
        }
    }

    private void addParent(int stringLength, final byte[] string, int position, int length) {
        pool.allocate(parent);
        if (context.found.equals(root)) {
            root.wrap(parent);
        }
        if (context.keyPosition >= 0) {
            context.parent.index(context.keyPosition, context.parent.key(context.keyPosition), parent.block());
        }

        final int childBlock = length >= 2 ? pool.allocate(child).block() : 0;
        final byte childKey = length >= 1 ? string[position] : 0;
        final int foundBlock = stringLength == 1 && context.found.keyCount() == 0 ? 0 : context.found.block();
        final byte foundKey = context.found.stringByte(0);
        if (stringLength == 0) {
            parent
                .stringLength(length).completeString(true).string(string, position, length);
        } else {
            parent
                .stringLength(0).completeString(false)
                .keyCount(2)
                .index(0, foundKey, foundBlock).completeKey(0, stringLength == 1)
                .index(1, childKey, childBlock).completeKey(1, length == 1);
        }
        if (foundBlock == 0) {
            pool.free(context.found);
            context.found.wrap(parent);
        } else {
            context.found.copyString(1, stringLength - 1);
        }
        if (childBlock != 0) {
            context.found.wrap(child);
        }
    }

    private int splitString(final int stringLength, final byte key, final int length) {
        int consumed = 0;
        int parentBlock = 0;
        if (stringLength >= 2 || stringLength == 1 && context.found.keyCount() >= 1) {
            parentBlock = pool.allocate(parent).block();
            if (stringLength >= 2) {
                parent
                    .copyNode(context.found)
                    .copyString(context.mismatch + 1, stringLength - 1);
            } else {
                final byte oldKey = context.found.key(0);
                parent
                    .stringLength(1).stringByte(0, oldKey).completeString(true)
                    .completeKey(0, false);
            }
        }
        context.found
            .stringLength(context.mismatch).completeString(false)
            .keyCount(1)
            .index(0, context.found.stringByte(context.mismatch), parentBlock)
            .completeKey(0, length == 1);  // stringLength
        final int childBlock = length >= 2 ? pool.allocate(child).block() : 0;
        consumed = addKey(length, key, childBlock, context.found);
        if (childBlock != 0) {
            context.found.wrap(child);
        }
        return consumed;
    }

    private int addKey(final int length, final byte key, final int block, Node3 found) {
        final int count = found.keyCount();
        int consumed = 0;
        if (count < Node3.KEY_COUNT) {
            found
                .keyCount(count + 1).index(count, key, block).completeKey(count, length == 1);
            consumed = 1;
        } else {
            final byte foundKey = found.key(2);
            final int foundChild = found.child(2);
            final boolean complete = found.completeKey(2);
            pool.allocate(child);
            found
                .index(2, Node.EMPTY_KEY, child.block()).completeKey(2, false);
            child
                .stringLength(0).completeString(false)
                .index(0, foundKey, foundChild).completeKey(0, complete)
                .keyCount(2).index(1, key, block).completeKey(1, true);
                consumed = 1;
            found.wrap(child);
        }
        return consumed;
    }

    private void addString(final int length, final byte[] string, int position, Node3 found) {
        int remaining = length;
        while (remaining >= 1) {
            final int stringLength = Math.min(Node3.STRING_LENGTH, remaining);
            found
                .stringLength(stringLength).string(string, position, stringLength)
                .completeString(remaining == stringLength);
            position += stringLength;
            remaining -= stringLength;
            if (remaining >= 2) {
                final int childBlock = pool.allocate(child).block();
                found.keyCount(1).index(0, string[position], childBlock);
                found.wrap(child).completeString(false);
            } else {
                if (remaining == 1) {
                    found
                        .keyCount(1).index(0, string[position], 0)
                        .completeKey(0, true);
                }
            }
            --remaining;
            ++position;
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
        context.key = -1;
        context.keyPosition = -1;
        final Node3 found = context.found;
        int remaining = length;
        boolean process = true;
        while (remaining >= 1) {
            if (process) {
                final int nodeLength = found.stringLength();
                if (nodeLength >= 1) {
                    context.mismatch = found.mismatch(string, context.position, length);
                    if (context.mismatch == -1) {
                        return false;
                    }
                    remaining -= context.mismatch;
                    context.position += context.mismatch;
                }
                process = false;
            } else {
                if (found.keyCount() == 0) {
                    return true;
                }

                final byte key = string[context.position];
                final int foundPos = found.position(key);
                context.keyPosition = foundPos;
                if (foundPos == Node3.NOT_FOUND) {
                    return true;
                }

                context.key = found.key(foundPos);
                if (context.key != Node3.EMPTY_KEY) {
                    --remaining;
                    ++context.position;
                    if (remaining == 0) {
                        return !context.found.completeKey(foundPos);
                    }
                    process = true;
                }
                context.parent.wrap(found);
                final int childBlock = found.child(foundPos);
                found.wrap(found.memorySegment(), found.segment(), childBlock);
            }
        }
        return true;
    }

    private static final class NodeContext {
        Node3 found;
        Node3 parent;
        int mismatch;
        int position;

        byte key;
        int keyPosition;
    }
}
