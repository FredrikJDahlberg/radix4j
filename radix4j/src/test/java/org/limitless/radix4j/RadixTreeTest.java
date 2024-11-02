package org.limitless.radix4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RadixTreeTest {

    @Test
    public void basics() {
        RadixTree tree = new RadixTree();

        assertTrue(tree.add("ABC"));
        assertFalse(tree.add("ABC"));
        assertTrue(tree.contains("ABC"));
        assertEquals(1, tree.size());
        tree.forEach(System.out::println);

        assertTrue(tree.add("ABCD"));
        assertFalse(tree.add("ABCD"));
        assertTrue(tree.contains("ABCD"));
        assertEquals(2, tree.size());
        tree.forEach(System.out::println);

        assertTrue(tree.add("ABCE"));
        assertFalse(tree.add("ABCE"));
        assertTrue(tree.contains("ABCE"));
        assertEquals(3, tree.size());
        tree.forEach(System.out::println);

        assertTrue(tree.add("ABCF"));
        assertFalse(tree.add("ABCF"));
        assertTrue(tree.contains("ABCF"));
        assertEquals(4, tree.size());
        tree.forEach(System.out::println);

        assertTrue(tree.add("ABCG"));
        assertFalse(tree.add("ABCG"));
        assertTrue(tree.contains("ABCG"));
        assertEquals(5, tree.size());
        tree.forEach(System.out::println);
    }
}
