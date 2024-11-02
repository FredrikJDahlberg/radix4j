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

        assertTrue(tree.add("ABCD"));
        assertFalse(tree.add("ABCD"));
        assertTrue(tree.contains("ABCD"));
        assertEquals(2, tree.size());

        assertTrue(tree.add("ABCE"));
        assertFalse(tree.add("ABCE"));
        assertTrue(tree.contains("ABCE"));
        assertEquals(3, tree.size());

        assertTrue(tree.add("ABCF"));
        assertFalse(tree.add("ABCF"));
        assertTrue(tree.contains("ABCF"));
        assertEquals(4, tree.size());

        assertTrue(tree.add("ABCG"));
        assertFalse(tree.add("ABCG"));
        assertTrue(tree.contains("ABCG"));
        assertEquals(5, tree.size());
        tree.forEach(System.out::println);
        System.out.println(tree);
    }

    @Test
    public void string24() {
        RadixTree tree = new RadixTree();
        assertTrue(tree.add("123456789012345678901234"));
        tree.forEach(System.out::println);
        System.out.println(tree);
    }

    @Test
    public void string25() {
        RadixTree tree = new RadixTree();
        assertTrue(tree.add("1234567890123456789012345"));
        tree.forEach(System.out::println);
        System.out.println(tree);
    }

    @Test
    public void multipleString25() {
        RadixTree tree = new RadixTree();
        assertTrue(tree.add("123456789_123456789_1234_5"));
        tree.forEach(System.out::println);

        assertTrue(tree.add("123456789_123456789_1234_6"));
        tree.forEach(System.out::println);
        System.out.println(tree);
    }
}
