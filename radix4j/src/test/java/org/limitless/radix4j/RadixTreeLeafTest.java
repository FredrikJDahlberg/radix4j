package org.limitless.radix4j;

import java.lang.foreign.Arena;

/**
 * Runs {@link RadixTreeTest} with buckets of one string: leaves holding the tail of a string longer than an
 * inline node string.
 */
public class RadixTreeLeafTest extends RadixTreeTest {

    @Override
    protected RadixTree newTree() {
        return new RadixTree(RadixTree.DEFAULT_BLOCKS_PER_SEGMENT, Arena.ofShared(), 1);
    }

    @Override
    protected boolean leaves() {
        return true;
    }
}
