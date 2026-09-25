package org.limitless.radix4j;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.limitless.radix4j.Node.Header;

/**
 * Differential tests comparing {@link RadixTree} against a {@link HashSet}, with a structural
 * check of the node graph after every mutation. Strings are mapped one char to one byte (ISO-8859-1)
 * so that the random alphabets can cover the full byte range.
 */
public class RadixTreeFuzzTest {

    private static final int SEEDS = 50;
    private static final int OPERATIONS = 1_000;
    private static final int CONSTRUCTOR_BLOCKS = 5;

    @Test
    public void splitWithOneCharLeftDropsTail() {
        checkStrings("abcd", "abx");
        checkStrings("bcbc", "bcd");
    }

    @Test
    public void splitWithNewStringAsPrefixDropsTail() {
        checkStrings("dcaad", "dca");
        checkStrings("baabaaaabbbb", "ba");
    }

    @Test
    public void splitMustNotCreatePhantomPrefix() {
        checkStrings("abcdef", "abcdx");
        checkStrings("bbbbcc", "bbbba");
        checkStrings("baaabcbacbccc", "baaacacbaac");
    }

    @Test
    public void splitNodeWithChildren() {
        checkStrings("abcde", "abcdefg", "abcdexy", "abx");
        checkStrings("abcde", "abcdefg", "abcdexy", "ab");
        checkStrings("abcd", "abcdefg", "abcdexy", "abcx");
        checkStrings("abcd", "abcdefg", "abcdexy", "abc");
    }

    @Test
    public void containsRejectsMismatchInsideNodeString() {
        final RadixTree tree = new RadixTree();
        assertTrue(tree.add("abc"));
        assertTrue(tree.add("abd"));
        assertFalse(tree.contains("ad"));
        assertFalse(tree.contains("ac"));
        assertTrue(tree.contains("abd"));
        checkStrings("abab", "ab");
    }

    @Test
    public void addKeyToFullNode() {
        // 11 keys fill the root; the 12th key moves to an overflow node
        checkStrings("aa", "ba", "ca", "da", "ea", "fa", "ga", "ha", "ia", "ja", "ka", "la");
        checkStrings("aa", "ba", "ca", "da", "ea", "fa", "ga", "ha", "ia", "ja", "ka", "lab");
        checkStrings("aa", "ba", "ca", "da", "ea", "fa", "ga", "ha", "ia", "ja", "ka", "l");
    }

    @Test
    public void addStringEndingAtKeyOfFullNode() {
        checkStrings("xaa", "xba", "xca", "xda", "xea", "xfa", "xga", "xha", "xia", "xja", "xka", "xa");
        checkStrings("xaa", "xba", "xca", "xda", "xea", "xfa", "xga", "xha", "xia", "xja", "xka", "xk");
    }

    @Test
    public void addExistingOverflowKeyAfterRemove() {
        // "lx" moves 'k' and 'l' to an overflow node; removing "a" frees a root slot,
        // but "l" must update the existing overflow key rather than the root
        checkOperations("+a", "+b", "+c", "+d", "+e", "+f", "+g", "+h", "+i", "+j", "+k", "+lx", "-a", "+l");
    }

    @TestFactory
    public Stream<DynamicTest> addRemoveContains() {
        final List<DynamicTest> tests = new ArrayList<>();
        final int[][] alphabets = { // first byte, size
            { 'a', 2 }, { 'a', 3 }, { 'a', 4 }, { 'a', 16 }, { 0x7e, 4 }, { 0xf0, 16 }
        };
        for (final int[] alphabet : alphabets) {
            for (final int maxLength : new int[] { 4, 8, 14 }) {
                final String name = String.format("first=0x%02x, alphabet=%d, maxLength=%d",
                    alphabet[0], alphabet[1], maxLength);
                tests.add(DynamicTest.dynamicTest(name,
                    () -> assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                        for (int seed = 0; seed < SEEDS; ++seed) {
                            fuzz(seed, (char) alphabet[0], alphabet[1], maxLength);
                        }
                    })));
            }
        }
        return tests.stream();
    }

    private static void fuzz(final int seed, final char first, final int alphabet, final int maxLength) {
        final Random random = new Random(seed);
        final List<String> log = new ArrayList<>();
        final String failure = run(log, random, first, alphabet, maxLength);
        if (failure != null) {
            fail("seed=" + seed + ": " + failure + "\nminimal operations: " + shrink(log));
        }
    }

    /**
     * Runs random operations, recording them in the log: +add, -remove, ?contains, *removeStrings,
     * #forEach(prefix).
     * @return failure description or null
     */
    private static String run(final List<String> log,
                              final Random random,
                              final char first,
                              final int alphabet,
                              final int maxLength) {
        final RadixTree tree = new RadixTree();
        final Set<String> expected = new HashSet<>();
        try {
            for (int i = 0; i < OPERATIONS; ++i) {
                final int operation = switch (random.nextInt(10)) {
                    case 0 -> 3;
                    case 1 -> 4;
                    default -> random.nextInt(3);
                };
                final String string = randomString(random, first, alphabet, operation >= 3 ? 1 + maxLength / 2 : maxLength);
                if (operation == 4) {
                    log.add("#" + string);
                    final String error = checkForEach(tree, expected, string);
                    if (error != null) {
                        return error;
                    }
                    continue;
                } else if (operation == 3) {
                    log.add("*" + string);
                    if (removeStrings(expected, string) != removeStrings(tree, string)) {
                        return "removeStrings(" + string + ") returned wrong value";
                    }
                } else if (operation == 0) {
                    log.add("+" + string);
                    if (expected.add(string) != add(tree, string)) {
                        return "add(" + string + ") returned wrong value";
                    }
                } else if (operation == 1) {
                    log.add("-" + string);
                    if (expected.remove(string) != remove(tree, string)) {
                        return "remove(" + string + ") returned wrong value";
                    }
                } else {
                    log.add("?" + string);
                    if (expected.contains(string) != contains(tree, string)) {
                        return "contains(" + string + ") returned " + contains(tree, string);
                    }
                }
                if (operation != 2) {
                    final String error = verify(tree, expected);
                    if (error != null) {
                        return "after " + log.getLast() + ": " + error;
                    }
                }
            }
            return removeAll(tree, expected);
        } catch (Throwable e) {
            return e.toString();
        }
    }

    private static String replay(final List<String> log) {
        final RadixTree tree = new RadixTree();
        final Set<String> expected = new HashSet<>();
        try {
            for (final String entry : log) {
                final String string = entry.substring(1);
                if (entry.charAt(0) == '?') {
                    if (expected.contains(string) != contains(tree, string)) {
                        return "contains(" + string + ") returned " + contains(tree, string);
                    }
                    continue;
                }
                if (entry.charAt(0) == '#') {
                    final String error = checkForEach(tree, expected, string);
                    if (error != null) {
                        return error;
                    }
                    continue;
                }
                final boolean wrong = switch (entry.charAt(0)) {
                    case '+' -> expected.add(string) != add(tree, string);
                    case '-' -> expected.remove(string) != remove(tree, string);
                    default -> removeStrings(expected, string) != removeStrings(tree, string);
                };
                if (wrong) {
                    return "wrong return value for " + entry;
                }
                final String error = verify(tree, expected);
                if (error != null) {
                    return error;
                }
            }
            return removeAll(tree, expected);
        } catch (Throwable e) {
            return e.toString();
        }
    }

    private static List<String> shrink(final List<String> log) {
        List<String> current = new ArrayList<>(log);
        if (replay(current) == null) {
            return current;
        }
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int i = 0; i < current.size(); ++i) {
                final List<String> candidate = new ArrayList<>(current);
                candidate.remove(i);
                if (replay(candidate) != null) {
                    current = candidate;
                    progress = true;
                    --i;
                }
            }
        }
        return current;
    }

    private static String removeAll(final RadixTree tree, final Set<String> expected) {
        for (final String string : new ArrayList<>(expected)) {
            if (!remove(tree, string)) {
                return "final remove(" + string + ") failed";
            }
            expected.remove(string);
        }
        if (!tree.isEmpty() || tree.allocatedBlocks() > CONSTRUCTOR_BLOCKS) {
            return "tree not empty after removing all strings: " + tree;
        }
        return null;
    }

    /**
     * Checks the node graph (no cycles or shared nodes, valid headers, no dangling keys), the
     * number of stored strings, and that every stored string and none of its other prefixes are found.
     * @return error description or null
     */
    private static String verify(final RadixTree tree, final Set<String> expected) {
        final Set<Integer> visited = new HashSet<>();
        final int[] strings = { 0 };
        try {
            tree.forEach(node -> {
                if (!visited.add(node.offset())) {
                    throw new AssertionError("node visited twice: " + node);
                }
                final byte header = node.header();
                final int count = Header.children(header);
                if (count > Node.BLOCK_COUNT || Header.stringLength(header) > Node.STRING_LENGTH) {
                    throw new AssertionError("invalid header: " + node);
                }
                if (Header.containsString(header)) {
                    ++strings[0];
                }
                for (int i = 0; i < count; ++i) {
                    if (node.containsKey(i)) {
                        ++strings[0];
                    } else if (node.child(i) == Node.EMPTY_BLOCK) {
                        throw new AssertionError("dangling key " + i + ": " + node);
                    }
                }
            });
        } catch (AssertionError e) {
            return e.getMessage();
        }
        if (tree.size() != expected.size() || strings[0] != expected.size()) {
            return "expected " + expected.size() + " strings, size = " + tree.size() + ", stored = " + strings[0];
        }
        for (final String string : expected) {
            for (int i = 1; i <= string.length(); ++i) {
                final String prefix = string.substring(0, i);
                if (expected.contains(prefix) != contains(tree, prefix)) {
                    return "contains(" + prefix + ") returned " + contains(tree, prefix);
                }
            }
        }
        return null;
    }

    /**
     * Checks that forEach(prefix) visits distinct nodes holding exactly the strings starting with the
     * prefix. The prefix itself is not visited when it is stored at a key of the parent node.
     * @return error description or null
     */
    private static String checkForEach(final RadixTree tree, final Set<String> expected, final String prefix) {
        final byte[] bytes = bytes(prefix);
        final Set<Integer> visited = new HashSet<>();
        final int[] strings = { 0 };
        tree.forEach(bytes.length, bytes, node -> {
            if (!visited.add(node.offset())) {
                throw new AssertionError("forEach(" + prefix + ") visited node twice: " + node);
            }
            final byte header = node.header();
            if (Header.containsString(header)) {
                ++strings[0];
            }
            for (int i = 0; i < Header.children(header); ++i) {
                if (node.containsKey(i)) {
                    ++strings[0];
                }
            }
        });
        final long all = expected.stream().filter(string -> string.startsWith(prefix)).count();
        final long longer = all - (expected.contains(prefix) ? 1 : 0);
        if (strings[0] != all && strings[0] != longer) {
            return "forEach(" + prefix + ") visited " + strings[0] + " strings, expected " + all;
        }
        return null;
    }

    private static void checkStrings(final String... strings) {
        final String[] operations = new String[strings.length];
        for (int i = 0; i < strings.length; ++i) {
            operations[i] = "+" + strings[i];
        }
        checkOperations(operations);
    }

    private static void checkOperations(final String... operations) {
        final String error = replay(Arrays.asList(operations));
        assertNull(error, () -> Arrays.toString(operations) + ": " + error);
    }

    private static String randomString(final Random random, final char first, final int alphabet, final int maxLength) {
        final int length = 1 + random.nextInt(maxLength);
        final StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; ++i) {
            builder.append((char) (first + random.nextInt(alphabet)));
        }
        return builder.toString();
    }

    private static byte[] bytes(final String string) {
        return string.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static boolean add(final RadixTree tree, final String string) {
        return tree.add(bytes(string));
    }

    private static boolean remove(final RadixTree tree, final String string) {
        return tree.remove(bytes(string));
    }

    private static boolean contains(final RadixTree tree, final String string) {
        return tree.contains(bytes(string));
    }

    private static boolean removeStrings(final RadixTree tree, final String prefix) {
        final byte[] bytes = bytes(prefix);
        return tree.removeStrings(bytes.length, bytes);
    }

    private static boolean removeStrings(final Set<String> expected, final String prefix) {
        return expected.removeIf(string -> string.startsWith(prefix));
    }
}
