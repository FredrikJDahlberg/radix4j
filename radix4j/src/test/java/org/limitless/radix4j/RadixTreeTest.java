package org.limitless.radix4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RadixTreeTest {

    @Test
    public void insertEmptyNode() {
        RadixTree tree = new RadixTree();
        assertTrue(tree.add("car"));
        assertTrue(tree.add("pig"));
        tree.forEach(System.out::println);
        assertTrue(tree.contains("car"));
        assertTrue(tree.contains("pig"));
    }

    @Test
    public void containsString2() {
        RadixTree tree = new RadixTree();
        assertTrue(tree.add("on"));
        assertTrue(tree.add("one"));
        tree.forEach(System.out::println);
        assertTrue(tree.contains("one"));
    }

    @Test
    public void containsString3() {
        RadixTree tree = new RadixTree();
        assertTrue(tree.add("cat"));
        assertTrue(tree.add("cats"));
        tree.forEach(System.out::println);
        assertTrue(tree.contains("cats"));
    }

    @Test
    public void basics() {
        RadixTree tree = new RadixTree();
        assertTrue(tree.add("cat"));
        assertTrue(tree.add("cats"));
        assertTrue(tree.add("cow"));
        assertTrue(tree.add("pig"));
        assertTrue(tree.add("pin"));
        tree.forEach(System.out::println);
        new Checker().check(tree,
            node -> {
                assertEquals(0, node.stringLength());
                assertEquals(2, node.keyCount());
                assertEquals('c', (char) node.key(0));
                assertEquals('p', (char) node.key(1));
            },
            node -> {
                assertEquals("i", getString(node));
                assertFalse(node.completeString());
                assertEquals(2, node.keyCount());
                assertEquals('n', (char) node.key(1));
                assertEquals('g', (char) node.key(0));
                assertTrue(node.completeKey(0));
                assertTrue(node.completeKey(1));
            },
            node -> {
                assertEquals(0, node.stringLength());
                assertEquals(2, node.keyCount());
                assertEquals('a',  (char) node.key(0));
                assertEquals('o', node.key(1));
                assertFalse(node.completeKey(0));
                assertFalse(node.completeKey(1));
            },
            node -> {
                assertEquals("w", getString(node));
                assertTrue(node.completeString());
            },
            node -> {
                assertEquals("t", getString(node));
                assertEquals(1, node.keyCount());
                assertEquals('s', node.key(0));
                assertTrue(node.completeString());
                assertTrue(node.completeKey(0));
            }
        );

        assertTrue(tree.contains("pin"));
        assertTrue(tree.contains("pig"));
        assertTrue(tree.contains("cat"));
        assertTrue(tree.contains("cats"));
        assertTrue(tree.contains("cow"));
    }

    @Test
    public void twoStringsLength3Split() {
        RadixTree tree = new RadixTree();
        assertTrue(tree.add("cat"));
        assertTrue(tree.add("car"));
        tree.forEach(System.out::println);
        new Checker().check(tree,
            node -> {
                assertEquals("ca", getString(node), "string");
                assertEquals('t', (char) node.key(0), "key 0");
                assertTrue(node.completeKey(0), "key complete 0");
                assertEquals('r', (char) node.key(1), "key 1");
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
