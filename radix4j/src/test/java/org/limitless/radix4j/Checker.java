package org.limitless.radix4j;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Checker {

    private Consumer<Node3>[] checkers;
        private int position;

    @SafeVarargs
    public final void check(final RadixTree tree, final Consumer<Node3>... checkers) {
        this.checkers = checkers;
        position = 0;
        tree.forEach(this::test);
        assertEquals(checkers.length, position, "more nodes expected");
    }

    private void test(final Node3 node) {
        assertTrue(position < checkers.length, "fewer nodes expected");
        checkers[position].accept(node);
        ++position;
    }
}
