package com.porter.qwerte.manager.gui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Meteor's WTextBox calls its CharFilter for every typed character, so a text box made with a null filter can't be
 * typed into at all (each key throws). That is how the password boxes broke in 0.4.0-dev.8. No textBox(...) call in
 * the addon may pass null as the filter: the 3-argument form textBox(text, filter, renderer) and the 4-argument form
 * textBox(text, placeholder, filter, renderer).
 */
class TextBoxFilterTest {
    // textBox(a, b, c, X.class) with c == null, or textBox(a, b, X.class) with b == null
    private static final Pattern FOUR = Pattern.compile("textBox\\(([^,;()]|\\([^()]*\\))+,([^,;()]|\\([^()]*\\))+,\\s*null\\s*,[^;]*?\\.class\\)");
    private static final Pattern THREE = Pattern.compile("textBox\\(([^,;()]|\\([^()]*\\))+,\\s*null\\s*,[^,;]*?\\.class\\)");

    @Test
    void noTextBoxIsCreatedWithANullFilter() throws IOException {
        List<String> bad = new ArrayList<>();
        Path root = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(root), "run from the project root");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                for (Pattern pattern : new Pattern[] { FOUR, THREE }) {
                    Matcher m = pattern.matcher(src);
                    while (m.find()) bad.add(p + ": " + m.group());
                }
            }
        }
        assertTrue(bad.isEmpty(), "text boxes with a null CharFilter (nothing can be typed): " + bad);
    }

    @Test
    void thePatternsCatchTheOldPasswordBox() {
        assertTrue(FOUR.matcher("theme.textBox(\"\", null, null, MaskedRenderer.class)").find());
        assertTrue(FOUR.matcher("theme.textBox(text, null, null, MaskedRenderer.class)").find());
        assertTrue(THREE.matcher("theme.textBox(text, null, MaskedRenderer.class)").find());
        assertTrue(!FOUR.matcher("theme.textBox(\"\", null, MaskedRenderer.ANY_CHAR, MaskedRenderer.class)").find());
    }
}
