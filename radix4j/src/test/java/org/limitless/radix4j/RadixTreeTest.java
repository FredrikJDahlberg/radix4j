package org.limitless.radix4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RadixTreeTest {

    @Test
    public void addRootParent() {
        final var tree = new RadixTree();
        addContains(tree, "abc");
        addContains(tree, "xyz");
        addContains(tree, "123");
    }

    @Test
    public void addRootParentAndKey() {
        final var tree = new RadixTree();
        addContains(tree, "ab");
        addContains(tree, "xy");
        addContains(tree, "x");
        new Checker().check(tree,
            node -> {
                assertEquals(0, node.stringLength());
                assertEquals(2, node.keyCount());
                assertEquals('a', (char) node.key(0));
                assertFalse(node.completeKey(0));
                assertEquals('x', (char) node.key(1));
                assertTrue(node.completeKey(1));
            },
            node -> {
                assertEquals("b", getString(node));
                assertTrue(node.completeString());
                assertEquals(0, node.keyCount());
            },
            node -> {
                assertEquals("y", getString(node));
                assertTrue(node.completeString());
                assertEquals(0, node.keyCount());
            }
        );
    }

    @Test
    public void addParentChildAndKey() {
        final var tree = new RadixTree();
        addContains(tree, "abcdef");
        addContains(tree, "abcdxy");
        addContains(tree, "abcdx");
        addContains(tree, "abcd");
        new Checker().check(tree,
            node -> {
                assertEquals("abc", getString(node));
                assertEquals(1, node.keyCount());
                assertEquals('d', (char) node.key(0));
                assertTrue(node.completeKey(0));
            },
            node -> {
                assertEquals(0, node.stringLength());
                assertEquals(2, node.keyCount());
                assertFalse(node.completeKey(0));
                assertEquals('e', (char) node.key(0));
                assertTrue(node.completeKey(1));
                assertEquals('x', (char) node.key(1));
            },
            node -> {
                assertEquals("f", getString(node));
                assertTrue(node.completeString());
                assertEquals(0, node.keyCount());
            },
            node -> {
                assertEquals("y", getString(node));
                assertTrue(node.completeString());
                assertEquals(0, node.keyCount());
            }
        );
    }

    @Test
    public void splitRoot2String15() {
        final var tree = new RadixTree();
        addContains(tree, "abcdefghijklm028");
        assertTrue(tree.add("abcdefghijklm030"));
        new Checker().check(tree,
            node -> {
                assertEquals("abc", getString(node));
                assertEquals(1, node.keyCount());
                assertEquals('d', (char) node.key(0));
            },
            node -> {
                assertEquals("efg", getString(node));
                assertEquals(1, node.keyCount());
                assertEquals('h', (char) node.key(0));
            },
            node -> {
                assertEquals("ijk", getString(node));
                assertEquals(1, node.keyCount());
                assertEquals('l', (char) node.key(0));
            },
            node -> {
                assertEquals("m0", getString(node));
                assertEquals(2, node.keyCount());
                assertEquals('2', (char) node.key(0));
                assertEquals('3', (char) node.key(1));
            },
            node -> {
                assertEquals("8", getString(node));
                assertEquals(0, node.keyCount());
                assertTrue(node.completeString());
            },
            node -> {
                assertEquals("0", getString(node));
                assertTrue(node.completeString());
                assertEquals(0, node.keyCount());
            }
        );
    }

    @Test
    public void addRootCompleteKeyString2() {
        final var tree = new RadixTree();
        addContains(tree, "on");
        addContains(tree, "one");
    }

    @Test
    public void addRootCompleteKeyString3() {
        final var tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "cats");
    }

    @Test
    public void addRootKeyString4() {
        final var tree = new RadixTree();
        addContains(tree, "AB_DF");
        addContains(tree, "AB_EG");
        new Checker().check(tree,
            node -> {
                assertEquals("AB_", getString(node));
                assertEquals((byte) 'D', node.key(0));
                assertFalse(node.completeKey(0));
                assertEquals((byte) 'E', node.key(1));
                assertFalse(node.completeKey(1));
                assertEquals(2, node.keyCount());
            },
            node -> {
                assertEquals("F", getString(node));
                assertTrue(node.completeString());
            },
            node -> {
                assertEquals("G", getString(node));
                assertTrue(node.completeString());
            }
        );
    }

    @Test
    public void splitRoot2String4Keys2() {
        final var tree = new RadixTree();
        addContains(tree, "m028");
        addContains(tree, "m030");
        new Checker().check(tree,
            node -> {
                assertEquals("m0", getString(node));
                assertEquals(2, node.keyCount());
                assertEquals('2', (char) node.key(0));
                assertEquals('3', (char) node.key(1));
                assertFalse(node.completeString());
                assertFalse(node.completeKey(0));
                assertFalse(node.completeKey(1));
            },
            node -> {
                assertEquals("8", getString(node));
                assertTrue(node.completeString());
            },
            node -> {
                assertEquals("0", getString(node));
                assertTrue(node.completeString());
            }
        );
    }

    @Test
    public void splitRoot3String4Keys3() {
        final var tree = new RadixTree();
        addContains(tree, "m028");
        addContains(tree, "m029");
        addContains(tree, "m030");
    }

    @Test
    public void splitRoot4String4Keys4() {
        final var tree = new RadixTree();
        addContains(tree, "m025");
        addContains(tree, "m026");
        addContains(tree, "m027");
        addContains(tree, "m030");
    }

    @Test
    public void splitRoot5String5Keys5() {
        final var tree = new RadixTree();
        addContains(tree, "m025");
        addContains(tree, "m026");
        addContains(tree, "m027");
        addContains(tree, "m028");
        addContains(tree, "m030");
    }

    @Test
    public void addParentAndCompleteKeys() {
        final var tree = new RadixTree();
        addContains(tree, "abc250");
        addContains(tree, "abc260");
        addContains(tree, "abc270");
        addContains(tree, "abc280");

        new Checker().check(tree,
            node -> {
                assertEquals("abc", getString(node));
                assertEquals('2', (char) node.key(0));
            },
            node -> {
                assertEquals("", getString(node));
                assertEquals(3, node.keyCount());
                assertEquals('5', (char) node.key(0));
                assertEquals('6', (char) node.key(1));
                assertEquals(0, node.key(2));
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
                assertEquals("", getString(node));
                assertEquals(2, node.keyCount());
                assertEquals('7', (char) node.key(0));
                assertEquals('8', (char) node.key(1));
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
    public void addBasics() {
        final var tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "cats");
        addContains(tree, "cow");
        addContains(tree, "pig");
        addContains(tree, "pin");
        addContains(tree, "crow");
        new Checker().check(tree,
            node -> {
                assertEquals(0, node.stringLength());
                assertEquals(2, node.keyCount());
                assertEquals('c', (char) node.key(0));
                assertEquals('p', (char) node.key(1));
                assertFalse(node.completeKey(0));
                assertFalse(node.completeKey(1));
            },
            node -> {
                assertEquals(0, node.stringLength());
                assertEquals(3, node.keyCount());
                assertEquals('a', (char) node.key(0));
                assertEquals('o', node.key(1));
                assertEquals('r', node.key(2));
                assertFalse(node.completeKey(0));
                assertFalse(node.completeKey(1));
                assertFalse(node.completeKey(2));
            },
            node -> {
                assertEquals("t", getString(node));
                assertEquals(1, node.keyCount());
                assertEquals('s', node.key(0));
                assertTrue(node.completeString());
                assertTrue(node.completeKey(0));
            },
            node -> {
                assertEquals("w", getString(node));
                assertTrue(node.completeString());
            },
            node -> {
                assertEquals("ow", getString(node));
                assertTrue(node.completeString());
            },
            node -> {
                assertEquals("i", getString(node));
                assertFalse(node.completeString());
                assertEquals(2, node.keyCount());
                assertEquals('g', (char) node.key(0));
                assertEquals('n', (char) node.key(1));
                assertTrue(node.completeKey(0));
                assertTrue(node.completeKey(1));
            }
        );
    }

    @Test
    public void splitRootString3() {
        final var tree = new RadixTree();
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
    public void splitRootString4() {
        final var tree = new RadixTree();
        addContains(tree, "cars");
        addContains(tree, "cart");

        new Checker().check(tree,
            node -> {
                assertEquals("car", getString(node));
                assertEquals((byte) 's', node.key(0));
                assertTrue(node.completeKey(0));
                assertEquals((byte) 't', node.key(1));
                assertTrue(node.completeKey(1));
                assertEquals(2, node.keyCount());
            }
        );
    }

    @Test
    public void addRootString11Keys6() {
        final var tree = new RadixTree();
        addContains(tree, "abcdefghij-0");
        addContains(tree, "abcdefghij-1");
        addContains(tree, "abcdefghij-2");
        addContains(tree, "abcdefghij-3");
        addContains(tree, "abcdefghij-4");
        addContains(tree, "abcdefghij-5");
        new Checker().check(tree,
            node -> {
                assertEquals("abc", getString(node));
                assertEquals('d', node.key(0));
                assertFalse(node.completeKey(0));
                assertFalse(node.completeString());
                assertEquals(1, node.keyCount());
            },
            node -> {
                assertEquals("efg", getString(node));
                assertEquals('h', node.key(0));
                assertFalse(node.completeKey(0));
                assertFalse(node.completeString());
                assertEquals(1, node.keyCount());
            },
            node -> {
                assertEquals("ij-", getString(node));
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
                assertEquals(0, node.key(2));
                assertFalse(node.completeKey(2));
            },
            node -> {
                assertEquals(0, node.stringLength());
                assertEquals(2, node.keyCount());
                assertEquals('4', node.key(0));
                assertTrue(node.completeKey(0));
                assertEquals('5', node.key(1));
                assertTrue(node.completeKey(1));
            }
        );
    }

    @Test
    public void splitRoot2String11Keys1() {
        final var tree = new RadixTree();
        addContains(tree, "flabbergasted");
        assertFalse(tree.add("flabbergasted"));
        addContains(tree, "flabbergast");
    }

    @Test
    public void splitRootString6() {
        final var tree = new RadixTree();
        addContains(tree, "" + (17 + 1_000_000_000_000_000L));
        addContains(tree, "" + (18 + 1_000_000_000_000_000L));
        addContains(tree, "" + (19 + 1_000_000_000_000_000L));
        addContains(tree, "" + (20 + 1_000_000_000_000_000L));
        new Checker().check(tree,
            node -> {
                assertEquals("100", getString(node));
                assertEquals(1, node.keyCount());
                assertEquals('0', (char) node.key(0));
            },
            node -> {
                assertEquals("000", getString(node));
                assertEquals(1, node.keyCount());
                assertEquals('0', (char) node.key(0));
            },
            node -> {
                assertEquals("000", getString(node));
                assertEquals(1, node.keyCount());
                assertEquals('0', (char) node.key(0));
            },
            node -> {
                assertEquals("00", getString(node));
                assertEquals(2, node.keyCount());
                assertEquals('1', (char) node.key(0));
                assertEquals('2', (char) node.key(1));
            },
            node -> {
                assertEquals("", getString(node));
                assertEquals(3, node.keyCount());
                assertEquals('7', (char) node.key(0));
                assertEquals('8', (char) node.key(1));
                assertEquals('9', (char) node.key(2));
                assertTrue(node.completeKey(0));
                assertTrue(node.completeKey(1));
                assertTrue(node.completeKey(2));
            },
            node -> {
                assertEquals("0", getString(node));
                assertTrue(node.completeString());
            }
        );
    }

    @Test
    public void removeCompleteKeyWithChildren() {
        final var tree = new RadixTree();
        assertTrue(tree.add("12345678901234567890_1"));
        assertTrue(tree.add("12345678901234567890_2"));
        assertTrue(tree.add("12345678901234567890_10"));

        assertTrue(tree.contains("12345678901234567890_1"));
        assertTrue(tree.contains("12345678901234567890_2"));
        assertTrue(tree.contains("12345678901234567890_10"));

        assertTrue(tree.remove("12345678901234567890_1"));
        assertTrue(tree.remove("12345678901234567890_2"));
        assertTrue(tree.remove("12345678901234567890_10"));
    }

    @Test
    public void removeRootString() {
        final var tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "cats");
        assertEquals(2, tree.size());

        assertTrue(tree.remove("cat"));
        assertFalse(tree.contains("cat"));
        assertTrue(tree.contains("cats"));

        assertEquals(1, tree.size());

        assertTrue(tree.remove("cats"));
        assertFalse(tree.remove("cats"));
        assertFalse(tree.contains("cats"));

        assertEquals(0, tree.size());
        assertEquals(0, tree.allocatedBlocks());
    }

    @Test
    public void removeRootKey() {
        final  var tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "cats");
        assertEquals(2, tree.size());

        assertTrue(tree.remove("cats"));
        assertFalse(tree.contains("cats"));
        assertTrue(tree.contains("cat"));
        assertEquals(1, tree.size());

        new Checker().check(tree,
            node -> {
                assertEquals("cat", getString(node));
                assertTrue(node.completeString());
                assertEquals(0, node.keyCount());
            }
        );

        assertTrue(tree.remove("cat"));
        assertEquals(0, tree.size());
        assertEquals(0, tree.allocatedBlocks());
    }

    @Test
    public void removeRootSubString() {
        final var tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "cats");
        assertEquals(2, tree.size());

        assertTrue(tree.remove("cat"));
        assertTrue(tree.contains("cats"));
        assertEquals(1, tree.size());

        new Checker().check(tree,
            node -> {
                assertEquals("cat", getString(node));
                assertFalse(node.completeString());
                assertEquals(1, node.keyCount());
                assertEquals('s', (char) node.key(0));
                assertTrue(node.completeKey(0));
            }
        );
    }

    @Test
    public void removeNodeSubString() {
        final var tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "cats");
        addContains(tree, "pig");
        assertEquals(3, tree.size());

        assertTrue(tree.remove("cat"));
        assertTrue(tree.contains("cats"));
        assertTrue(tree.contains("pig"));
        assertEquals(2, tree.size());

        new Checker().check(tree,
            node -> {
                assertEquals(0, node.stringLength());
                assertEquals(2, node.keyCount());
                assertEquals('c', node.key(0));
                assertFalse(node.completeKey(0));
                assertEquals('p', node.key(1));
                assertFalse(node.completeKey(1));
            },
            node -> {
                assertEquals("at", getString(node));
                assertFalse(node.completeString());
                assertEquals(1, node.keyCount());
                assertEquals('s', (char) node.key(0));
                assertTrue(node.completeKey(0));
            },
            node -> {
                assertEquals("ig", getString(node));
                assertTrue(node.completeString());
            }
        );

        assertTrue(tree.remove("cats"));
        assertTrue(tree.remove("pig"));

        assertEmpty(tree);
    }

    @Test
    public void removeRootString11Keys6() {
        final var tree = new RadixTree();
        addContains(tree, "abcdefghij-0");
        addContains(tree, "abcdefghij-1");
        addContains(tree, "abcdefghij-2");
        addContains(tree, "abcdefghij-3");
        addContains(tree, "abcdefghij-4");
        addContains(tree, "abcdefghij-5");

        assertTrue(tree.remove("abcdefghij-0"));
        assertTrue(tree.remove("abcdefghij-1"));
        assertTrue(tree.remove("abcdefghij-2"));
        assertFalse(tree.contains("abcdefghij-0"));
        assertFalse(tree.contains("abcdefghij-1"));
        assertFalse(tree.contains("abcdefghij-2"));
        assertTrue(tree.remove("abcdefghij-3"));
        assertFalse(tree.contains("abcdefghij-3"));
        assertTrue(tree.remove("abcdefghij-4"));
        assertFalse(tree.contains("abcdefghij-4"));
        assertTrue(tree.remove("abcdefghij-5"));
        assertFalse(tree.contains("abcdefghij-5"));

        assertEmpty(tree);
    }

    @Test
    public void removeRootEmptyString() {
        final var tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "pig");
        assertEquals(2, tree.size());

        assertTrue(tree.remove("cat"));
        assertFalse(tree.contains("cat"));
        assertTrue(tree.contains("pig"));

        assertTrue(tree.remove("pig"));
        assertFalse(tree.contains("pig"));
        assertEmpty(tree);
    }

    @Test
    public void removeNodeKey() {
        final var tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "cats");
        addContains(tree, "pig");
        assertEquals(3, tree.size());

        assertTrue(tree.remove("cats"));
        assertFalse(tree.contains("cats"));
        assertTrue(tree.contains("cat"));
        assertTrue(tree.contains("pig"));
        assertEquals(2, tree.size());

        assertTrue(tree.remove("cat"));
        assertFalse(tree.contains("cat"));
        assertTrue(tree.contains("pig"));
        assertEquals(1, tree.size());

        assertTrue(tree.remove("pig"));
        assertFalse(tree.remove("pig"));

        assertEmpty(tree);
    }

    @Test
    public void removeRoot() {
        final var tree = new RadixTree();
        tree.add("monkey");
        assertTrue(tree.remove("monkey"));
        assertTrue(tree.isEmpty());
        assertEquals(0, tree.size());
        assertEmpty(tree);
        assertTrue(tree.add("cat"));
        assertTrue(tree.contains("cat"));
        assertTrue(!tree.isEmpty());
        assertEquals(1, tree.size());
    }

    @Test
    public void addParentWithMergeAndRemove() {
        final var tree = new RadixTree();
        addContains(tree, "a");
        addContains(tree, "pig");
        new Checker().check(tree,
            node -> {
                assertEquals(0, node.stringLength());
                assertEquals(2, node.keyCount());
                assertEquals('a', (char) node.key(0));
                assertTrue(node.completeKey(0));
                assertEquals('p', (char) node.key(1));
                assertFalse(node.completeKey(1));
            },
            node -> {
                assertEquals("ig", getString(node));
                assertTrue(node.completeString());
                assertEquals(0, node.keyCount());
            }
        );
        assertTrue(tree.remove("pig"));
        assertTrue(tree.remove("a"));
    }

    @Test
    public void removeBasics() {
        final var tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "cats");
        addContains(tree, "cow");
        addContains(tree, "pin");
        addContains(tree, "pig");
        addContains(tree, "crow");

        assertTrue(tree.remove("cat"));
        assertFalse(tree.contains("cat"));
        assertTrue(tree.contains("cats"));

        assertTrue(tree.remove("cats"));
        assertFalse(tree.contains("cats"));

        assertTrue(tree.remove("cow"));
        assertTrue(tree.remove("pig"));
        assertTrue(tree.remove("pin"));
        assertTrue(tree.remove("crow"));
        assertEmpty(tree);
    }

    @Test
    public void addParentMissingCompleteKey() {
        final var tree = new RadixTree();
        addContains(tree, "1234567890-1");
        addContains(tree, "1234567890-10");
        assertTrue(tree.contains("1234567890-1"));
        addContains(tree, "1234567890-11");
        assertTrue(tree.contains("1234567890-1"));
    }

    private static void assertEmpty(final RadixTree tree)
    {
        assertTrue(tree.isEmpty());
        assertEquals(0, tree.size());
        if (tree.allocatedBlocks() >= 1) {
            tree.forEach(System.out::println);
            fail("tree not empty");
        }
    }

    private static void print(final String string, final RadixTree tree) {
        System.out.println(string);
        tree.forEach(System.out::println);
    }

    private static void addContains(final RadixTree tree, final String string) {
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

    private static void removeContains(final RadixTree tree, final String string) {
        final int size = tree.size();
        assertTrue(tree.remove(string), string);
        assertFalse(tree.contains(string));
        assertEquals(size - 1, tree.size());
    }

    private static String getString(Node3 node) {
        int length = node.stringLength();
        byte[] bytes = new byte[length];
        node.string(bytes);
        return new String(bytes, 0, length);
    }
}
