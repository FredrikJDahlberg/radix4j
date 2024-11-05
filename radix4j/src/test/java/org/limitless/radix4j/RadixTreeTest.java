package org.limitless.radix4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RadixTreeTest {

    @Test
    public void noCommonPrefixRootNode() {
        RadixTree tree = new RadixTree();
        addContains(tree, "car");
        addContains(tree, "pig");
    }

    @Test
    public void noCommonPrefixReplaceIndex() {
        final RadixTree tree = new RadixTree();
        addContains(tree, "" + 100_000_000_000_028L);
        addContains(tree, "" + 100_000_000_000_030L);
        addContains(tree, "" + 100_000_000_000_029L);

        new Checker().check(tree,
            node -> {
                assertEquals("100", getString(node));
                assertEquals('0', (char) node.key(0));
            },
            node -> {
                assertEquals("000", getString(node));
                assertEquals('0', (char) node.key(0));
            },
            node -> {
                assertEquals("000", getString(node));
                assertEquals(1, node.keyCount());
            },
            node -> {
                assertEquals("0", getString(node));
                assertEquals(2, node.keyCount());
                assertEquals('2', (char) node.key(0));
                assertEquals('3', (char) node.key(1));
            },
            node -> {
                assertEquals("0", getString(node));
                assertTrue(node.completeString());
                assertEquals(0, node.keyCount());
            },
            node -> {
                assertEquals(0, node.stringLength());
                assertEquals(2, node.keyCount());
                assertEquals('8', (char) node.key(0));
                assertEquals('9', (char) node.key(1));
                assertTrue(node.completeKey(0));
                assertTrue(node.completeKey(1));
                assertFalse(node.completeString());
            }
        );
    }

    @Test
    public void commonPrefix2AddCompleteKey() {
        RadixTree tree = new RadixTree();
        addContains(tree, "on");
        addContains(tree, "one");
    }

    @Test
    public void commonPrefix3AddCompleteKey() {
        RadixTree tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "cats");
    }

    @Test
    public void basics() {
        RadixTree tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "cats");
        addContains(tree, "cow");
        addContains(tree, "pig");
        addContains(tree, "pin");

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
    }

    @Test
    public void commonPrefix2SplitRootNode() {
        RadixTree tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "car");

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
    public void commonPrefix3SplitRootNode() {
        RadixTree tree = new RadixTree();
        addContains(tree, "cars");
        addContains(tree, "cart");

        new Checker().check(tree,
            node -> {
                assertEquals("car", getString(node), "string");
                assertEquals((byte) 's', node.key(0), "key 0");
                assertTrue(node.completeKey(0), "key complete 0");
                assertEquals((byte) 't', node.key(1), "key 1");
                assertTrue(node.completeKey(1), "key complete 1");
                assertEquals(2, node.keyCount(), "key count");
            }
        );
    }

    @Test
    public void commonPrefix3String5SplitRootNode() {
        RadixTree tree = new RadixTree();
        addContains(tree, "ABCDF");
        addContains(tree, "ABCEG");

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
        addContains(tree, "123456789012");

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
        addContains(tree, "1234567890123");

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

    @Test
    public void commonPrefix11Strings5SplitRootNode() {
        RadixTree tree = new RadixTree();
        addContains(tree, "1234567890-0");
        addContains(tree, "1234567890-1");
        addContains(tree, "1234567890-2");
        addContains(tree, "1234567890-3");
        addContains(tree, "1234567890-4");
        addContains(tree, "1234567890-5");

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
                assertEquals("90-", getString(node));
                assertFalse(node.completeString());
                assertEquals(3, node.keyCount());
                assertEquals('0', node.key(0));
                assertTrue(node.completeKey(0));
                assertEquals('1', node.key(1));
                assertTrue(node.completeKey(1));
                assertEquals(Node3.EMPTY_KEY, node.key(2));
                assertFalse(node.completeKey(2));
            },
            node -> {
                assertEquals(0, node.stringLength());
                assertFalse(node.completeString());
                assertEquals(3, node.keyCount());
                assertEquals('2', node.key(0));
                assertTrue(node.completeKey(0));
                assertEquals('3', node.key(1));
                assertTrue(node.completeKey(1));
                // FIXME key 3
            },
            node -> {
                assertEquals(0, node.stringLength());
            }
        );
    }

    @Test
    public void commonPrefix11WordsSplitRootNode() {
        final RadixTree tree = new RadixTree();
        addContains(tree, "flabbergasted");
        assertFalse(tree.add("flabbergasted"));
        addContains(tree, "flabbergast");
    }

    @Test
    public void commonPrefixAddStringNotKey() {
        final RadixTree tree = new RadixTree();
        addContains(tree, "" + 1_000_000_000_250L);
        addContains(tree, "" + 1_000_000_000_260L);
        addContains(tree, "" + 1_000_000_000_270L);
        addContains(tree, "" + 1_000_000_000_280L);
        addContains(tree, "" + 1_000_000_000_281L);

        new Checker().check(tree,
            node -> {
                assertEquals("100", getString(node));
                assertEquals('0', (char) node.key(0));
            },
            node -> {
                assertEquals("000", getString(node));
                assertEquals('0', (char) node.key(0));
            },
            node -> {
                assertEquals("002", getString(node));
                assertEquals(3, node.keyCount());
                assertEquals('5', (char) node.key(0));
                assertEquals('6', (char) node.key(1));
                assertEquals(0, node.key(2));
            },
            node -> {
                assertEquals("0", getString(node));
                assertTrue(node.completeString());
                assertEquals(2, node.keyCount());
                assertEquals('7', (char) node.key(0));
                assertEquals('8', (char) node.key(1));
            },
            node -> {
                assertEquals("1", getString(node), node.toString());
                assertTrue(node.completeString());
            },
            node -> {
                assertEquals("0", getString(node));
                assertTrue(node.completeString());
            },
            node -> {
                assertEquals("0", getString(node));
                assertTrue(node.completeString());
            },
            node -> {
                assertEquals("0", getString(node));
                assertTrue(node.completeString());
            }
        );
    }

    @Test
    public void manyStrings() {
        var tree = new RadixTree();
        addContains(tree, "1000_000_000_000_001");
        tree.forEach(System.out::println);
        addContains(tree, "1000_000_000_000_010");
    }

    private void addContains(final RadixTree tree, final String string) {
        final int size = tree.size();
        final boolean empty = tree.isEmpty();
        final boolean failedAdd = !tree.add(string);
        final boolean failedContains = !tree.contains(string);
        if (failedAdd || failedContains) {
            tree.forEach(System.out::println);
        }
        if (failedAdd) {
            fail("add " + string);
        }
        if (failedContains) {
            fail("contains " + string);
        }
        assertEquals(empty, size == 0);
        assertEquals(size + 1, tree.size());
    }

    private String getString(Node3 node) {
        int length = node.stringLength();
        byte[] bytes = new byte[length];
        node.string(bytes);
        return new String(bytes, 0, length);
    }
}
