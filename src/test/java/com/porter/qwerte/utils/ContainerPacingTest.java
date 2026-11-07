package com.porter.qwerte.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerPacingTest {
    @Test
    void zeroDelayNeverHolds() {
        assertFalse(ContainerPacing.openHeld(100, 100, 0));
        assertFalse(ContainerPacing.openHeld(100, 99, 0));
        assertEquals(3, ContainerPacing.firstClickWait(3, 0));
    }

    @Test
    void holdsUntilEnoughTicksPassed() {
        assertTrue(ContainerPacing.openHeld(100, 100, 5));
        assertTrue(ContainerPacing.openHeld(104, 100, 5));
        assertFalse(ContainerPacing.openHeld(105, 100, 5));
        assertFalse(ContainerPacing.openHeld(500, 100, 5));
    }

    @Test
    void neverClosedDoesNotHold() {
        assertFalse(ContainerPacing.openHeld(10, -100000, 100));
    }

    @Test
    void firstClickAddsTheDelay() {
        assertEquals(13, ContainerPacing.firstClickWait(3, 10));
        assertEquals(10, ContainerPacing.firstClickWait(0, 10));
        assertEquals(3, ContainerPacing.firstClickWait(3, -4));
    }
}
