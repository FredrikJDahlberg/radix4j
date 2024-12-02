package org.limitless.radix4j;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Checker14 {

    private Consumer<Node14>[] checkers;
    private int position;

    @SafeVarargs
    public final void check(final RadixTree14 tree, final Consumer<Node14>... checkers) {
        this.checkers = checkers;
        position = 0;
        tree.forEach(this::test);
        assertEquals(checkers.length, position, "more nodes expected");
    }

    private void test(final Node14 node) {
        assertTrue(position < checkers.length, "fewer nodes expected");
        checkers[position].accept(node);
        ++position;
    }
}