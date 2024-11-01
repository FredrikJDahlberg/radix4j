package org.limitless.radix4j;

import org.junit.jupiter.api.Test;
import org.limitless.fsmp4j.BlockPool;

import java.lang.foreign.Arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.limitless.radix4j.Node.INCLUDED;

public class InnerNodeTest {

    BlockPool<InnerNode> pool = new BlockPool.Builder<>(Arena.ofShared(), InnerNode.class).blocksPerSegment(256).build();

    @Test
    public void mismatch() {
        var node = pool.allocate();

        node.string("ABC".getBytes(), 0, 3);
        assertEquals(0, node.mismatch("_BC".getBytes(), 0, 3));
        assertEquals(1, node.mismatch("A_C".getBytes(), 0, 3));
        assertEquals(2, node.mismatch("AB_".getBytes(), 0, 3));
        assertEquals(3, node.mismatch("ABC".getBytes(), 0, 3));

        node.completeString(INCLUDED);
        assertEquals(-1, node.mismatch("ABC".getBytes(), 0, 3));

        node.indexCount(1).index(0, (byte) 'D', 1);
        assertEquals(0, node.mismatch("_BCD".getBytes(), 0, 4));
        assertEquals(1, node.mismatch("A_CD".getBytes(), 0, 4));
        assertEquals(2, node.mismatch("AB_D".getBytes(), 0, 4));
        assertEquals(3, node.mismatch("ABC_".getBytes(), 0, 4));
        assertEquals(4, node.mismatch("ABCD_".getBytes(), 0, 5));

        node.completeKey(0, INCLUDED);
        assertEquals(-1, node.mismatch("ABCD".getBytes(), 0, 4));
    }
}
