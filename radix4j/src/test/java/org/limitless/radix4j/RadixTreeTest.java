package org.limitless.radix4j;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;

import static org.junit.jupiter.api.Assertions.*;

import static org.limitless.radix4j.Node.Header;

public class RadixTreeTest {

    @Test
    public void splitShortPrefix() {
        final var tree = new RadixTree();
        check(tree, false, "cat", "cats", "cabbage");
    }

    @Test
    public void splitLongerPrefix() {
        final var tree = new RadixTree();
        check(tree, false, "money", "monkey", "montage");
    }

    @Test
    public void errorHandling() {
        assertThrows(IllegalArgumentException.class, () -> new RadixTree(63, null));
        assertThrows(IllegalArgumentException.class, () -> new RadixTree(65537, null));
        assertThrows(IllegalArgumentException.class, () -> new RadixTree(128, null));

        final var TEST = "test".getBytes();
        final var tree = RadixTree.allocate(128, Arena.ofShared());
        assertFalse(tree.add((String) null));
        assertFalse(tree.add((byte[]) null));
        assertFalse(tree.add(0, -1, TEST));
        assertFalse(tree.add(-1, 0, TEST));
        assertFalse(tree.add(0, 0, TEST));
        assertFalse(tree.add(2, 3, TEST));
        assertFalse(tree.add(0, 10, null));

        assertFalse(tree.contains((String) null));
        assertFalse(tree.contains((byte[]) null));
        assertFalse(tree.contains(0, -1, TEST));
        assertFalse(tree.contains(-1, 0, TEST));
        assertFalse(tree.contains(0, 0, TEST));
        assertFalse(tree.contains(2, 3, TEST));
        assertFalse(tree.contains(0, 10, null));

        assertFalse(tree.remove("test"));
        assertFalse(tree.remove(TEST));
        assertFalse(tree.remove(0, 4, TEST));
        assertFalse(tree.remove((String) null));
        assertFalse(tree.remove((byte[]) null));
        assertFalse(tree.remove(0, -1, TEST));
        assertFalse(tree.remove(-1, 0, TEST));
        assertFalse(tree.remove(0, 0, TEST));
        assertFalse(tree.remove(2, 3, TEST));
        assertFalse(tree.remove(0, 10, null));

        assertThrows(IllegalArgumentException.class,
            () -> tree.forEach(0, null, null));
        // assertFalse(tree.forEach( 0, null, System.out::println));
        tree.forEach( 0, null, System.out::println);

        assertThrows(IllegalArgumentException.class, () -> tree.forEach(null));

        assertThrows(IllegalArgumentException.class, () -> tree.forEach(null));
        assertThrows(IllegalArgumentException.class, () -> RadixTree.allocate(63, Arena.ofShared()));
        assertThrows(IllegalArgumentException.class, () ->
            RadixTree.allocate(RadixTree.MAX_BLOCKS_PER_SEGMENT + 1, Arena.ofShared()));
        assertThrows(IllegalArgumentException.class, () ->
            new RadixTree(RadixTree.MAX_BLOCKS_PER_SEGMENT + 1));
    }

    @Test
    public void memoryOverflow() {
        final var tree = new RadixTree(64);
        assertThrows(IllegalStateException.class, () -> {
            for (int i = 0; i < 42_000_000; ++i) {
                tree.add((i + "").getBytes());
            }
        });
        System.out.println(tree);
    }

    @Test
    public void closeTree() {
        final var tree = RadixTree.allocate(128, Arena.ofShared());
        assertTrue(tree.add("12345"));
        tree.close();
        assertThrows(IllegalStateException.class, () -> tree.add("12346"));
    }

    @Test
    public void reuseSlotOnlyAtLastChar() {
        final var tree = new RadixTree();
        final String prefix = "1234567890-";
        final String[] strings = {
            prefix + "A", prefix + "B", prefix + "C", prefix + "D",
            prefix + "E", prefix + "F", prefix + "G", prefix + "H",
            prefix + "I", prefix + "J", prefix + "K",
            prefix + "L1", prefix + "L2", prefix + "L3", prefix + "L4",
            prefix + "L5", prefix + "L6", prefix + "L7", prefix + "L8",
            prefix + "L9", prefix + "LA", prefix + "LB"
        };
        for (String string : strings) {
            assertTrue(tree.add(string));
        }
        assertTrue(tree.remove(prefix + "J"));
        assertTrue(tree.remove(prefix + "K"));
        assertTrue(tree.add(prefix + "LX"));
        assertTrue(tree.contains(prefix + "LX"));
        assertTrue(tree.remove(prefix + "LA"));
        assertTrue(tree.add(prefix + "LY"));
    }

    @Test
    public void forEach() {
        final var tree = new RadixTree();
        check(tree, false, "cat", "cats", "cow", "cabbage", "crow", "pig", "pin", "cabs");
        int[] count = { 0 };
        tree.forEach(node -> {
            System.out.println(node);
            byte header = node.header();
            if (Header.containsString(header)) {
                ++count[0];
            }
            for (int i = 0; i < Header.children(header); ++i) {
                if (node.containsKey(i)) {
                    ++count[0];
                }
            }
        });
        assertEquals(tree.size(), count[0]);
    }

    @Test
    public void removeStrings() {
        final var tree = new RadixTree();
        check(tree, false, "cat", "cats", "cow", "cabbage", "crow", "pig", "pin", "cabs");
        tree.forEach(System.out::println);
        System.out.println("pi");
        assertTrue(tree.removeStrings(2, "pi".getBytes()));
        tree.forEach(System.out::println);
        assertEquals(6, tree.size());

        System.out.println("cab");
        assertTrue(tree.removeStrings(3, "cab".getBytes()));
        tree.forEach(System.out::println);
        assertEquals(4, tree.size());

        System.out.println("cats");
        assertTrue(tree.removeStrings(4, "cats".getBytes()));
        tree.forEach(System.out::println);
        assertEquals(3, tree.size());

        System.out.println("c");
        assertTrue(tree.removeStrings(1, "c".getBytes()));
        assertEmpty(tree);
    }

    @Test
    public void removeStringsWithPrefix() {
        final var tree = new RadixTree();
        check(tree, false, "cat", "cats", "cow", "cabbage", "crow", "pig", "pin", "cabs");

        System.out.println("cats");
        assertTrue(tree.removeStrings(4, "cats".getBytes()));
        assertEquals(7, tree.size());

        System.out.println("cabs");
        assertTrue(tree.removeStrings(4, "cabs".getBytes()));
        assertEquals(6, tree.size());

        assertTrue(tree.removeStrings(4, "crow".getBytes()));
        assertEquals(5, tree.size());

        assertTrue(tree.removeStrings(3, "pig".getBytes()));
        assertEquals(4, tree.size());

        assertTrue(tree.removeStrings(7, "cabbage".getBytes()));
        assertEquals(3, tree.size());

        assertTrue(tree.removeStrings(3, "pin".getBytes()));
        assertTrue(tree.removeStrings(1, "c".getBytes()));
        assertEmpty(tree);
    }

    @Test
    public void removePrefixString() {
        final var tree = new RadixTree();
        check(tree, false, "word", "word_cats", "word_cats_tail");
        assertEquals(3, tree.size());
        assertTrue(tree.removeStrings(14, "word_cats_tail".getBytes()));
        assertFalse(tree.contains("word_cats_tail"));
        assertEquals(2, tree.size());
        assertTrue(tree.removeStrings(9, "word_cats".getBytes()));
        assertFalse(tree.contains("word_cats"));
        assertEquals(1, tree.size());
        assertTrue(tree.removeStrings(4, "word".getBytes()));
        assertFalse(tree.contains("word"));
        assertTrue(tree.isEmpty());
    }

    @Test
    public void addRemoveContainsBasics() {
        final var tree = new RadixTree();
        check(tree, false, "cat", "cats", "cow", "cabbage", "crow", "pig", "pin", "cabs");
        assertTrue(tree.remove("cat".getBytes()));
        assertFalse(tree.contains("cat".getBytes()));
        assertFalse(tree.remove("remove".getBytes()));
        assertTrue(tree.contains("cabbage".getBytes()));
    }

    @Test
    public void forEachPrefixStrings() {
        final var tree = new RadixTree();
        check(tree, false, "cat", "cats");

        tree.forEach(2, "ca".getBytes(), System.out::println);
        System.out.println();

        tree.forEach(1, "C".getBytes(), System.out::println);
    }

    @Test
    public void forEachPrefixKeys() {
        final var tree = new RadixTree();
        check(tree, false, "00cat", "00cats", "00cow", "00cabbage", "01crow", "01pig", "01pin", "01cabs");
        tree.forEach(2, "00".getBytes(), System.out::println);
        System.out.println();
        tree.forEach(System.out::println);
        System.out.println();

        tree.forEach(2, "01".getBytes(), System.out::println);
        System.out.println();

        tree.forEach(2, "99".getBytes(), System.out::println);
    }

    @Test
    public void splitLongerStringWithIncludedKey() {
        final var tree = new RadixTree();
        check(tree, false, "cabbage", "cabs");
        assertFalse(tree.contains("cabb"));
        assertFalse(tree.contains("X"));

        new Checker().check(tree,
            node -> {
                assertEquals("cab", getString(node));
                assertEquals(2, Header.children(node.header()));
                assertEquals('b', node.key(0));
                assertFalse(node.containsKey(0));
                assertEquals('s', node.key(1));
                assertTrue(node.containsKey(1));
            },
            node -> {
                assertEquals("a", getString(node));
                assertFalse(Header.containsString(node.header()));
                assertEquals(1, Header.children(node.header()));
                assertEquals('g', node.key(0));
            },
            node -> {
                assertEquals("e", getString(node));
                assertTrue(Header.containsString(node.header()));
                assertEquals(0, Header.children(node.header()));
            }
        );
    }

    @Test
    public void splitShorterStringWithIncludedKey() {
        final var tree = new RadixTree();
        check(tree, "cat", "cabs");
    }

    @Test
    public void addLongString() {
        final var tree = new RadixTree();
        final String prefix = "12345678901234567890-";
        check(tree, false,
            prefix + "a", prefix + "b", prefix + "c", prefix + "d",
            prefix + "e", prefix + "f", prefix + "g", prefix + "h",
            prefix + "i", prefix + "j", prefix + "k", prefix + "l",
            prefix + "m", prefix + "n", prefix + "o", prefix + "p");
        assertEquals(16, tree.size());

        assertTrue(tree.remove(prefix + "a"));
        assertTrue(tree.remove(prefix + "b"));
        assertTrue(tree.remove(prefix + "c"));
        assertTrue(tree.remove(prefix + "d"));
        assertTrue(tree.remove(prefix + "e"));
        assertEquals(11, tree.size());

        assertTrue(tree.add(prefix + "X"));
        assertTrue(tree.add(prefix + "Y"));
        assertTrue(tree.add(prefix + "Z"));
        assertTrue(tree.add(prefix + "Q"));
        assertTrue(tree.add(prefix + "W"));
        assertTrue(tree.add(prefix + "U"));
        assertEquals(17, tree.size());

        final String[] strings = {
            prefix + "f", prefix + "g", prefix + "h", prefix + "i",
            prefix + "j", prefix + "k", prefix + "l", prefix + "X",
            prefix + "Y", prefix + "Z", prefix + "Q", prefix + "U"
        };
        for (String string : strings) {
            assertTrue(tree.contains(string));
            assertTrue(tree.remove(string));
        }
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
        final String prefix = "abcdefghijklmnop-";
        for (int i = 0; i < count; ++i) {
            assertTrue(tree.add(prefix + i), prefix + i);
        }
        for (int i = 0; i < count; ++i) {
            assertTrue(tree.contains(prefix + i), prefix + i);
        }
        for (int i = 0; i < count; ++i) {
            assertTrue(tree.remove(prefix + i), prefix + i);
        }
        assertEmpty(tree);
    }

    @Test
    public void addRootParent() {
        final var tree = new RadixTree();
        check(tree, "abc", "xyz", "123");
    }

    @Test
    public void addRootParentAndKey() {
        final var tree = new RadixTree();
        check(tree, false, "ab", "xy", "x");
    }

    @Test
    public void addParentChildAndKey() {
        final var tree = new RadixTree();
        check(tree, "abcdef", "abcdxy", "abcdx", "abcd");
    }

    @Test
    public void splitRoot2String15() {
        final var tree = new RadixTree();
        check(tree, "abcdefghijklm028", "abcdefghijklm030");
    }

    @Test
    public void addRootCompleteKeyString2() {
        final var tree = new RadixTree();
        check(tree, "on", "one");
    }

    @Test
    public void addRootCompleteKeyString3() {
        final var tree = new RadixTree();
        check(tree, "cat", "cats");
    }

    @Test
    public void addRootKeyString8() {
        final var tree = new RadixTree();
        check(tree, "ABCDEFG_H", "ABCDEFG_I");
    }

    @Test
    public void splitRoot2String4Keys2() {
        final var tree = new RadixTree();
        check(tree, "m028", "m030");
    }

    @Test
    public void splitRoot3String4Keys3() {
        final var tree = new RadixTree();
        check(tree, "aaaam028", "aaaam029", "aaaam030");
    }

    @Test
    public void splitRoot4String4Keys4() {
        final var tree = new RadixTree();
        check(tree, "aaaam025", "aaaam026", "aaaam027", "aaaam030");
    }

    @Test
    public void splitRoot5String5Keys5() {
        final var tree = new RadixTree();
        check(tree, "aaaam025", "aaaam026", "aaaam027", "aaaam028", "aaaam030");
    }

    @Test
    public void addParentAndCompleteKeys() {
        final var tree = new RadixTree();
        check(tree, "abc250", "abc260", "abc270", "abc280");
    }

    @Test
    public void splitRootString3() {
        final var tree = new RadixTree();
        check(tree, "cat", "car");
    }

    @Test
    public void splitRootString4() {
        final var tree = new RadixTree();
        check(tree, "cars", "cart");
    }

    @Test
    public void addRootString11Keys6() {
        final var tree = new RadixTree();
        final String prefix = "abcdefghij-";
        check(tree, prefix + 0, prefix + 1, prefix + 2, prefix + 3, prefix + 4, prefix + 5);
    }

    @Test
    public void splitRoot2String11Keys1() {
        final var tree = new RadixTree();
        check(tree, "flabbergasted", "flabbergast");
    }

    @Test
    public void splitRootString6() {
        final var tree = new RadixTree();
        final String prefix = "123456789012345-";
        check(tree, prefix + 17, prefix + 18, prefix + 19, prefix + 20);
    }

    @Test
    public void removeCompleteKeyWithChildren() {
        final var tree = new RadixTree();
        final String prefix = "12345678901234567890_";
        check(tree, prefix + "1", prefix + "2", prefix + "10");
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
    public void removeRootEmptyString() {
        final var tree = new RadixTree();
        check(tree, "cat", "pig");
    }

    @Test
    public void removeNodeKey() {
        final var tree = new RadixTree();
        check(tree, "cat", "cats", "pig");
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
                assertFalse(Header.containsString(header));
                assertEquals(1, Header.children(header));
                assertEquals("cat", getString(node));
                assertEquals('s', (char) node.key(0));
                assertTrue(node.containsKey(0));
            }
        );
    }

    @Test
    public void removeRootString11Keys6() {
        final var tree = new RadixTree();
        final String prefix = "abcdefghij-";
        check(tree, prefix + "0", prefix + "1", prefix + "2", prefix + "3", prefix + "4", prefix + "5");
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
        check(tree, "a", "pig");
    }

    @Test
    public void addParentMissingCompleteKey() {
        final var tree = new RadixTree();
        check(tree, "1234567890-1", "1234567890-10", "1234567890-11");
    }

    @Test
    public void missingNodeKey() {
        final var tree = new RadixTree();
        check(tree, "1234D2", "1234D3", "1234D4", "1234D5", "1234D6", "1234D7", "1234D8",
            "1234D9", "1234DA", "1234DB", "1234DC");
    }

    @Test
    public void treeToString() {
        final var tree = new RadixTree();
        assertTrue(tree.add("1234_1"));
        assertTrue(tree.add("1234_2"));
        assertEquals(0, ("RadixTree{ size = 2, BlockPool{ size = 64, blocks = 256," +
            " segments = 1, bytes = 16 384 }}").compareTo(tree.toString()));
    }

    private void addStrings(final String prefix, final int count, final RadixTree tree) {
        for (int i = 1; i <= count; ++i) {
            assertTrue(tree.add(prefix + i));
        }
    }

    @Test
    public void benchmark10M() {
        final int COUNT = 10_000_000;
        final var tree = new RadixTree(RadixTree.MAX_BLOCKS_PER_SEGMENT);
        final String prefix = "abcdefghijklmnop-";
        final byte[] buffer = new byte[COUNT * 25];
        for (int i = 0; i < COUNT; ++i) {
            final byte[] string = String.format(prefix + "%08d", i).getBytes();
            System.arraycopy(string, 0, buffer, i * 25, 25);
        }

        long timestamp = -System.currentTimeMillis();
        for (int i = 0; i < COUNT; ++i) {
            assertTrue(tree.add(i * 25, 25, buffer));
        }
        timestamp += System.currentTimeMillis();
        System.out.println(tree);
        System.out.printf("Add           : %,10d strings in %,6d ms\n", COUNT, timestamp);

        final int[] counts = { 0 };
        timestamp = -System.currentTimeMillis();
        tree.forEach(node -> {
            counts[0] += node.containsStringCount();
        });
        timestamp += System.currentTimeMillis();
        assertEquals(COUNT, counts[0]);
        System.out.printf("ForEach       : %,10d strings in %,6d ms\n", counts[0], timestamp);

        timestamp = -System.currentTimeMillis();
        for (int i = 0; i < COUNT; ++i) {
            assertTrue(tree.contains(i * 25, 25, buffer));
        }
        timestamp += System.currentTimeMillis();
        System.out.printf("Contains      : %,10d strings in %,6d ms\n", COUNT, timestamp);

        timestamp = -System.currentTimeMillis();
        for (int i = 0; i < COUNT; ++i) {
            assertTrue(tree.remove(i * 25, 25, buffer));
        }
        timestamp += System.currentTimeMillis();
        System.out.printf("Remove        : %,10d strings in %,6d ms\n", COUNT, timestamp);

        addStrings(prefix, COUNT, tree);
        counts[0] = 0;
        timestamp = -System.currentTimeMillis();
        byte[] bytes = prefix.getBytes();
        var size = tree.size();
        tree.forEach(bytes.length, bytes, node -> counts[0] += node.containsStringCount());
        timestamp += System.currentTimeMillis();
        assertEquals(COUNT, counts[0]);
        System.out.printf("ForEachPrefix : %,10d strings in %,6d ms\n", counts[0], timestamp);

        timestamp = -System.currentTimeMillis();
        var _ = tree.removeStrings(15, bytes);
        timestamp += System.currentTimeMillis();
        System.out.printf("RemoveStrings : %,10d strings in %,6d ms\n", size, timestamp);

        assertEmpty(tree);
    }

    private void check(RadixTree tree, String...strings) {
        check(tree, true, strings);
    }

    private void check(final RadixTree tree, final boolean remove, final String...strings) {
        for (String string : strings) {
            addContains(tree, string);
        }

        assertEquals(strings.length, tree.size(), "size");
        for (String string : strings) {
            if (tree.add(string)) {
                tree.forEach(System.out::println);
                fail("add failed");
            }
            if (!tree.contains(string)) {
                tree.forEach(System.out::println);
                fail("contains failed");
            }
            if (remove) {
                assertTrue(tree.remove(string), string);
            }
        }
        if (remove) {
            assertEmpty(tree);
            assertEquals(0, tree.size());
        }
    }

    private static void assertEmpty(final RadixTree tree) {
        if (tree.allocatedBlocks() >= 6 || !tree.isEmpty() || tree.size() >= 1) {
            System.out.println(tree);
            tree.forEach(System.out::println);
            fail("tree is not empty");
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
        final int length = Node.Header.stringLength(node.header());
        final byte[] bytes = new byte[length];
        node.string(0, bytes.length, bytes);
        return new String(bytes, 0, length);
    }
}
