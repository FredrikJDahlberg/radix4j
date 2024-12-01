package org.limitless.radix4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RadixTree14Test {

    @Test
    public void addBasics() {
        final var tree = new RadixTree14();
        addContains(tree, "cat");
        addContains(tree, "cats");
        addContains(tree, "cow");
        addContains(tree, "pig");
        addContains(tree, "pin");
        addContains(tree, "crow");
    }

    @Test
    public void removeBasics() {
        final var tree = new RadixTree14();
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
        final var tree = new RadixTree14();
        assertTrue(tree.add("12345678901234567890-0"));
        tree.forEach(System.out::println);
        assertTrue(tree.contains("12345678901234567890-0"));
    }

    private static void assertEmpty(final RadixTree14 tree) {
        assertTrue(tree.isEmpty());
        assertEquals(0, tree.size());
        if (tree.allocatedBlocks() >= 4) {
            tree.forEach(System.out::println);
            fail("tree not empty");
        }
    }

    private static void addContains(final RadixTree14 tree, final String string) {
        final int size = tree.size();
        final boolean empty = tree.isEmpty();
        if (!tree.add(string)) {
            tree.forEach(System.out::println);
            fail("failed add: string =  " + string);
        }
        if (!tree.contains(string)) {
            fail("failed contains: string = " + string);
            tree.forEach(System.out::println);
        }
        assertEquals(empty, size == 0);
        assertEquals(size + 1, tree.size());
    }
}
