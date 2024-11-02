package org.limitless.radix4j;

import org.junit.jupiter.api.Test;
import org.limitless.fsmp4j.BlockPool;

import java.lang.foreign.Arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.limitless.radix4j.Node.INCLUDED;

public class Node3Test {

    BlockPool<Node3> pool = new BlockPool.Builder<>(Arena.ofShared(), Node3.class).blocksPerSegment(256).build();

    @Test
    public void basics() {
        var node = pool.allocate().string("ABC".getBytes(), 0, 3);
        var child = pool.allocate().string("EFG".getBytes(), 0, 3).completeString(true);
        node.indexCount(1).index(0, (byte) 'D', child.index());

        node.indexCount(3);
        node.completeKey(0, true);
        assertTrue(node.completeKey(0));

        node.completeKey(1, true);
        assertTrue(node.completeKey(1));

        node.completeKey(2, true);
        assertTrue(node.completeKey(2));
        System.out.println(node);

        var work = new Node3().wrap(node);
        System.out.println(work);
        work.wrap(node.memorySegment(), node.segment(), node.block(0));
        System.out.println(work);


    }

    @Test
    public void mismatch() {
        var node = pool.allocate().string("ABC".getBytes(), 0, 3);
        assertEquals(0, node.mismatch("_BC".getBytes(), 0, 3));
        assertEquals(1, node.mismatch("A_C".getBytes(), 0, 3));
        assertEquals(2, node.mismatch("AB_".getBytes(), 0, 3));
        assertEquals(3, node.mismatch("ABC".getBytes(), 0, 3));

        node.completeString(true);
        assertEquals(-1, node.mismatch("ABC".getBytes(), 0, 3));

        node.indexCount(1).index(0, (byte) 'D', 1);
        assertEquals(0, node.mismatch("_BCD".getBytes(), 0, 4));
        assertEquals(1, node.mismatch("A_CD".getBytes(), 0, 4));
        assertEquals(2, node.mismatch("AB_D".getBytes(), 0, 4));
        assertEquals(3, node.mismatch("ABC_".getBytes(), 0, 4));
        assertEquals(3, node.mismatch("ABCD_".getBytes(), 0, 5));

        node.completeKey(0, true);
        assertEquals(3, node.mismatch("ABCD".getBytes(), 0, 4));
    }
}
