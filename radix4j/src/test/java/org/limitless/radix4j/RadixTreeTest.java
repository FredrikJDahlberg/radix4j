package org.limitless.radix4j;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

import static org.limitless.radix4j.Node.Header;
import static org.limitless.radix4j.Node.Index;

public class RadixTreeTest {

    @Test
    public void addBasics() {
        final var tree = new RadixTree();
        addContains(tree, "crow");
        addContains(tree, "cat");
        addContains(tree, "cats");
        addContains(tree, "cow");
        addContains(tree, "pig");
        addContains(tree, "pin");
        tree.forEach(System.out::println);
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
        assertFalse(tree.contains("cow"));
        assertTrue(tree.contains("crow"));

        assertTrue(tree.remove("pig"));
        assertFalse(tree.contains("pig"));
        assertTrue(tree.contains("pin"));

        assertTrue(tree.remove("pin"));
        assertFalse(tree.contains("pin"));

        assertTrue(tree.remove("crow"));
        assertFalse(tree.contains("crow"));
        assertEmpty(tree);
    }

    @Test
    public void addLongString() {
        final var tree = new RadixTree();
        addContains(tree, "12345678901234567890-a");
        addContains(tree, "12345678901234567890-b");
        addContains(tree, "12345678901234567890-c");
        addContains(tree, "12345678901234567890-d");
        addContains(tree, "12345678901234567890-e");
        addContains(tree, "12345678901234567890-f");
        addContains(tree, "12345678901234567890-g");
        addContains(tree, "12345678901234567890-h");
        addContains(tree, "12345678901234567890-i");
        addContains(tree, "12345678901234567890-j");
        addContains(tree, "12345678901234567890-k");
        addContains(tree, "12345678901234567890-l");
        addContains(tree, "12345678901234567890-m");
        addContains(tree, "12345678901234567890-n");
        addContains(tree, "12345678901234567890-o");
        addContains(tree, "12345678901234567890-p");
    }

    @Test
    public void addWithOffset() {
        final var tree = new RadixTree();
        final byte[] strings = "cat bison dog crow".getBytes();
        assertTrue(tree.add(0, 3, strings));
        assertTrue(tree.add(4,5, strings));
        assertTrue(tree.add(10,3, strings));
        assertTrue(tree.add(14,4, strings));
        tree.forEach(System.out::println);

        assertEquals(4, tree.size());
        assertTrue(tree.contains("cat"));
        assertTrue(tree.contains("dog"));
        assertTrue(tree.contains("crow"));
        assertTrue(tree.contains("bison"));
    }

    @Test
    public void treeWithOneSegment() {
        final var tree = new RadixTree();
        final int count = 500_000;
        for (int i = 0; i < count; ++i) {
            final String str = "abcdefghijklmnop-" + i;
            assertTrue(tree.add(str), str);
        }
        for (int i = 0; i < count; ++i) {
            final String str = "abcdefghijklmnop-" + i;
            assertTrue(tree.contains(str), str);
        }
        for (int i = 0; i < count; ++i) {
            final String str = "abcdefghijklmnop-" + i;
            assertTrue(tree.remove(str), str);
        }
        assertEmpty(tree);
    }

    @Test
    public void clearTree() {
        final var tree = new RadixTree();
        addContains(tree, "test");
        tree.clear();
        assertEquals(0, tree.size());
        addContains(tree, "test");
    }

    @Disabled
    @Test
    public void benchmarkMaxLimits() {
        final int count = 167_772_080;
        final var tree = new RadixTree(RadixTree.MAX_BLOCK_COUNT);
        long bytes = 0;
        long elapsed = 0;
        for (int i = 0; i < count; ++i) {
            final String str = "abcdefghijklmnop-" + i;
            bytes += str.length();
            long timestamp = System.currentTimeMillis();
            assertTrue(tree.add(str), str);
            elapsed += System.currentTimeMillis() - timestamp;
        }
        System.out.printf("Add: %,d strings in %d ms\n", count, elapsed);
        System.out.printf("Limits: blocks = %,d, strings = %,d\n", Node.BYTES * tree.allocatedBlocks(), bytes);

        elapsed = 0;
        for (int i = 0; i < count; ++i) {
            final String str = "abcdefghijklmnop-" + i;
            long timestamp = System.currentTimeMillis();
            assertTrue(tree.contains(str), str);
            elapsed += System.currentTimeMillis() - timestamp;
        }
        System.out.printf("Contains: %,d strings in %d ms\n", count, elapsed);

        elapsed = 0;
        for (int i = 0; i < count; ++i) {
            final String str = "abcdefghijklmnop-" + i;
            long timestamp = System.currentTimeMillis();
            assertTrue(tree.remove(str), str);
            elapsed += System.currentTimeMillis() - timestamp;
        }
        System.out.printf("Remove: %,d strings in %d ms\n", count, elapsed);

        assertEmpty(tree);
    }

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
                final byte header = node.header();
                assertEquals(0, Header.stringLength(header));
                assertEquals(2, Header.indexCount(header));

                final int index0 = node.index(0);
                assertEquals('a', (char) Index.key(index0));
                assertFalse(Index.completeKey(index0));

                final int index1 = node.index(1);
                assertEquals('x', (char) Index.key(index1));
                assertTrue(Index.completeKey(index1));
            },
            node -> {
                final byte header = node.header();
                assertTrue(Header.completeString(header));
                assertEquals(0, Header.indexCount(header));
                assertEquals("y", getString(node));
            },
            node -> {
                final byte header = node.header();
                assertTrue(Header.completeString(header));
                assertEquals(0, Header.indexCount(header));
                assertEquals("b", getString(node));
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
                final byte header = node.header();
                assertEquals(2, Header.indexCount(header));
                assertEquals("abcd", getString(node));
                assertEquals('e', (char) Index.key(node.index(0)));
                assertEquals('x', (char) Index.key(node.index(1)));
            },
            node -> {
                final byte header = node.header();
                assertEquals("y", getString(node));
                assertEquals(0, Header.indexCount(header));
            },
            node -> {
                final byte header = node.header();
                assertTrue(Header.completeString(header));
                assertEquals(0, Header.indexCount(header));
                assertEquals("f", getString(node));
            }
        );
    }

    @Test
    public void splitRoot2String15() {
        final var tree = new RadixTree();
        addContains(tree, "abcdefghijklm028");
        addContains(tree, "abcdefghijklm030");
        new Checker().check(tree,
            node -> {
                final byte header = node.header();
                assertEquals(1, Header.indexCount(header));
                assertEquals('h', (char) Index.key(node.index(0)));
                assertEquals("abcdefg", getString(node));
            },
            node -> {
                final byte header = node.header();
                assertEquals("ijklm0", getString(node));
                assertEquals(2, Header.indexCount(header));
                assertEquals(2, Header.indexCount(header));
                assertEquals('2', (char) Index.key(node.index(0)));
                assertEquals('3', (char) Index.key(node.index(1)));
            },
            node -> {
                final byte header = node.header();
                assertTrue(Header.completeString(header));
                assertEquals(0, Header.indexCount(header));
                assertEquals("0", getString(node));
            },
            node -> {
                final byte header = node.header();
                assertEquals(0, Header.indexCount(header));
                assertTrue(Header.completeString(header));
                assertEquals("8", getString(node));
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
    public void addRootKeyString8() {
        final var tree = new RadixTree();
        addContains(tree, "ABCDEFG_H");
        addContains(tree, "ABCDEFG_I");
        new Checker().check(tree,
            node -> {
                final byte header = node.header();
                assertEquals(1, Header.indexCount(header));
                assertEquals("ABCDEFG", getString(node));
                final int index0 = node.index(0);
                assertEquals((byte) '_', Index.key(index0));
                assertFalse(Index.completeKey(index0));
            },
            node -> {
                final byte header = node.header();
                assertEquals("", getString(node));
                assertFalse(Header.completeString(header));
                assertEquals(2, Header.indexCount(header));

                final int index0 = node.index(0);
                assertTrue(Index.completeKey(index0));
                assertEquals('H', Index.key(index0));

                final int index1 = node.index(1);
                assertTrue(Index.completeKey(index1));
                assertEquals('I', Index.key(index1));
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
                final byte header = node.header();
                assertFalse(Header.completeString(header));
                assertEquals("m0", getString(node));
                assertEquals(2, Header.indexCount(header));
                final int index0 = node.index(0);
                assertFalse(Index.completeKey(index0));
                assertEquals('2', (char) Index.key(index0));
                final int index1 = node.index(1);
                assertFalse(Index.completeKey(index1));
                assertEquals('3', (char) Index.key(index1));
            },
            node -> {
                assertTrue(Header.completeString(node.header()));
                assertEquals("0", getString(node));
            },
            node -> {
                assertTrue(Header.completeString(node.header()));
                assertEquals("8", getString(node));
            }
        );
    }

    @Test
    public void splitRoot3String4Keys3() {
        final var tree = new RadixTree();
        addContains(tree, "aaaam028");
        addContains(tree, "aaaam029");
        addContains(tree, "aaaam030");
    }

    @Test
    public void splitRoot4String4Keys4() {
        final var tree = new RadixTree();
        addContains(tree, "aaaam025");
        addContains(tree, "aaaam026");
        addContains(tree, "aaaam027");
        addContains(tree, "aaaam030");
    }

    @Test
    public void splitRoot5String5Keys5() {
        final var tree = new RadixTree();
        addContains(tree, "aaaam025");
        addContains(tree, "aaaam026");
        addContains(tree, "aaaam027");
        addContains(tree, "aaaam028");
        addContains(tree, "aaaam030");
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
                final byte header = node.header();
                assertEquals(4, Header.indexCount(header));
                assertEquals("abc2", getString(node));
                assertEquals('5', (char) Index.key(node.index(0)));
                assertEquals('6', (char) Index.key(node.index(1)));
                assertEquals('7', (char) Index.key(node.index(2)));
                assertEquals('8', (char) Index.key(node.index(3)));
            },
            node -> assertEquals("0", getString(node)),
            node -> assertEquals("0", getString(node)),
            node -> assertEquals("0", getString(node)),
            node -> assertEquals("0", getString(node))
        );
    }

    @Test
    public void splitRootString3() {
        final var tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "car");

        new Checker().check(tree,
            node -> {
                final byte header = node.header();
                assertEquals("ca", getString(node), "string");
                assertFalse(Header.completeString(header));
                assertEquals(2, Header.indexCount(header), "key count");

                final int index0 = node.index(0);
                assertEquals('t', (char) Index.key(index0), "key 0");
                assertTrue(Index.completeKey(index0), "key complete 0");

                final int index1 = node.index(1);
                assertEquals('r', (char) Index.key(index1), "key 1");
                assertTrue(Index.completeKey(index1), "key complete 1");
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
                assertEquals(2, Header.indexCount(node.header()));

                final int index0 = node.index(0);
                assertEquals((byte) 's', Index.key(index0));
                assertTrue(Index.completeKey(index0));

                final int index1 = node.index(1);
                assertEquals((byte) 't', Index.key(index1));
                assertTrue(Index.completeKey(index1));
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
                final byte header = node.header();
                assertFalse(Header.completeString(header));
                assertEquals(1, Header.indexCount(header));
                assertEquals("abcdefg", getString(node));

                final int index = node.index(0);
                assertEquals('h', Index.key(index));
                assertFalse(Index.completeKey(index));
            },
            node -> {
                final byte header = node.header();
                assertEquals("ij-", getString(node));
                assertEquals(6, Header.indexCount(header));
                assertFalse(Header.completeString(header));
                for (int i = 0; i <= 5; ++i) {
                    final int index = node.index(i);
                    assertEquals('0' + i, Index.key(index));
                    assertTrue(Index.completeKey(index));
                }
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
                assertEquals("1000000", getString(node));
                assertEquals(1, Header.indexCount(node.header()));
                assertEquals('0', (char) Index.key(node.index(0)));
            },
            node -> {
                assertEquals("000000", getString(node));
                assertEquals(2, Header.indexCount(node.header()));
                assertEquals('1', (char) Index.key(node.index(0)));
                assertEquals('2', (char) Index.key(node.index(1)));
            },
            node -> {
                final byte header = node.header();
                assertTrue(Header.completeString(header));
                assertEquals("0", getString(node));
                assertEquals(0, Header.indexCount(header));
            },
            node -> {
                final byte header = node.header();
                assertEquals(0, Header.stringLength(header));
                assertEquals(3, Header.indexCount(header));

                final int index0 = node.index(0);
                assertEquals('7', (char) Index.key(index0));
                assertTrue(Index.completeKey(index0));

                final int index1 = node.index(1);
                assertEquals('8', (char) Index.key(index1));
                assertTrue(Index.completeKey(index1));

                final int index2 = node.index(2);
                assertEquals('9', (char) Index.key(index2));
                assertTrue(Index.completeKey(index2));
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
        assertEmpty(tree);
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
                final byte header = node.header();
                assertTrue(Header.completeString(header));
                assertEquals(0, Header.indexCount(header));
                assertEquals("cat", getString(node));
            }
        );

        assertTrue(tree.remove("cat"));
        assertEquals(0, tree.size());
        assertEquals(5, tree.allocatedBlocks());
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
                final byte header = node.header();
                assertFalse(Header.completeString(header));
                assertEquals(1, Header.indexCount(header));
                assertEquals("cat", getString(node));

                final int index = node.index(0);
                assertEquals('s', (char) Index.key(index));
                assertTrue(Index.completeKey(index));
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
                final byte header = node.header();
                assertEquals(0, Header.stringLength(header));
                assertEquals(2, Header.indexCount(header));

                final int index0 = node.index(0);
                assertEquals('c', Index.key(index0));
                assertFalse(Index.completeKey(index0));

                final int index1 = node.index(1);
                assertEquals('p', Index.key(index1));
                assertFalse(Index.completeKey(index1));
            },
            node -> {
                assertEquals("ig", getString(node));
                assertTrue(Header.completeString(node.header()));
            },
            node -> {
                final byte header = node.header();
                assertFalse(Header.completeString(header));
                assertEquals(1, Header.indexCount(header));
                assertEquals("at", getString(node));

                final int index = node.index(0);
                assertEquals('s', (char) Index.key(index));
                assertTrue(Index.completeKey(index));
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
        assertFalse(tree.isEmpty());
        assertEquals(1, tree.size());
    }

    @Test
    public void addParentWithMergeAndRemove() {
        final var tree = new RadixTree();
        addContains(tree, "a");
        addContains(tree, "pig");
        new Checker().check(tree,
            node -> {
                final byte header = node.header();
                assertEquals(0, Header.stringLength(header));
                assertEquals(2, Header.indexCount(header));

                final int index0 = node.index(0);
                assertEquals('a', (char) Index.key(index0));
                assertTrue(Index.completeKey(index0));

                final int index1 = node.index(1);
                assertEquals('p', (char) Index.key(index1));
                assertFalse(Index.completeKey(1));
            },
            node -> {
                final byte header = node.header();
                assertTrue(Header.completeString(header));
                assertEquals(0, Header.indexCount(header));
                assertEquals("ig", getString(node));
            }
        );
        assertTrue(tree.remove("pig"));
        assertTrue(tree.remove("a"));
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

    private static void assertEmpty(final RadixTree tree) {
        assertTrue(tree.isEmpty());
        assertEquals(0, tree.size());
        if (tree.allocatedBlocks() >= 6) {
            tree.forEach(System.out::println);
            fail("tree not empty");
        }
    }

    private static void addContains(final RadixTree tree, final String string) {
        final int size = tree.size();
        final boolean empty = tree.isEmpty();
        if (!tree.add(string)) {
            tree.forEach(System.out::println);
            fail("failed add: string =  " + string);
        }
        if (!tree.contains(string)) {
            tree.forEach(System.out::println);
            fail("failed contains: string = " + string);
        }
        assertEquals(empty, size == 0);
        assertEquals(size + 1, tree.size());
    }

    private static String getString(Node node) {
        final byte header = node.header();
        final int length = Node.Header.stringLength(header);
        final byte[] bytes = new byte[length];
        node.string(bytes);
        return new String(bytes, 0, length);
    }
}
