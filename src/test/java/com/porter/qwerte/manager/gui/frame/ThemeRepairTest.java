package com.porter.qwerte.manager.gui.frame;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Meteor's widgets pull in Minecraft's static init, so the walk is tested on a fake tree. */
class ThemeRepairTest {
    private static final class Node {
        Object theme;
        final List<Node> kids = new ArrayList<>();

        Node add(Node n) {
            n.theme = theme; // what WContainer.add does
            kids.add(n);
            return n;
        }
    }

    private static int run(Node root) {
        return ThemeRepair.fill(root, n -> n.kids, n -> n.theme, (n, t) -> n.theme = t);
    }

    @Test
    void fixesSubtreeFilledBeforeAttach() {
        Object theme = new Object();
        Node root = new Node();
        root.theme = theme;
        Node detached = new Node();
        Node deeper = detached.add(new Node());
        Node leaf = deeper.add(new Node());
        root.add(detached);
        assertNull(deeper.theme);
        assertNull(leaf.theme);
        assertEquals(2, run(root));
        assertSame(theme, deeper.theme);
        assertSame(theme, leaf.theme);
        assertEquals(0, run(root));
    }

    @Test
    void leavesAThemedTreeAlone() {
        Object theme = new Object();
        Node root = new Node();
        root.theme = theme;
        root.add(new Node()).add(new Node());
        assertEquals(0, run(root));
    }
}
