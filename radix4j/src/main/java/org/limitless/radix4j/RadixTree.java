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
            addStringSuffix(true, bytes.length, bytes, 0, context.found);
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
        final int nodeLength = context.found.stringLength();
        final int stringLength = nodeLength - context.mismatch;
        if  (context.mismatch == 0) { // no common prefix - insert new parent
            pool.allocate(parent);
            if (context.found.equals(root)) {
                root.wrap(parent);
            }
            if (context.keyPosition >= 0) {
                context.parent.index(context.keyPosition, context.parent.key(context.keyPosition), parent.block());
            }

            final int childBlock = processString ? pool.allocate(child).block() : 0;
            final int foundBlock = stringLength == 1 && context.found.keyCount() == 0 ? 0 : context.found.block();
            final byte foundKey = context.found.getStringByte(0);
            final byte childKey = length >= 1 ? string[position] : 0;
            if (stringLength == 0) {
                parent
                    .stringLength(remaining)
                    .completeString(true)
                    .string(string, offset, length);
            } else {
                parent
                    .stringLength(0)
                    .completeString(false)
                    .keyCount(2)
                    .index(0, foundKey, foundBlock)
                    .index(1, childKey, childBlock)
                    .completeKey(0, stringLength == 1)
                    .completeKey(1, remaining == 1);
            }
            if (foundBlock == 0) {
                pool.free(context.found);
                context.found.wrap(parent);
            } else {
                context.found
                    .copyString(1, stringLength - 1);
            }
            if (processString) {
                context.found.wrap(child);
            }
            --remaining;
            ++position;
        } else {
            if (context.mismatch == nodeLength && remaining == 0) {  // substring
                context.found.completeString(true);
            } else if (context.mismatch < nodeLength) {   // split string
                final int childBlock = processString ? pool.allocate(child).block() : 0;
                final int nodeBlock = stringLength >= 2 ? pool.allocate(parent).block() : 0;
                if (nodeBlock != 0) {
                    parent
                        .copyNode(context.found)
                        .copyString(context.mismatch + 1, stringLength - 1);
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
                    pool.allocate(child);
                    context.found
                        .index(2, Node.EMPTY_KEY, child.block())
                        .completeKey(2, false);
                    if (length >= 1) {
                        child
                            .stringLength(0)
                            .completeString(false)
                            .keyCount(2)
                            .index(0, key, index)
                            .index(1, string[position], 0)
                            .completeKey(0, complete)
                            .completeKey(1, true);
                    } else {
                        child
                            .stringLength(0)
                            .completeString(false)
                            .keyCount(1)
                            .index(0, key, index)
                            .completeKey(0, complete);
                    }
                    context.found.wrap(child);
                }
                --remaining;
                ++position;
            }
        }
        if (remaining >= 1) {
            addStringSuffix(processString, remaining, string, position, context.found);
        }
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
        context.keyPosition = -1;
        final Node3 found = context.found;
        int remaining = length;
        while (remaining >= 1) {
            final int nodeLength = found.stringLength();
            if (nodeLength >= 1) {
                context.mismatch = found.mismatch(string, context.position, length);
                if (context.mismatch == -1) {
                    return false;
                }
                remaining -= context.mismatch;
                context.position += context.mismatch;
            }
            if (remaining >= 1 && found.keyCount() >= 1) {
                context.keyPosition = found.position(string[context.position]);
                if (context.keyPosition == Node3.NOT_FOUND) {
                    return true;
                }
                final int foundPos = context.keyPosition;
                if (found.key(foundPos) != Node3.EMPTY_KEY) {
                    if (remaining == 1) {
                        return !context.found.completeKey(foundPos);
                    } else {
                        ++context.position;
                        context.parent.wrap(found);
                        final int childBlock = found.child(foundPos);
                        found.wrap(found.memorySegment(), found.segment(), childBlock);
                    }
                }
            }
            --remaining;
        }
        return true;
    }

    private static final class NodeContext {
        Node3 found;
        Node3 parent;
        int mismatch;
        int position;
        int keyPosition;
    }
}
