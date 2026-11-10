package com.porter.stashmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The start-up output (a service's journal) shows the bots' API key only on the run that generated it. */
class StartupBannerTest {
    @TempDir
    Path dir;

    static String banner(boolean generated, String key) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            StashManagerServer.printKeyNotice(out, generated, key);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theKeyIsPrintedOnlyWhenItWasJustGenerated() throws Exception {
        Config first = Config.load(dir);
        assertTrue(first.generatedKey);
        String shown = banner(first.generatedKey, first.apiKey());
        assertTrue(shown.contains(first.apiKey()));
        assertTrue(shown.contains("first setup: copy it now; it won't be shown again"));
        // Every later start: the key is set, the banner does not carry it.
        Config later = Config.load(dir);
        assertFalse(later.generatedKey);
        assertEquals(first.apiKey(), later.apiKey());
        String quiet = banner(later.generatedKey, later.apiKey());
        assertFalse(quiet.contains(later.apiKey()));
        assertTrue(quiet.contains("API key: set (use the website's \"Link addon\" button to link a bot)"));
    }

    @Test
    void theSourceHasNoOtherPrintOfTheKey() throws Exception {
        // Nothing but the banner method may print the key: look for the accessor next to a print call in the sources.
        try (var files = java.nio.file.Files.walk(Path.of("src", "main", "java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : java.nio.file.Files.readAllLines(f)) {
                    if ((line.contains("System.out") || line.contains("System.err")) && line.contains("apiKey()")) {
                        assertTrue(f.endsWith("StashManagerServer.java") && line.contains("printKeyNotice"), f + ": " + line.trim());
                    }
                }
            }
        }
    }
}
