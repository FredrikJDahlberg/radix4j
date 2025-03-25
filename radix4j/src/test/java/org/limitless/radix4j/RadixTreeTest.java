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
    public void errorChecks() {
        final var tree = RadixTree.allocate(128, Arena.ofShared());
        assertFalse(tree.add((String) null));
        assertFalse(tree.add((byte[]) null));
        assertFalse(tree.add(0, -1, "test".getBytes()));
        assertFalse(tree.add(-1, 0, "test".getBytes()));
        assertFalse(tree.add(0, 0, "test".getBytes()));
        assertFalse(tree.add(2, 3, "test".getBytes()));
        assertFalse(tree.add(0, 10, null));

        assertFalse(tree.contains((String) null));
        assertFalse(tree.contains((byte[]) null));
        assertFalse(tree.contains(0, -1, "test".getBytes()));
        assertFalse(tree.contains(-1, 0, "test".getBytes()));
        assertFalse(tree.contains(0, 0, "test".getBytes()));
        assertFalse(tree.contains(2, 3, "test".getBytes()));
        assertFalse(tree.contains(0, 10, null));

        assertFalse(tree.remove("test"));
        assertFalse(tree.remove("test".getBytes()));
        assertFalse(tree.remove(0, 4, "test".getBytes()));
        assertFalse(tree.remove((String) null));
        assertFalse(tree.remove((byte[]) null));
        assertFalse(tree.remove(0, -1, "test".getBytes()));
        assertFalse(tree.remove(-1, 0, "test".getBytes()));
        assertFalse(tree.remove(0, 0, "test".getBytes()));
        assertFalse(tree.remove(2, 3, "test".getBytes()));
        assertFalse(tree.remove(0, 10, null));

        assertThrows(IllegalArgumentException.class, () -> tree.forEach(null));
        assertThrows(IllegalArgumentException.class, () -> RadixTree.allocate(63, Arena.ofShared()));
        assertThrows(IllegalArgumentException.class, () ->
            RadixTree.allocate(RadixTree.MAX_BLOCKS_PER_SEGMENT + 1, Arena.ofShared()));
        assertThrows(IllegalArgumentException.class, () ->
            new RadixTree(RadixTree.MAX_BLOCKS_PER_SEGMENT + 1));

        final var small = new RadixTree(64);
        assertThrows(IllegalStateException.class, () -> {
            for (int i = 0; i < 85_000_000; ++i) {
                small.add("" + i);
            }
        });
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
    public void addRemoveContainsBasics() {
        final var tree = new RadixTree();
        check(tree, false, "cat", "cats", "cow", "cabbage", "crow", "pig", "pin", "cabs");
        assertTrue(tree.remove("cat"));
        assertFalse(tree.contains("cat"));
    }

    @Test
    public void containsPrefixStrings() {
        final var tree = new RadixTree();
        check(tree, false, "cat", "cats");

        assertTrue(tree.startsWith(0, 2, "ca".getBytes(), System.out::println));
        System.out.println();

        assertFalse(tree.startsWith(0, 1, "C".getBytes(), System.out::println));
    }

    @Test
    public void containsPrefixKeys() {
        final var tree = new RadixTree();
        check(tree, false, "00cat", "00cats", "00cow", "00cabbage", "01crow", "01pig", "01pin", "01cabs");
        assertTrue(tree.startsWith(0, 2, "00".getBytes(), System.out::println));
        System.out.println();

        assertTrue(tree.startsWith(0, 2, "01".getBytes(), System.out::println));
        System.out.println();

        assertFalse(tree.startsWith(0, 2, "99".getBytes(), System.out::println));
    }

    @Test
    public void splitLongerStringWithIncludedKey() {
        final var tree = new RadixTree();
        check(tree, false, "cabbage", "cabs");
        assertFalse(tree.contains("cabb"));

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

    @Test
    public void benchmark10M() {
        final int count = 10_000_000;
        final var tree = new RadixTree(RadixTree.MAX_BLOCKS_PER_SEGMENT);
        final String prefix = "abcdefghijklmnop-";
        long timestamp = System.currentTimeMillis();
        for (int i = 1; i <= count; ++i) {
            assertTrue(tree.add(prefix + i));
        }
        System.out.printf("Add     : %,d strings in %,6d ms\n", count, System.currentTimeMillis() - timestamp);

        final String string = tree.toString();
        final int[] counts = { 0 };
        timestamp = System.currentTimeMillis();
        tree.forEach(node -> {
            final byte header = node.header();
            if (Header.containsString(header)) {
                ++counts[0];
            }
            final int keys = Header.children(header);
            for (int i = 0; i < keys; ++i) {
                if (node.containsKey(i)) {
                    ++counts[0];
                }
            }
        });
        System.out.printf("ForEach : %,d strings in %,6d ms\n", counts[0], System.currentTimeMillis() - timestamp);

        timestamp = System.currentTimeMillis();
        for (int i = 1; i <= count; ++i) {
            assertTrue(tree.contains(prefix + i));
        }
        System.out.printf("Contains: %,d strings in %,6d ms\n", count, System.currentTimeMillis() - timestamp);

        timestamp = System.currentTimeMillis();
        for (int i = 1; i <= count; ++i) {
            assertTrue(tree.remove(prefix + i));
        }
        System.out.printf("Remove  : %,d strings in %,6d ms\n", count, System.currentTimeMillis() - timestamp);
        System.out.println(string);

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
        node.string(0, bytes.length, bytes);
        return new String(bytes, 0, length);
    }
}
