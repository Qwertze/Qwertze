package com.porter.qwerte.manager.gui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-dev.10 crashed the game the moment the Stash window was opened: BrowseTab built the item grid into its own
 * field only, so WStashPage.grid stayed null and the first layout threw. WStashPage lays out five children; each must be
 * handed to the page when BrowseTab builds it, and the page must not touch them before they are all there.
 */
class StashPageWiringTest {
    private static final Path TABS = Path.of("src", "main", "java", "com", "porter", "qwerte", "manager", "gui", "tabs");

    @Test
    void browseTabHandsEveryLaidOutChildToThePage() throws IOException {
        String browse = Files.readString(TABS.resolve("BrowseTab.java"), StandardCharsets.UTF_8);
        for (String field : new String[] { "tabs", "grid", "search", "sort", "details" }) {
            assertTrue(Pattern.compile("page\\." + field + "\\s*=").matcher(browse).find(),
                "BrowseTab never sets page." + field + ": WStashPage would lay out a null child");
        }
    }

    @Test
    void theStashPageWaitsUntilItsChildrenAreThere() throws IOException {
        String page = Files.readString(TABS.resolve("WStashPage.java"), StandardCharsets.UTF_8);
        assertTrue(page.contains("if (!ready())"), "WStashPage.calculateSize must return early until every child is set");
        assertTrue(page.contains("if (lay == null || !ready()) return;"), "WStashPage must not position children before its layout exists");
    }
}
