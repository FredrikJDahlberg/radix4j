package org.limitless.radix4j;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

import static org.limitless.radix4j.Node.Header;

public class RadixTreeTest {

    @Test
    public void addBasics() {
        final var tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "cats");
        addContains(tree, "cow");
        addContains(tree, "cabbage");
        addContains(tree, "crow");
        addContains(tree, "pig");
        addContains(tree, "pin");
        addContains(tree, "cabs");
        assertTrue(tree.contains("cat"));
    }

    @Test
    public void splitLongerStringWithIncludedKey() {
        final var tree = new RadixTree();
        addContains(tree, "cabbage");
        addContains(tree, "cabs");
        assertFalse(tree.contains("cabb"));
        new Checker().check(tree,
            node -> {
                assertEquals("cab", getString(node));
                assertEquals(2, Header.indexCount(node.header()));
                assertEquals('b', node.key(0));
                assertFalse(node.includeKey(0));
                assertEquals('s', node.key(1));
                assertTrue(node.includeKey(1));
            },
            node -> {
                assertEquals("a", getString(node));
                assertFalse(Header.includeString(node.header()));
                assertEquals(1, Header.indexCount(node.header()));
                assertEquals('g', node.key(0));
            },
            node -> {
                assertEquals("e", getString(node));
                assertTrue(Header.includeString(node.header()));
                assertEquals(0, Header.indexCount(node.header()));
            }
        );
    }

    @Test
    public void splitShorterStringWithIncludedKey() {
        final var tree = new RadixTree();
        addContains(tree, "cat");
        addContains(tree, "cabs");
        assertTrue(tree.contains("cat"));
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

                assertEquals('a', (char) node.key(0));
                assertFalse(node.includeKey(0));

                assertEquals('x', (char) node.key(1));
                assertTrue(node.includeKey(1));
            },
            node -> {
                final byte header = node.header();
                assertTrue(Header.includeString(header));
                assertEquals(0, Header.indexCount(header));
                assertEquals("y", getString(node));
            },
            node -> {
                final byte header = node.header();
                assertTrue(Header.includeString(header));
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
                assertEquals('e', (char) node.key(0));
                assertEquals('x', (char) node.key(1));
            },
            node -> {
                final byte header = node.header();
                assertEquals("y", getString(node));
                assertEquals(0, Header.indexCount(header));
            },
            node -> {
                final byte header = node.header();
                assertTrue(Header.includeString(header));
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
                assertEquals("abcde", getString(node));
                assertEquals(1, Header.indexCount(header));
                assertEquals('f', (char) node.key(0));
            },
            node -> {
                final byte header = node.header();
                assertEquals("ghijk", getString(node));
                assertEquals(1, Header.indexCount(header));
                assertEquals('l', (char) node.key(0));
            },
            node -> {
                final byte header = node.header();
                assertEquals("m0", getString(node));
                assertFalse(Header.includeString(header));
                assertEquals(2, Header.indexCount(header));
                assertEquals('2', (char) node.key(0));
                assertEquals('3', (char) node.key(1));
            },
            node -> {
                final byte header = node.header();
                assertTrue(Header.includeString(header));
                assertEquals(0, Header.indexCount(header));
                assertEquals("0", getString(node));
            },
            node -> {
                final byte header = node.header();
                assertEquals(0, Header.indexCount(header));
                assertTrue(Header.includeString(header));
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
                assertEquals("ABCDE", getString(node));

                assertEquals((byte) 'F', node.key(0));
                assertFalse(node.includeKey(0));
            },
            node -> {
                final byte header = node.header();
                assertEquals(2, Header.indexCount(header));
                assertEquals("G_", getString(node));

                assertTrue(node.includeKey(0));
                assertEquals('H', node.key(0));

                assertTrue(node.includeKey(1));
                assertEquals('I', node.key(1));
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
                assertFalse(Header.includeString(header));
                assertEquals("m0", getString(node));
                assertEquals(2, Header.indexCount(header));
                assertFalse(node.includeKey(0));
                assertEquals('2', (char) node.key(0));
                assertFalse(node.includeKey(1));
                assertEquals('3', (char) node.key(1));
            },
            node -> {
                assertTrue(Header.includeString(node.header()));
                assertEquals("0", getString(node));
            },
            node -> {
                assertTrue(Header.includeString(node.header()));
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
                assertEquals('5', (char) node.key(0));
                assertEquals('6', (char) node.key(1));
                assertEquals('7', (char) node.key(2));
                assertEquals('8', (char) node.key(3));
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
                assertFalse(Header.includeString(header));
                assertEquals(2, Header.indexCount(header), "key count");

                assertEquals('t', (char) node.key(0), "key 0");
                assertTrue(node.includeKey(0), "key complete 0");

                assertEquals('r', (char) node.key(1), "key 1");
                assertTrue(node.includeKey(1), "key complete 1");
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

                assertEquals((byte) 's', node.key(0));
                assertTrue(node.includeKey(0));

                assertEquals((byte) 't', node.key(1));
                assertTrue(node.includeKey(1));
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
                assertFalse(Header.includeString(header));
                assertEquals(1, Header.indexCount(header));
                assertEquals("abcde", getString(node));
                assertEquals('f', node.key(0));
                assertFalse(node.includeKey(0));
            },
            node -> {
                final byte header = node.header();
                assertEquals("ghij-", getString(node));
                assertEquals(6, Header.indexCount(header));
                assertFalse(Header.includeString(header));
                for (int i = 0; i <= 5; ++i) {
                    assertEquals('0' + i, node.key(i));
                    assertTrue(node.includeKey(i));
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
                assertEquals("10000", getString(node));
                assertEquals(1, Header.indexCount(node.header()));
                assertEquals('0', (char) node.key(0));
            },
            node -> {
                assertEquals("00000", getString(node));
                assertEquals(1, Header.indexCount(node.header()));
                assertEquals('0', (char) node.key(0));
            },
            node -> {
                assertEquals("00", getString(node));
                assertEquals(2, Header.indexCount(node.header()));
                assertEquals('1', (char) node.key(0));
                assertEquals('2', (char) node.key(1));
            },
            node -> {
                final byte header = node.header();
                assertTrue(Header.includeString(header));
                assertEquals("0", getString(node));
                assertEquals(0, Header.indexCount(header));
            },
            node -> {
                final byte header = node.header();
                assertEquals(0, Header.stringLength(header));
                assertEquals(3, Header.indexCount(header));

                assertEquals('7', (char) node.key(0));
                assertTrue(node.includeKey(0));

                assertEquals('8', (char) node.key(1));
                assertTrue(node.includeKey(1));

                assertEquals('9', (char) node.key(2));
                assertTrue(node.includeKey(2));
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
                assertTrue(Header.includeString(header));
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
                assertFalse(Header.includeString(header));
                assertEquals(1, Header.indexCount(header));
                assertEquals("cat", getString(node));

                assertEquals('s', (char) node.key(0));
                assertTrue(node.includeKey(0));
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

                assertEquals('c', node.key(0));
                assertFalse(node.includeKey(0));

                assertEquals('p', node.key(1));
                assertFalse(node.includeKey(1));
            },
            node -> {
                assertEquals("ig", getString(node));
                assertTrue(Header.includeString(node.header()));
            },
            node -> {
                final byte header = node.header();
                assertFalse(Header.includeString(header));
                assertEquals(1, Header.indexCount(header));
                assertEquals("at", getString(node));

                assertEquals('s', (char) node.key(0));
                assertTrue(node.includeKey(0));
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

                assertEquals('a', (char) node.key(0));
                assertTrue(node.includeKey(0));

                assertEquals('p', (char) node.key(1));
                assertFalse(node.includeKey(1));
            },
            node -> {
                final byte header = node.header();
                assertTrue(Header.includeString(header));
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

    @Disabled
    @Test
    public void benchmarkMaxLimits() {
        final int count = 200_000_000;
        final var tree = new RadixTree(RadixTree.MAX_BLOCKS_PER_SEGMENT);
        long bytes = 0;
        long elapsed = 0;
        for (int i = 0; i < count; ++i) {
            final String str = "abcdefghijklmnop-" + i;
            bytes += str.length();
            long timestamp = System.currentTimeMillis();
            boolean added = tree.add(str);
            elapsed += System.currentTimeMillis() - timestamp;
            assertTrue(added, str);
            if (i % 1_000_000 == 0) {
                System.out.printf("allocated = %,d\n", tree.allocatedBlocks() * 64);
            }
        }
        System.out.printf("Add: %,d strings in %d ms\n", count, elapsed);
        final int blocks = tree.allocatedBlocks();
        System.out.printf("Limits: blocks = %,d, segments = %d, strings = %,d\n",
            Node.BYTES * blocks, blocks / RadixTree.MAX_BLOCKS_PER_SEGMENT, bytes);

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
    public void missingNodeKey() {
        String[] strings = {
            "1234D2",
            "1234D3",
            "1234D4",
            "1234D5",
            "1234D6",
            "1234D7",
            "1234D8",
            "1234D9",
            "1234DA",
            "1234DB",
            "1234DC",
        };
        final var tree = new RadixTree();
        for (final String string : strings) {
            addContains(tree, string);
        }
        final String missing = "1234DD";
        assertTrue(tree.add(missing), missing);

        tree.forEach(System.out::println);
        for (final String string : strings) {
            assertTrue(tree.contains(string), string);
        }
        assertTrue(tree.contains(missing), missing);
        System.out.println(64 * tree.allocatedBlocks() + " / " + strings.length * strings[0].length());

    }

    @Disabled
    @Test
    public void hashSetMemoryUsage() {
        final HashSet<Integer> set = new HashSet<>(101);
        final Runtime runtime = Runtime.getRuntime();
        for (int i = 100_000_000; i <= 110_000_000; ++i) {
            assertTrue(set.add(i));
            if (i % 1_000_000 == 0) {
                System.out.printf("set: free = %,d MB\n", runtime.freeMemory() / 1024 / 1024);
            }
        }

        final RadixTree tree = new RadixTree(RadixTree.MAX_BLOCKS_PER_SEGMENT);
        for (int i = 100_000_000; i <= 110_000_000; ++i) {
            assertTrue(tree.add("" + i));
        }
        System.out.printf("tree: lim = %,d MB\n", tree.allocatedBlocks() * 64 / 1024 / 1024);
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
