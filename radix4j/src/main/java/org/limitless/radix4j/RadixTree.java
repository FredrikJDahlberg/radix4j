package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockFlyweight;
import org.limitless.fsmp4j.BlockPool;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.function.Consumer;

public class RadixTree {

    // FIXME: more than one segment per tree?
    // FIXME: add segment to key index

    private static final String BLOCKS_PER_SEGMENT_PROP = "radix4j.blocks.per.segment";
    private static final int BLOCKS_PER_SEGMENT = Integer.getInteger(BLOCKS_PER_SEGMENT_PROP, 128 * 1024);

    private final BlockPool<Node3> pool;
    private Node3 root;
    private final Node3 child;
    private final Node3 parent;
    private final NodeContext context;

    private final int[] indices = new int[256];
    private int size;

    private int allocated;

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

        addString(context.position, bytes.length, bytes, context);
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
            final Node3 found = context.found;
            final int count = found.keyCount();

            if (found.stringLength() == string.length() && found.completeString()) {
                found.completeString(false);
            }
            if (context.key != -1 && context.found.completeKey(context.keyPosition)) {
                found.completeKey(context.keyPosition, false);
                found.keyCount(count - 1);
            }
            // let mismatch track the previous node with complete string or more than one key remove all nodes below
            --size;
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

    public int blocks() {
        return allocated;
    }

    protected void forEach(final Consumer<Node3> consumer) {
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
        final int remainingNode = nodeLength - context.mismatch;
        int remainingString = length - offset;
        int consumed = 0;
        final byte key = remainingString >= 1 ? string[offset] : context.key;
        switch (context.type) {
            case TYPE_COMMON_PREFIX:
                consumed = splitString(remainingNode, string[offset], offset,
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
                //if (remainingString >= 1) {
                    addChild(context.key, context.keyPosition, context.found);
                    //consumed = 1;
                //}
                break;
            default:
                break;
        }
        if (remainingString - consumed >= 1) {
            addString(remainingString - consumed, string, offset + consumed, context.found);
        }
    }

    private void addParent(int remainingNode, final byte key, int remainingString, NodeContext meta) {
        //pool.allocate(parent);
        allocate(parent);
        if (meta.found.equals(root)) {
            root.wrap(parent);
        }
        if (meta.keyPosition >= 0) {
            meta.parent.index(meta.keyPosition, meta.parent.key(meta.keyPosition), parent.block());
        }

        //final int childBlock = remainingString >= 2 ? pool.allocate(child).block() : 0;
        final int childBlock = remainingString >= 2 ? allocate(child).block() : 0;
        final byte childKey = key;
        final Node3 node = meta.found;
        final int foundBlock = remainingNode == 1 && node.keyCount() == 0 ? 0 : node.block();
        if (remainingNode >= 1) {
            final byte foundKey = meta.found.stringByte(0);
            parent
                .stringLength(0).completeString(false)
                .keyCount(2)
                .index(0, foundKey, foundBlock).completeKey(0, remainingNode == 1)
                .index(1, childKey, childBlock).completeKey(1, remainingString == 1);
        }
        if (foundBlock == 0) {
            // pool.free(node);
            free(node);
            node.wrap(parent);
        } else {
            node.copyString(1, remainingNode - 1);
        }
        if (childBlock != 0) {
            node.wrap(child);
        }
    }

    private int splitString(final int remainingString,
                            final byte key,
                            final int keyPos,
                            final int remainingNode,
                            final Node3 found,
                            final int mismatch) {
        int parentBlock = 0;
        final int count = found.keyCount();
        if (remainingString != 1 || remainingNode != 1 || count != 0) {
            // parentBlock = pool.allocate(parent).block();
            parentBlock = allocate(parent).block();
            parent.copyNode(found);
        }
        if (remainingString >= 2) {
            parent.copyString(mismatch + 1, remainingString - 1);
        } else {
            if (count == 1) {
                parent
                    .stringLength(1).stringByte(0, found.key(0)).completeString(true)
                    .keyCount(0).completeKey(0, false);
            } else {
                parent.stringLength(0);
            }
        }
        found
            .stringLength(mismatch).completeString(false)
            .keyCount(1)
            .index(0, found.stringByte(mismatch), parentBlock)
            .completeKey(0, remainingNode == 1);
        return addKey(remainingNode, key, keyPos, found);
    }

    private void addChild(final byte key, final int keyPos, Node3 found) {
        // final int childBlock = pool.allocate(child).block();
        final int childBlock = allocate(child).block();
        child.keyCount(0).stringLength(0);
        found.index(keyPos, key, childBlock);
        found.wrap(child);
    }

    private int addKey(final int length, final byte key, final int keyPos, Node3 found) {
        // final int block = length >= 2 ? pool.allocate(parent).block() : 0;
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
            // final int childBlock = pool.allocate(child).block();
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
            context.found.wrap(parent);
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
            if (remaining == 1) {
                found
                    .keyCount(1).index(0, string[position], 0)
                    .completeKey(0, true);
            } else if (remaining >= 2) {
                // final int childBlock = pool.allocate(child).block();
                final int childBlock = allocate(child).block();
                found.keyCount(1).index(0, string[position], childBlock);
                found.wrap(child).completeString(false);
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
        context.type = TYPE_NULL;
        final Node3 found = context.found;
        int remaining = length;
        boolean process = true;
        int nodeLength = found.stringLength();
        while (remaining >= 1) {
            if (process) {
                if (nodeLength >= 1) {
                    context.mismatch = found.mismatch(string, context.position, length);
                    if (context.mismatch == -1) {
                        return false;
                    }
                    remaining -= context.mismatch;
                    context.position += context.mismatch;
                }
                if (context.mismatch < nodeLength) {
                    context.type = nodeLength >=1 && context.mismatch == 0 ? TYPE_NO_COMMON_PREFIX : TYPE_COMMON_PREFIX;
                    return true;
                }
                process = false;
            } else {
                final int count = found.keyCount();
                context.type = TYPE_MISSING_KEY;
                if (count == 0) {
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
                if (childBlock != 0) {
                    found.wrap(found.memorySegment(), found.segment(), childBlock);
                    nodeLength = found.stringLength();
                } else {
                    if (context.mismatch < context.position) {
                        context.type = TYPE_COMMON_PREFIX_AND_KEY;
                        return true;
                    }
                }
            }
        }
        if (context.mismatch == nodeLength) {  // substring
            context.type = TYPE_SUBSTRING;
        }
        return true;
    }

    private Node3 allocate(final Node3 node) {
        ++allocated;
        return pool.allocate(node);
    }

    private void free(final Node3 node) {
        --allocated;
        pool.allocate(node);
    }

    private static final class NodeContext {
        int type;

        Node3 found;
        Node3 parent;
        int mismatch;
        int position;

        byte key;
        int keyPosition;
    }
}
