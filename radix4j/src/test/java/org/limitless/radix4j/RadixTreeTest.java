package org.limitless.radix4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RadixTreeTest {

    @Test
    public void fourStringsWithSamePrefix() {
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
        new Checker().check(tree,
            node -> {
                assertEquals("ABC", getString(node));
                assertEquals('D', node.key(0));
                assertTrue(node.completeKey(0));
                assertEquals('E', node.key(1));
                assertTrue(node.completeKey(1));
                assertEquals(0, node.key(2));
                assertFalse(node.completeKey(2));
            },
            node -> {
                assertEquals(0, node.stringLength());
                assertEquals('F', node.key(0));
                assertTrue(node.completeKey(0));
                assertEquals('G', node.key(1));
                assertTrue(node.completeKey(1));
            });
        tree.forEach(System.out::println);
    }

    @Test
    public void twoStringsLength3Split() {
        RadixTree tree = new RadixTree();
        assertTrue(tree.add("ABC"));
        assertTrue(tree.add("ABD"));
        tree.forEach(System.out::println);
        new Checker().check(tree,
            node -> {
                assertEquals("AB", getString(node), "string");
                assertEquals((byte) 'C', node.key(0), "key 0");
                assertTrue(node.completeKey(0), "key complete 0");
                assertEquals((byte) 'D', node.key(1), "key 1");
                assertTrue(node.completeKey(1), "key complete 1");
                assertEquals(2, node.keyCount(), "key count");
            }
        );
    }

    @Test
    public void twoStringsLength4Split() {
        RadixTree tree = new RadixTree();
        assertTrue(tree.add("ABCD"));
        assertTrue(tree.add("ABCE"));
        tree.forEach(System.out::println);
        new Checker().check(tree,
            node -> {
                assertEquals("ABC", getString(node), "string");
                assertEquals((byte) 'D', node.key(0), "key 0");
                assertTrue(node.completeKey(0), "key complete 0");
                assertEquals((byte) 'E', node.key(1), "key 1");
                assertTrue(node.completeKey(1), "key complete 1");
                assertEquals(2, node.keyCount(), "key count");
            }
        );
    }

    @Test
    public void twoStringsLength5Split() {
        RadixTree tree = new RadixTree();
        assertTrue(tree.add("ABCDF"));
        assertTrue(tree.add("ABCEG"));
        tree.forEach(System.out::println);
        new Checker().check(tree,
            node -> {
                assertEquals("ABC", getString(node), "string");
                assertEquals((byte) 'D', node.key(0), "key 0");
                assertFalse(node.completeKey(0), "key complete 0");
                assertEquals((byte) 'E', node.key(1), "key 1");
                assertFalse(node.completeKey(1), "key complete 1");
                assertEquals(2, node.keyCount(), "key count");
            },
            node -> {
                assertEquals("G", getString(node), "string");
                assertTrue(node.completeString());
            },
            node -> {
                assertEquals("F", getString(node), "string");
                assertTrue(node.completeString());
            }
        );
    }

    @Test
    public void oneStringLength12() {
        RadixTree tree = new RadixTree();
        assertTrue(tree.add("123456789012"));
        tree.forEach(System.out::println);
        new Checker().check(tree,
            node -> {
                assertEquals("123", getString(node));
                assertEquals('4', node.key(0));
                assertFalse(node.completeString());
            },
            node -> {
                assertEquals("567", getString(node));
                assertEquals('8', node.key(0));
            },
            node -> {
                assertEquals("901", getString(node));
                assertEquals('2', node.key(0));
            }
        );
    }

    @Test
    public void oneStringLength13() {
        RadixTree tree = new RadixTree();
        assertTrue(tree.add("1234567890123"));
        tree.forEach(System.out::println);
        new Checker().check(tree,
            node -> {
                assertEquals("123", getString(node));
                assertEquals('4', node.key(0));
                assertFalse(node.completeKey(0));
                assertFalse(node.completeString());
                assertEquals(1, node.keyCount());
            },
            node -> {
                assertEquals("567", getString(node));
                assertEquals('8', node.key(0));
                assertFalse(node.completeKey(0));
                assertFalse(node.completeString());
                assertEquals(1, node.keyCount());
            },
            node -> {
                assertEquals("901", getString(node));
                assertEquals('2', node.key(0));
                assertFalse(node.completeKey(0));
                assertFalse(node.completeString());
                assertEquals(1, node.keyCount());
            },
            node -> {
                assertEquals("3", getString(node));
                assertEquals(0, node.keyCount());
                assertTrue(node.completeString());
            }
        );
    }

    private String getString(Node3 node) {
        int length = node.stringLength();
        byte[] bytes = new byte[length];
        node.string(bytes);
        return new String(bytes, 0, length);
    }
}
