package org.limitless.radix4j;

import org.limitless.fsmp4j.BlockPool;

import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class RadixTree {

    private static final String BLOCKS_PER_SEGMENT_PROP = "radix4j.blocks.per.segment";

    private static final int BLOCKS_PER_SEGMENT = Integer.getInteger(BLOCKS_PER_SEGMENT_PROP, 256 * 1024);

    private final BlockPool<InnerNode> pool;

    private Node root;

    public RadixTree() {
        pool = new BlockPool.Builder<>(Arena.ofShared(), InnerNode.class).blocksPerSegment(256).build();
    }

    public RadixTree add(String value) {
        if (root == null) {
            root = pool.allocate();
        }

        byte[] string = value.getBytes();

        // find the insertion point

        return this;
    }

    public RadixTree contains(String value) {
        return this;
    }

    public RadixTree remove(String value) {
        return this;
    }

    public boolean isEmpty() {
        return true;
    }

    public int size() {
        return 0;
    }

    public void clear() {
        pool.close();
    }

    public void forEach(Consumer<? extends Node> consumer) {
    }

    private int mismatch(final int offset, final int length, final byte[] string, final Node node) {

        return node.mismatch(offset, length, string);

    }
}