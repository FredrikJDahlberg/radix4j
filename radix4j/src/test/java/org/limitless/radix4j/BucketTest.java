package org.limitless.radix4j;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.limitless.radix4j.Node.Header;
import static org.limitless.radix4j.RadixTreeTest.tails;

/**
 * Buckets holding the tails of several strings, and the subtrees they split into when full.
 */
public class BucketTest {

    @Test
    public void tailsShareABucket() {
        final var tree = new RadixTree();
        final List<String> strings = List.of("ARB00045612X7Y", "ARB00045613X7Y", "ARB0004561", "ARB00045614");
        for (final String string : strings) {
            assertTrue(tree.add(string));
        }
        new Checker().check(tree, node -> assertEquals(strings, tails(node)));
        assertFalse(tree.contains("ARB00045612X7"));
        assertFalse(tree.contains("ARB00045612X7YZ"));
        assertTrue(tree.remove("ARB00045613X7Y"));
        assertFalse(tree.contains("ARB00045613X7Y"));
        new Checker().check(tree,
            node -> assertEquals(List.of("ARB00045612X7Y", "ARB0004561", "ARB00045614"), tails(node)));
    }

    @Test
    public void fullBucketSplitsAtTheCommonPrefix() {
        final var tree = new RadixTree();
        // 5 tails of 14 bytes take 75 bytes: the bucket becomes a node holding 5 bytes of the common prefix and
        // a key for the next, followed by a bucket with the rest of the tails
        final List<String> strings = new ArrayList<>();
        for (int i = 0; i < 5; ++i) {
            strings.add("ARB0004561" + i + "X7Y");
            assertTrue(tree.add(strings.getLast()));
        }
        new Checker().check(tree,
            node -> {
                assertEquals("ARB00", string(node));
                assertFalse(Header.containsString(node.header()));
                assertEquals(1, Header.children(node.header()));
                assertEquals('0', node.key(0));
                assertFalse(node.containsKey(0));
            },
            node -> assertEquals(List.of("45610X7Y", "45611X7Y", "45612X7Y", "45613X7Y", "45614X7Y"), tails(node))
        );
        for (final String string : strings) {
            assertTrue(tree.contains(string));
        }
        // the next digits go to buckets below the new keys
        assertTrue(tree.add("ARB00045615X7Y"));
        assertTrue(tree.add("ARB00045615X7Z"));
        assertTrue(tree.contains("ARB00045615X7Z"));
    }

    @Test
    public void splitRecordsStringsEndingAtTheNodeAndKeys() {
        final var tree = new RadixTree();
        final String tail = "-".repeat(28);
        for (final String string : List.of("ab", "abc" + tail, "abd" + tail, "abc")) {
            assertTrue(tree.add(string));
        }
        new Checker().check(tree,
            node -> {
                assertEquals("ab", string(node));
                assertTrue(Header.containsString(node.header()));
                assertEquals(2, Header.children(node.header()));
                assertEquals('c', node.key(0));
                assertTrue(node.containsKey(0));
                assertEquals('d', node.key(1));
                assertFalse(node.containsKey(1));
                assertEquals(node.child(0), node.child(1));
            },
            // both keys share a hybrid bucket, its tails keep their key
            node -> {
                assertTrue(node.isHybrid());
                assertEquals(List.of("c" + tail, "d" + tail), tails(node));
            }
        );
        assertEquals(4, tree.size());
    }

    @Test
    public void keysShareHybridBuckets() {
        final var tree = new RadixTree(RadixTree.DEFAULT_BLOCKS_PER_SEGMENT, Arena.ofShared(), 3);
        // the 4th string splits the bucket into a node "k" with a key per digit: 3 keys share a hybrid bucket,
        // the 4th gets a bucket of its own
        for (final String string : List.of("k1xy", "k2xy", "k3xy", "k4xy")) {
            assertTrue(tree.add(string));
        }
        final List<List<String>> expected = new ArrayList<>(List.of(List.of("1xy", "2xy", "3xy"), List.of("xy")));
        assertEquals(expected, buckets(tree));
        // new keys join the nearest bucket with room, which becomes hybrid
        assertTrue(tree.add("k5xy"));
        assertTrue(tree.add("k6xy"));
        assertEquals(List.of(List.of("1xy", "2xy", "3xy"), List.of("4xy", "5xy", "6xy")), buckets(tree));
        // a full hybrid bucket is shared out among its keys again
        assertTrue(tree.add("k45z"));
        assertEquals(List.of(List.of("1xy", "2xy", "3xy"), List.of("45z", "4xy", "5xy"), List.of("xy")), buckets(tree));
        for (final String string : List.of("k1xy", "k2xy", "k3xy", "k4xy", "k5xy", "k6xy", "k45z")) {
            assertTrue(tree.contains(string), string);
        }
        assertFalse(tree.contains("k4x"));
        assertFalse(tree.contains("k7xy"));
        // removing the last tail of a key removes the key, and an empty bucket is freed
        assertTrue(tree.remove("k5xy"));
        assertFalse(tree.contains("k5xy"));
        assertEquals(List.of(List.of("1xy", "2xy", "3xy"), List.of("45z", "4xy"), List.of("xy")), buckets(tree));
        assertTrue(tree.removeStrings(2, "k4".getBytes()));
        assertEquals(List.of(List.of("1xy", "2xy", "3xy"), List.of("xy")), buckets(tree));
        assertTrue(tree.removeStrings(3, "k2x".getBytes()));
        assertEquals(3, tree.size());
        for (final String string : List.of("k1xy", "k3xy", "k6xy")) {
            assertTrue(tree.remove(string), string);
        }
        assertFalse(tree.remove("k2xy"));
        assertTrue(tree.isEmpty());
        assertEquals(5, tree.allocatedBlocks());
    }

    /**
     * Tails of the buckets in the tree, hybrid buckets first, each sorted by their tails
     */
    private static List<List<String>> buckets(final RadixTree tree) {
        final List<List<String>> hybrid = new ArrayList<>();
        final List<List<String>> buckets = new ArrayList<>();
        tree.forEach(node -> {
            if (node.isBucket()) {
                (node.isHybrid() ? hybrid : buckets).add(tails(node));
            }
        });
        hybrid.sort(Comparator.comparing(List::toString));
        buckets.sort(Comparator.comparing(List::toString));
        hybrid.addAll(buckets);
        return hybrid;
    }

    @Test
    public void splitWithMoreKeysThanANodeHolds() {
        final var tree = new RadixTree(RadixTree.DEFAULT_BLOCKS_PER_SEGMENT, Arena.ofShared(), Node.MAX_BUCKET_STRINGS);
        // 31 one-byte tails fill the bucket, the 32nd splits it into 32 keys: 10 in the node and in each of
        // two overflow nodes, 2 in a third
        final List<String> strings = new ArrayList<>();
        for (int i = 0; i < 32; ++i) {
            strings.add(String.valueOf((char) ('0' + i)));
            assertTrue(tree.add(strings.getLast()));
        }
        final int[] overflows = { 0 };
        tree.forEach(node -> {
            assertFalse(node.isBucket());
            if (node.overflow() != Node.NOT_FOUND) {
                ++overflows[0];
            }
        });
        assertEquals(3, overflows[0]);
        for (final String string : strings) {
            assertTrue(tree.contains(string), string);
        }
        assertEquals(32, tree.size());
        for (final String string : strings) {
            assertTrue(tree.remove(string), string);
        }
        assertTrue(tree.isEmpty());
        assertEquals(5, tree.allocatedBlocks());
    }

    @Test
    public void tailLongerThanABucket() {
        final var tree = new RadixTree();
        final String prefix = "prefix-";
        final String longTail = prefix + "x".repeat(200);
        assertTrue(tree.add(prefix + "a"));
        assertTrue(tree.add(longTail));
        assertTrue(tree.add(longTail + "y"));
        assertTrue(tree.contains(longTail));
        assertTrue(tree.contains(longTail + "y"));
        assertFalse(tree.contains(longTail.substring(0, 100)));
        assertTrue(tree.remove(longTail));
        assertTrue(tree.remove(longTail + "y"));
        assertTrue(tree.remove(prefix + "a"));
        assertTrue(tree.isEmpty());
        assertEquals(5, tree.allocatedBlocks());
    }

    @Test
    public void removeStringsInsideABucket() {
        final var tree = new RadixTree();
        for (final String string : List.of("order-1", "order-12", "order-2", "other")) {
            assertTrue(tree.add(string));
        }
        assertFalse(tree.removeStrings(7, "order-3".getBytes()));
        assertTrue(tree.removeStrings(7, "order-1".getBytes()));
        assertEquals(2, tree.size());
        assertFalse(tree.contains("order-1"));
        assertFalse(tree.contains("order-12"));
        assertTrue(tree.contains("order-2"));
        assertTrue(tree.contains("other"));
        assertTrue(tree.removeStrings(1, "o".getBytes()));
        assertTrue(tree.isEmpty());
        assertEquals(5, tree.allocatedBlocks());
    }

    @Test
    public void forEachVisitsTheBucketWhereThePrefixEnds() {
        final var tree = new RadixTree();
        for (final String string : List.of("order-1", "order-12", "other")) {
            assertTrue(tree.add(string));
        }
        final byte[] prefix = "order".getBytes();
        final List<List<String>> visited = new ArrayList<>();
        tree.forEach(prefix.length, prefix, node -> visited.add(tails(node)));
        assertEquals(List.of(List.of("order-1", "order-12", "other")), visited);
        final byte[] missing = "orders".getBytes();
        tree.forEach(missing.length, missing, node -> fail("visited " + node));
    }

    @Test
    public void oneStringBucketsAreLeaves() {
        final var tree = new RadixTree(RadixTree.DEFAULT_BLOCKS_PER_SEGMENT, Arena.ofShared(), 1);
        assertTrue(tree.add("cat"));
        assertTrue(tree.add("caterpillar"));
        new Checker().check(tree,
            node -> {
                assertEquals("cat", string(node));
                assertTrue(Header.containsString(node.header()));
                assertEquals('e', node.key(0));
            },
            node -> assertEquals(List.of("rpillar"), tails(node))
        );
        assertThrows(IllegalArgumentException.class, () ->
            new RadixTree(RadixTree.DEFAULT_BLOCKS_PER_SEGMENT, Arena.ofShared(), 0));
        assertThrows(IllegalArgumentException.class, () ->
            new RadixTree(RadixTree.DEFAULT_BLOCKS_PER_SEGMENT, Arena.ofShared(),
                Node.MAX_BUCKET_STRINGS + 1));
    }

    private static String string(final Node node) {
        final int length = Header.stringLength(node.header());
        final byte[] bytes = new byte[length];
        node.string(0, length, bytes);
        return new String(bytes);
    }
}
