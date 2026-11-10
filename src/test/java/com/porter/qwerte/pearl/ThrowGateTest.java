package com.porter.qwerte.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThrowGateTest {
    @Test
    void priorityIsTheHighOneOfThePrinterFix() {
        assertEquals(100, ThrowGate.ROTATION_PRIORITY);
    }

    @Test
    void theUseIsOnlyAllowedAfterTheRotationCallback() {
        ThrowGate g = new ThrowGate();
        assertFalse(g.pending());
        long t = g.request(0);
        assertTrue(g.pending());
        assertTrue(g.rotationSent(t));
        g.finished();
        assertFalse(g.pending());
    }

    @Test
    void aSecondRequestWhileOneWaitsIsRefused() {
        ThrowGate g = new ThrowGate();
        long t = g.request(0);
        assertEquals(-1, g.request(0));
        assertTrue(g.rotationSent(t));
    }

    @Test
    void aCancelledRequestsCallbackDoesNotThrow() {
        ThrowGate g = new ThrowGate();
        long t = g.request(0);
        g.cancel();
        assertFalse(g.pending());
        assertFalse(g.rotationSent(t));
    }

    @Test
    void anOldCallbackCannotUseANewRequest() {
        ThrowGate g = new ThrowGate();
        long old = g.request(0);
        g.cancel();
        long fresh = g.request(0);
        assertTrue(fresh != old);
        assertFalse(g.rotationSent(old));
        assertTrue(g.rotationSent(fresh));
    }

    @Test
    void aCallbackAfterTheUseWasFinishedDoesNothing() {
        ThrowGate g = new ThrowGate();
        long t = g.request(0);
        g.finished();
        assertFalse(g.rotationSent(t));
    }

    @Test
    void aRefusedUseCanBeRequestedAgain() {
        ThrowGate g = new ThrowGate();
        long t = g.request(0);
        assertTrue(g.rotationSent(t));
        g.finished();
        long again = g.request(0);
        assertTrue(again > 0);
        assertTrue(g.rotationSent(again));
        assertFalse(g.rotationSent(t));
    }

    @Test
    void aRequestWhoseCallbackNeverRunsExpires() {
        ThrowGate g = new ThrowGate();
        long t = g.request(100);
        assertFalse(g.expire(100 + ThrowGate.EXPIRY_TICKS - 1));
        assertTrue(g.pending());
        assertTrue(g.expire(100 + ThrowGate.EXPIRY_TICKS));
        assertFalse(g.pending());
        assertFalse(g.rotationSent(t));
        // The throw phase can ask again, and nothing is left to expire
        assertTrue(g.request(110) > 0);
        assertFalse(g.expire(110));
    }

    @Test
    void nothingExpiresWhenNothingIsWaiting() {
        ThrowGate g = new ThrowGate();
        assertFalse(g.expire(1000));
        long t = g.request(0);
        g.finished();
        assertFalse(g.expire(1000));
        assertFalse(g.rotationSent(t));
    }
}
