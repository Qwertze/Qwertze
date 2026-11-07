package com.porter.qwerte.manager.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowModeTest {
    @Test
    void offMeansLocalAndNoNetwork() {
        assertEquals(WindowMode.LOCAL, WindowMode.of(false));
        assertFalse(WindowMode.of(false).network());
    }

    @Test
    void onMeansServer() {
        assertEquals(WindowMode.SERVER, WindowMode.of(true));
        assertTrue(WindowMode.of(true).network());
    }

    @Test
    void localModeNeverAllowsServerSources() {
        // the screen builds its policy with serverMode = LOCAL.network()
        DisplayPolicy local = new DisplayPolicy(false, false, WindowMode.LOCAL.network());
        assertTrue(local.allowLocalSources());
        assertFalse(local.streamer());
        assertTrue(new DisplayPolicy(false, true, false).hideCoords(), "the local toggle hides coordinates");
        assertFalse(new DisplayPolicy(false, false, true).allowLocalSources());
    }
}
